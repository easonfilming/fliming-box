package com.filmbox.archive;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 照片文件的落地：解码 → 按 EXIF 旋正 → 缩放 → 编码。
 *
 * 每张图生成三份（原片可选）：
 *   photos/&lt;rollId&gt;/&lt;pid&gt;.jpg     展示副本，长边 ≤ displayPx
 *   thumbs/&lt;rollId&gt;/&lt;pid&gt;.jpg     缩略图，长边 ≤ thumbPx
 *   originals/&lt;rollId&gt;/&lt;pid&gt;.&lt;ext&gt;  仅 keepOriginal 时，源字节原样流拷贝
 *
 * 内存：解码时用 2 的幂 inSampleSize，Skia 在 libjpeg-turbo 里就降采样了，
 * 峰值约 11MB 而不是 90MB。36 张并发解码必 OOM，所以 Importer 那边限制 3 线程。
 */
public class PhotoStore {

    private static final String TAG = "FilmboxPhoto";

    public final File photos;
    public final File thumbs;
    public final File originals;
    public final File gear;
    public final File tmp;

    public PhotoStore(Context ctx) {
        File base = ctx.getFilesDir();
        photos = new File(base, "photos");
        thumbs = new File(base, "thumbs");
        originals = new File(base, "originals");
        gear = new File(base, "gear");
        tmp = new File(base, "tmp");
        photos.mkdirs();
        thumbs.mkdirs();
        originals.mkdirs();
        gear.mkdirs();
        tmp.mkdirs();
        // 压缩包导入中途被杀掉会留下 zip_*.part，启动时清一次。
        // 构造函数在 onStart 里跑，那时不可能有导入在进行，安全。
        File[] stale = tmp.listFiles((d, nm) -> nm.startsWith("zip_"));
        if (stale != null) for (File f : stale) f.delete();
    }

    public File photoFile(String rollId, String pid) {
        return new File(new File(photos, rollId), pid + ".jpg");
    }

    public File thumbFile(String rollId, String pid) {
        return new File(new File(thumbs, rollId), pid + ".jpg");
    }

    public File originalFile(String rollId, String pid, String ext) {
        return new File(new File(originals, rollId), pid + "." + ext);
    }

    /** 单张导入的产出。字段名要和 doc.json 里的照片对象对齐。 */
    public static class Result {
        public String id;
        public String name;
        public int w, h;
        public int thumbW, thumbH;
        public long bytes;
        public boolean original;
        /** 内容本来是竖构图、被转成横的存下来了（见 importOne 第 3.5 步）。
         *  存储的像素已经是横的，单看 w/h 分不出「本来就是横构图」和
         *  「竖构图转过来的」—— 边框要按内容方向排版，就得记这一笔。 */
        public boolean rotated;
        public long origBytes;
        public String origExt;
        public String takenAt;
    }

    /**
     * 取字节的来源。相册给的 content:// URI 和压缩包解出来的临时文件
     * 在这一点上不同，其余全部相同。
     */
    public interface Source {
        /** 每次调用都返回一个全新的、从头开始的流。 */
        InputStream open() throws IOException;
        /** 原始文件名。取后缀、写进 doc.json 都用它。 */
        String name();
        /** 把源内容落到 dest。源本来就是 dest（或能直接改名过去）时不白拷。 */
        void materialize(File dest) throws IOException;
    }

    /** 相册 / 文件选择器给的 URI。content 流不可回退，所以每次读都要重开。 */
    private static final class UriSource implements Source {
        private final ContentResolver cr;
        private final Uri uri;
        private final String name;
        UriSource(ContentResolver cr, Uri uri) {
            this.cr = cr;
            this.uri = uri;
            this.name = displayName(cr, uri);
        }
        @Override public InputStream open() throws IOException {
            InputStream in = cr.openInputStream(uri);
            if (in == null) throw new IOException("打不开输入流");
            return in;
        }
        @Override public String name() { return name; }
        @Override public void materialize(File dest) throws IOException { copyTo(this, dest); }
    }

