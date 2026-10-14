package com.filmbox.archive;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * 从 zip 压缩包里导入照片。
 *
 * 为什么是「先解到临时文件」而不是「读进 byte[]」：zip 的 entry 只能顺着读
 * 一次，而导入一张要读它三遍（量尺寸 / 读 EXIF / 解码）。读进 byte[] 的话一张
 * 80MB 的扫描件就要整份驻留内存，三线程并发能到几百 MB。先落成临时文件，
 * 内存恒定在 64KB 缓冲，而且下游走的是和相册导入完全一样的代码。
 *
 * 只认标准 zip（deflate / store）。rar、7z 会靠文件头认出来并给一句人话；
 * 加密 zip 读的时候会抛 ZipException，也转成同一类提示。这两件事都不引第三方库。
 *
 * 不按压缩包里的文件名落地 —— 每张都重新编码成自己的编号文件，所以
 * ../ 之类的 entry 名（zip-slip）在这里结构上就无处可躲。
 */
public class ZipImporter {

    private static final String TAG = "FilmboxZip";

    /** entry 数上限。塞几千个文件的压缩包多半不是冲扫件。 */
    public static final int MAX_ENTRIES = 2000;
    /** 单个 entry 上限。超过这个大小基本可以断定不是扫描件。 */
    public static final long MAX_ENTRY_BYTES = 512L * 1024 * 1024;

    /** 跟 PhotoStore.extensionOf 的白名单保持一致 —— 不一致的话
     *  keepOriginal 写出来的后缀会跟 doc.json 里记的对不上。 */
    private static final String[] IMG_EXT = {
            "jpg", "jpeg", "png", "webp", "heic", "heif", "tif", "tiff", "bmp"
    };

    // ---------------------------------------------------------------- 文件头

    private static final int FMT_ZIP = 0, FMT_RAR = 1, FMT_7Z = 2, FMT_OTHER = 3;

    /** 只认「PK\x03\x04」这类本地文件头，还有空归档和分卷的两个变体。 */
    private static int sniff(byte[] h) {
        if (h.length >= 4 && h[0] == 'P' && h[1] == 'K' &&
                (h[2] == 3 || h[2] == 5 || h[2] == 7) && (h[3] == 4 || h[3] == 6 || h[3] == 8)) {
            return FMT_ZIP;
        }
        if (h.length >= 4 && h[0] == 'R' && h[1] == 'a' && h[2] == 'r' && h[3] == '!') return FMT_RAR;
        if (h.length >= 6 && h[0] == 0x37 && h[1] == 0x7A && h[2] == 0xBC &&
                h[3] == 0xAF && h[4] == 0x27 && h[5] == 0x1C) return FMT_7Z;
        return FMT_OTHER;
    }

    // ---------------------------------------------------------------- 扫描

    /** 扫描结果。error 非 null 就是不能导，直接把它显示给用户。 */
    public static class Scan {
        /** 要导入的 entry 名，已过滤、已按自然序排好。 */
        public List<String> names = new ArrayList<>();
        /** 解压后总字节。扫描时一个个数出来的，不是估算。 */
        public long bytes;
        /** 跳过的非图片 entry 数（__MACOSX/、.DS_Store、说明.txt…）。 */
        public int skipped;
        public String error;
        /** 用户在扫描过程中按了停止。 */
        public boolean cancelled;
    }

    /** 扫描进度。files 是已认出的图片数。返回 false 表示用户中止了。 */
    public interface ScanProgress {
        boolean onScan(int files, long bytes);
    }

