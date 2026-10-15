package com.filmbox.archive;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * 从 zip 压缩包里导入照片。
 *
 * 为什么**先整个拷成临时文件、再用 ZipFile**，而不是直接对 content:// 用
 * ZipInputStream：
 *
 *   1. ZipInputStream 读不了「stored + 数据描述符」的条目 —— 不压缩的条目
 *      尺寸未知时它划不出边界，直接抛
 *      ZipException: only DEFLATED entries can have EXT descriptor。
 *      而冲扫店发出来的包恰恰常常长这样（ZIP64 + stored + 描述符，一条不落）。
 *   2. ZipFile 读的是中央目录，条目名和尺寸都是现成的：几十 GB 的包枚举
 *      只要十几毫秒，不用解压一遍。ZipInputStream 要数体积只能整包解一遍。
 *   3. ZipFile 支持 ZIP64，ZipInputStream 支持得残缺。
 *   4. 条目可以按任意顺序取，不用顺着走第二遍。
 *
 * 代价是临时占一份包大小的磁盘（拷完就删，见 PhotoStore 启动时的清理）。
 * content:// 给不了可 seek 的文件，这一步躲不掉。
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
            "jpg", "jpeg", "jpe", "jfif", "png", "webp", "heic", "heif", "tif", "tiff", "bmp"
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

    private static String hex(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", b[i]));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 拷贝

    /** 拷贝进度。total <= 0 表示拿不到源文件大小。 */
    public interface StageProgress {
        /** 返回 false 表示用户中止了。 */
        boolean onStage(long copied, long total);
    }

    /** 源文件在 content:// 那边报的大小，拿不到返回 -1。 */
    public static long sourceSize(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.SIZE);
                if (i >= 0 && !c.isNull(i)) return c.getLong(i);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /**
     * 把 content:// 拷成一个真文件。ZipFile 要能 seek，而 content URI 给的
     * 是不可回退的流 —— 这一步躲不掉。
     *
     * @return 临时文件；中途被取消返回 null
     */
    public static File stage(ContentResolver cr, Uri uri, File tmpDir, StageProgress p)
            throws IOException {
        long total = sourceSize(cr, uri);
        // 空间不够就别开始 —— 拷到一半失败，报错还不如不开始清楚
        long free = tmpDir.getUsableSpace();
        if (total > 0 && free > 0 && total + 16L * 1024 * 1024 > free) {
            throw new IOException("剩余空间不够放下这个压缩包（要 " + (total >> 20)
                    + " MB，只剩 " + (free >> 20) + " MB）");
        }

        File dst = new File(tmpDir, "zip_" + System.currentTimeMillis() + ".archive");
        InputStream in = null;
        OutputStream out = null;
        try {
            in = cr.openInputStream(uri);
            if (in == null) throw new IOException("打不开这个文件");
            out = new BufferedOutputStream(new FileOutputStream(dst), 64 * 1024);
            byte[] buf = new byte[128 * 1024];
            long done = 0;
            int k;
            while ((k = in.read(buf)) > 0) {
                out.write(buf, 0, k);
                done += k;
                if (p != null && !p.onStage(done, total)) {
                    closeQuietly(out);
                    out = null;
                    deleteQuietly(dst);
                    return null;
                }
            }
            closeQuietly(out);
            out = null;
            return dst;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    // ---------------------------------------------------------------- 扫描

    /** 扫描结果。error 非 null 就是不能导，直接把它显示给用户。 */
    public static class Scan {
        /** 要导入的 entry 名，已过滤、已按自然序排好。 */
        public List<String> names = new ArrayList<>();
        /** 解压后总字节。中央目录里现成的，不是估算。 */
        public long bytes;
        /** 跳过的非图片 entry 数（__MACOSX/、.DS_Store、说明.txt…）。 */
        public int skipped;
        /** 压缩包里前几个 entry 的名字。一个图片都没认出来时用来说清楚里面是什么。 */
        public List<String> seen = new ArrayList<>();
        public String error;
        /** 用户在拷贝/扫描过程中按了停止。 */
        public boolean cancelled;
    }

    /**
     * 读中央目录。几万个条目也只要几十毫秒 —— 尺寸是现成的，不用解压。
     */
    public static Scan scan(File archive) {
        Scan s = new Scan();

        int fmt = FMT_OTHER;
        try (InputStream in = new FileInputStream(archive)) {
            byte[] head = new byte[8];
            int got = 0;
            while (got < head.length) {
                int k = in.read(head, got, head.length - got);
                if (k <= 0) break;
                got += k;
            }
            fmt = sniff(head);
            if (fmt == FMT_RAR) { s.error = "这是个 rar 压缩包，暂时只能导 zip"; return s; }
            if (fmt == FMT_7Z)  { s.error = "这是个 7z 压缩包，暂时只能导 zip"; return s; }
            if (fmt != FMT_ZIP) {
                s.error = "这不是一个 zip 压缩包（文件开头是 " + hex(head, got) + "）";
                return s;
            }
        } catch (IOException e) {
            s.error = "读不了这个文件：" + e.getMessage();
            return s;
        }

        try (ZipFile zf = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String nm = e.getName();
                if (!isImageName(nm)) {
                    s.skipped++;
                    if (s.seen.size() < 6) s.seen.add(baseName(nm));
                    continue;
                }
                if (s.names.size() >= MAX_ENTRIES) {
                    s.error = "压缩包里的照片超过 " + MAX_ENTRIES + " 张，先分几个包再导";
                    return s;
                }
                long len = e.getSize();
                if (len <= 0) { s.skipped++; continue; }              // 空文件
                if (len > MAX_ENTRY_BYTES) {
                    s.error = baseName(nm) + " 单张超过 512MB，不像是扫描件";
                    return s;
                }
                s.names.add(nm);
                s.bytes += len;
            }
        } catch (ZipException ze) {
            // 到这儿说明文件头是 PK 但中央目录读不出来：加密、损坏、或者分卷
            s.error = "压缩包读不出来 —— 可能加了密码、是分卷包，或者文件损坏了";
            return s;
        } catch (Exception e) {
            s.error = "读压缩包失败：" + e.getMessage();
            return s;
        }

        if (s.names.isEmpty()) {
            if (s.skipped > 0) {
                StringBuilder b = new StringBuilder("压缩包里没找到图片，里面是：");
                for (int i = 0; i < s.seen.size(); i++) {
                    if (i > 0) b.append('、');
                    b.append(s.seen.get(i));
                }
                if (s.skipped > s.seen.size()) b.append(" 等 ").append(s.skipped).append(" 个文件");
                s.error = b.toString();
            } else {
                s.error = "压缩包是空的";
            }
            return s;
        }
        sortNatural(s.names);
        return s;
    }

    // ---------------------------------------------------------------- 解出

    /** 一个 entry 已经落到临时文件上了。返回 false 表示调用方要中止。 */
    public interface Sink {
        boolean onEntry(File tmp, String baseName, int index, int total);
    }

    /**
     * 照着 wanted 的顺序逐个取出来 —— 这次是**按 wanted 走**，不是顺着
     * 压缩包走。ZipFile 能随机访问，所以拿到的顺序天然就是排好的顺序。
     */
    public static void extract(File archive, List<String> wanted, File tmpDir, Sink sink)
            throws IOException {
        final int total = wanted.size();
        try (ZipFile zf = new ZipFile(archive)) {
            for (int i = 0; i < total; i++) {
                String nm = wanted.get(i);
                ZipEntry e = zf.getEntry(nm);
                if (e == null) throw new IOException("压缩包里找不到 " + baseName(nm));

                File tmp = new File(tmpDir, "zip_" + i + "_" + System.nanoTime() + ".part");
                try (InputStream in = zf.getInputStream(e);
                     OutputStream out = new BufferedOutputStream(
                             new FileOutputStream(tmp), 64 * 1024)) {
                    byte[] buf = new byte[128 * 1024];
                    int k;
                    while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
                }
                if (!sink.onEntry(tmp, baseName(nm), i, total)) {
                    deleteQuietly(tmp);
                    return;
                }
            }
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
        // 后缀上可能粘着空格或换行（有些工具会写脏），去掉再比
        ext = ext.trim();
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

    static void closeQuietly(java.io.Closeable c) {
        if (c != null) try { c.close(); } catch (IOException ignored) {}
    }

    static void deleteQuietly(File f) {
        if (f != null) try { f.delete(); } catch (SecurityException ignored) {}
    }
}