    /**
     * 压缩包里的一个 entry —— 已经先落到 tmp/ 了。
     *
     * zip 的 entry 只能顺着读一次，而这条流水线要读它三遍（量尺寸、读 EXIF、
     * 解码）。所以先落成临时文件，之后按最普通的文件处理：内存恒定（64KB
     * 缓冲），也不用怀疑「压缩包那条路是不是解码得不一样」。
     */
    private static final class FileSource implements Source {
        private final File file;
        private final String name;
        FileSource(File file, String name) { this.file = file; this.name = name; }
        @Override public InputStream open() throws IOException {
            return new FileInputStream(file);
        }
        @Override public String name() { return name; }
        @Override public void materialize(File dest) throws IOException {
            // 源就是目标（keepOriginal 时 entry 直接落在 originals/）→ 什么都不用做
            if (file.getCanonicalPath().equals(dest.getCanonicalPath())) return;
            ensureParent(dest);
            // 同一个文件系统，改名是瞬时的，别白拷 80MB
            if (file.renameTo(dest)) return;
            copyTo(this, dest);
        }
    }

    private static void copyTo(Source src, File dest) throws IOException {
        ensureParent(dest);
        try (InputStream in = src.open(); OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            int k;
            while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
        }
    }

    /**
     * 导入一张。调用方保证在后台线程。
     *
     * @param keepOriginal 是否把源字节流拷贝到 originals/。
     *                     注意：展示副本和缩略图两种模式下都要生成 ——
     *                     这个开关只管原片，不管渲染。
     */
    public Result importOne(ContentResolver cr, Uri uri, String pid, String rollId,
                            boolean keepOriginal, int displayPx, int thumbPx, int quality)
            throws IOException {
        return importFrom(new UriSource(cr, uri), pid, rollId,
                keepOriginal, displayPx, thumbPx, quality);
    }

    /**
     * 导入压缩包里解出来的一张。
     *
     * @param file 已经落好的临时文件
     * @param name entry 的文件名（不含路径，只要后缀对就行）
     */
    public Result importOneFile(File file, String name, String pid, String rollId,
                                boolean keepOriginal, int displayPx, int thumbPx, int quality)
            throws IOException {
        return importFrom(new FileSource(file, name), pid, rollId,
                keepOriginal, displayPx, thumbPx, quality);
    }