    /**
     * 走一遍压缩包，数出有哪些图、解压后一共多大。
     *
     * 这一遍的代价是把整个包解压一次（只解不落盘），换来的是导入前那句
     * 体积提示是**准的** —— 保留原片时它直接等于要占的磁盘。冲扫件是 JPEG，
     * 本身已经压过了，压缩包的 SIZE 字段完全不能用来估解压后的大小。
     */
    public static Scan scan(ContentResolver cr, Uri uri, ScanProgress p) {
        Scan s = new Scan();
        InputStream raw = null;
        try {
            raw = cr.openInputStream(uri);
            if (raw == null) {
                s.error = "打不开这个文件";
                return s;
            }
            BufferedInputStream bin = new BufferedInputStream(raw, 64 * 1024);
            bin.mark(16);
            byte[] head = new byte[8];
            int got = 0;
            while (got < head.length) {
                int k = bin.read(head, got, head.length - got);
                if (k <= 0) break;
                got += k;
            }
            bin.reset();

            int fmt = sniff(head);
            if (fmt == FMT_RAR) { s.error = "这是个 rar 压缩包，暂时只能导 zip"; return s; }
            if (fmt == FMT_7Z)  { s.error = "这是个 7z 压缩包，暂时只能导 zip"; return s; }
            if (fmt != FMT_ZIP) { s.error = "这不是一个 zip 压缩包"; return s; }

            ZipInputStream zin = new ZipInputStream(bin);
            byte[] buf = new byte[64 * 1024];
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String nm = e.getName();
                if (!isImageName(nm)) { s.skipped++; continue; }

                if (s.names.size() >= MAX_ENTRIES) {
                    s.error = "压缩包里的照片超过 " + MAX_ENTRIES + " 张，先分几个包再导";
                    return s;
                }

                // 读到底才知道真实大小 —— entry.getSize() 对流式压缩包经常是 -1
                long len = 0;
                int k;
                while ((k = zin.read(buf)) > 0) len += k;
                if (len <= 0) { s.skipped++; continue; }          // 空文件
                if (len > MAX_ENTRY_BYTES) {
                    s.error = baseName(nm) + " 单张超过 512MB，不像是扫描件";
                    return s;
                }
                s.names.add(nm);
                s.bytes += len;
                if (p != null && !p.onScan(s.names.size(), s.bytes)) {
                    s.cancelled = true;
                    return s;
                }
            }

            if (s.names.isEmpty()) {
                s.error = s.skipped > 0
                        ? "压缩包里没找到图片（跳过了 " + s.skipped + " 个文件）"
                        : "压缩包是空的";
                return s;
            }
            sortNatural(s.names);
            return s;

        } catch (ZipException ze) {
            // 加密的 entry 到这里就炸了：ZipInputStream 没有公开的加密标志位，
            // 硬要说「加密」是猜的，所以把损坏也一起提
            s.error = "压缩包读不出来 —— 可能加了密码，或者文件损坏了";
            return s;
        } catch (Exception ex) {
            s.error = "读压缩包失败：" + ex.getMessage();
            return s;
        } finally {
            closeQuietly(raw);
        }
    }

    // ---------------------------------------------------------------- 解出

    /** 一个 entry 已经落到临时文件上了。返回 false 表示调用方要中止。 */
    public interface Sink {
        boolean onEntry(File tmp, String baseName, int index, int total);
    }

    /**
     * 照着 scanned 的顺序把 entry 逐个解到 tmpDir，交给 sink。
     * 要再走一遍压缩包 —— ZipInputStream 是单向的，没法倒回去。
     */
    public static void extract(ContentResolver cr, Uri uri, List<String> wanted,
                               File tmpDir, Sink sink) throws IOException {
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < wanted.size(); i++) index.put(wanted.get(i), i);
        final int total = wanted.size();

        InputStream raw = null;
        try {
            raw = cr.openInputStream(uri);
            if (raw == null) throw new IOException("打不开压缩包");
            ZipInputStream zin = new ZipInputStream(new BufferedInputStream(raw, 64 * 1024));
            byte[] buf = new byte[64 * 1024];
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String nm = e.getName();
                Integer at = index.get(nm);
                if (at == null) continue;

                File tmp = new File(tmpDir, "zip_" + at + "_" + System.nanoTime() + ".part");
                try (OutputStream out = new FileOutputStream(tmp)) {
                    int k;
                    while ((k = zin.read(buf)) > 0) out.write(buf, 0, k);
                }
                if (!sink.onEntry(tmp, baseName(nm), at, total)) {
                    deleteQuietly(tmp);
                    return;
                }
            }
        } finally {
            closeQuietly(raw);
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 路径取末段。zip 里用 / 分隔，但偶尔见到反斜杠，一起认。 */
    static String baseName(String nm) {
        int a = nm.lastIndexOf('/');
        int b = nm.lastIndexOf('\\');
        int i = Math.max(a, b);
        return i < 0 ? nm : nm.substring(i + 1);
    }

    static boolean isImageName(String nm) {
        // Mac 打的包一定会带 __MACOSX/ 里的资源分支副本，全跳过
        if (nm.startsWith("__MACOSX/") || nm.startsWith("__MACOSX\\")) return false;
        String base = baseName(nm).toLowerCase(Locale.US);
        if (base.isEmpty() || base.charAt(0) == '.') return false;        // .DS_Store 等
        if (base.equals("thumbs.db") || base.equals("desktop.ini")) return false;
        int dot = base.lastIndexOf('.');
        if (dot < 0 || dot == base.length() - 1) return false;
        String ext = base.substring(dot + 1);
        for (String x : IMG_EXT) if (x.equals(ext)) return true;
        return false;
    }

    /**
     * 自然序：IMG_2 排在 IMG_10 前面。
     *
     * 直接用 String.compareTo 会得到 1, 10, 11, 2… —— 冲扫件的文件名基本
     * 就是纯数字序号，差一眼就能看出来，但很容易漏。逐段比：数字段按数值
     * （先去掉前导零），其余按不区分大小写的字典序。
     */
    static void sortNatural(List<String> list) {
        Collections.sort(list, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                int i = 0, j = 0, la = a.length(), lb = b.length();
                while (i < la && j < lb) {
                    char ca = a.charAt(i), cb = b.charAt(j);
                    if (isDigit(ca) && isDigit(cb)) {
                        int si = i, sj = j;
                        while (i < la && isDigit(a.charAt(i))) i++;
                        while (j < lb && isDigit(b.charAt(j))) j++;
                        String na = trimZeros(a.substring(si, i));
                        String nb = trimZeros(b.substring(sj, j));
                        if (na.length() != nb.length()) return na.length() - nb.length();
                        int c = na.compareTo(nb);
                        if (c != 0) return c;
                    } else {
                        char xa = Character.toLowerCase(ca), xb = Character.toLowerCase(cb);
                        if (xa != xb) return xa - xb;
                        i++; j++;
                    }
                }
                return (la - i) - (lb - j);
            }
        });
    }

    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }

    private static String trimZeros(String s) {
        int i = 0;
        while (i < s.length() - 1 && s.charAt(i) == '0') i++;
        return s.substring(i);
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) try { in.close(); } catch (IOException ignored) {}
    }

    private static void deleteQuietly(File f) {
        if (f != null) try { f.delete(); } catch (SecurityException ignored) {}
    }
}