    private Result importFrom(Source src, String pid, String rollId,
                              boolean keepOriginal, int displayPx, int thumbPx, int quality)
            throws IOException {

        Result r = new Result();
        r.id = pid;
        r.name = src.name();

        // ---- 1. 量尺寸，算采样率 ----
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = src.open()) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("不是可解码的图片");
        }

        int sample = 1;
        int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
        while (maxDim / (sample * 2) >= displayPx) sample *= 2;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;

        Bitmap bmp = null;
        Bitmap scaled = null;
        Bitmap thumb = null;
        try {
            try (InputStream in = src.open()) {
                bmp = BitmapFactory.decodeStream(in, null, opts);
            }
            if (bmp == null) throw new IOException("解码失败");

            // ---- 2. EXIF 旋正。必须重新开一个流 —— 流读过就回不去了 ----
            int orientation = ExifInterface.ORIENTATION_NORMAL;
            try (InputStream in = src.open()) {
                ExifInterface exif = new ExifInterface(in);
                orientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                r.takenAt = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
            } catch (IOException e) {
                // EXIF 读不到不影响导入，只是方向可能不对
                Log.w(TAG, "EXIF 读取失败，按未旋转处理", e);
            }
            bmp = applyOrientation(bmp, orientation);

            // ---- 3. 缩到目标尺寸（不放大）----
            scaled = fitWithin(bmp, displayPx);
            if (scaled != bmp) {
                bmp.recycle();
                bmp = null;
            }

            // ---- 3.5 竖构图转 90° 存成横向 ----
            // 135 的画幅永远是横的，竖着拍的照片在底片上本来就是躺着的。
            // 在这里转正，界面和导出就只需要最普通的 contain —— 填满画框、
            // 不裁切，而且不用在 CSS/canvas 里做任何旋转变换。
            if (scaled.getHeight() > scaled.getWidth()) {
                Matrix rm = new Matrix();
                rm.postRotate(90);
                Bitmap rotated = Bitmap.createBitmap(
                        scaled, 0, 0, scaled.getWidth(), scaled.getHeight(), rm, true);
                if (rotated != scaled) {
                    scaled.recycle();
                    scaled = rotated;
                    r.rotated = true;
                }
            }

            // ---- 4. 写展示副本 ----
            File pf = photoFile(rollId, pid);
            ensureParent(pf);
            writeJpeg(scaled, pf, quality);
            r.w = scaled.getWidth();
            r.h = scaled.getHeight();
            r.bytes = pf.length();

            // ---- 5. 缩略图从同一张 bitmap 再缩，比二次解码便宜 ----
            thumb = fitWithin(scaled, thumbPx);
            File tf = thumbFile(rollId, pid);
            ensureParent(tf);
            writeJpeg(thumb, tf, 80);
            r.thumbW = thumb.getWidth();
            r.thumbH = thumb.getHeight();

            // ---- 6. 原片：纯字节搬运，不解码不重编码。压缩包那条路上
            //          源就是临时文件，materialize 会直接改名过去
            if (keepOriginal) {
                String ext = extensionOf(r.name);
                File of = originalFile(rollId, pid, ext);
                src.materialize(of);
                r.original = true;
                r.origBytes = of.length();
                r.origExt = ext;
            }

            return r;

        } finally {
            if (thumb != null && thumb != scaled) thumb.recycle();
            if (scaled != null) scaled.recycle();
            if (bmp != null && bmp != scaled) bmp.recycle();
        }
    }

    // ------------------------------------------------------------------

    private static Bitmap applyOrientation(Bitmap src, int orientation) {
        Matrix m = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:  m.postRotate(90);  break;
            case ExifInterface.ORIENTATION_ROTATE_180: m.postRotate(180); break;
            case ExifInterface.ORIENTATION_ROTATE_270: m.postRotate(270); break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:   m.postScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE:  m.postRotate(90);  m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: m.postRotate(270); m.postScale(-1, 1); break;
            default: return src;   // NORMAL 或 UNDEFINED
        }
        Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        if (out != src) src.recycle();
        return out;
    }

    /** 等比缩到长边 ≤ max。已经够小就原样返回（不放大）。 */
    private static Bitmap fitWithin(Bitmap src, int max) {
        int w = src.getWidth(), h = src.getHeight();
        int longSide = Math.max(w, h);
        if (longSide <= max) return src;
        float k = (float) max / longSide;
        int nw = Math.max(1, Math.round(w * k));
        int nh = Math.max(1, Math.round(h * k));
        return Bitmap.createScaledBitmap(src, nw, nh, true);
    }

    private static void writeJpeg(Bitmap bmp, File f, int quality) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                throw new IOException("JPEG 编码失败: " + f.getName());
            }
        }
    }

    private static void ensureParent(File f) throws IOException {
        File p = f.getParentFile();
        if (p != null && !p.isDirectory() && !p.mkdirs()) {
            throw new IOException("建目录失败: " + p);
        }
    }

    private static String displayName(ContentResolver cr, Uri uri) {
        try (android.database.Cursor c = cr.query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    String n = c.getString(i);
                    if (n != null && !n.isEmpty()) return n;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "取文件名失败", e);
        }
        return "photo";
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "jpg";
        String e = name.substring(dot + 1).toLowerCase();
        // 只留安全的白名单，避免把奇怪的后缀写进文件名
        if (e.equals("jpg") || e.equals("jpeg") || e.equals("png")
                || e.equals("webp") || e.equals("heic") || e.equals("heif")
                || e.equals("tif") || e.equals("tiff") || e.equals("bmp")) {
            return e;
        }
        return "jpg";
    }
}
