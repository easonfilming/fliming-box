package com.filmbox.archive;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.ByteBufferExtractor;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.framework.image.MPImageProperties;
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenterResult;
import com.google.mediapipe.tasks.vision.interactivesegmenterlegacy.InteractiveSegmenterLegacy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.Optional;

/**
 * MediaPipe Interactive Segmenter —— 点一下主体，把它抠出来。
 *
 * 会话式设计：begin() 打开一张图，之后可以反复 segmentAt() 换落点，
 * 每次只付一次推理（约 0.5–3 秒），不重新解码也不重建 segmenter。
 * 用户微调抠图结果通常要点三五次，这个差别决定了它能不能用。
 *
 * segment() 是同步且耗时的，调用方必须放在后台线程 —— 它跑在
 * JavaBridge 线程上的话会把整个页面冻住。
 *
 * 为什么用 InteractiveSegmenterLegacy 而不是新的 InteractiveSegmenter：
 * 新的那个要求 .task 打包格式（内部要 interactive_segmentation_encoder.int8.tflite
 * 等一堆文件），而 magic_touch 只发布了裸 .tflite，官方模型库里没有对应的
 * .task 版本（两个可能的 URL 都是 404）。裸模型只能配 legacy API。
 * 传错会抛 MediaPipeException: Task bundle is missing ...
 */
public class CutoutEngine {

    private static final String TAG = "FilmboxCutout";
    public static final String MODEL_ASSET = "magic_touch.tflite";

    /**
     * 软阈值区间。硬切（>0.5）边缘全是锯齿，留一段斜坡做羽化。
     *
     * 下界不能太低：实测把 LO 放到 0.35 时，桌面背景的噪点会以半透明形式
     * 渗进来，在深色机身旁边形成一片脏点。0.5 起跳只保留有把握的像素，
     * 边缘仍然靠 0.5→0.8 的斜坡柔化。
     */
    private static final float LO = 0.50f;
    private static final float HI = 0.80f;

    private InteractiveSegmenterLegacy segmenter;
    private MPImage mpImage;
    private Bitmap source;      // 工作图（已缩到 workingPx），用于取 RGB
    private int w, h;
    private boolean busy;

    public boolean isOpen() {
        return segmenter != null && mpImage != null;
    }

    public int width() { return w; }
    public int height() { return h; }

    /** 打开一张图作为工作图。workingPx 是长边上限 —— 越大越精细也越慢。 */
    public synchronized void begin(Context ctx, File file, int workingPx) throws IOException {
        end();

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("不是可解码的图片");
        }

        int sample = 1;
        int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
        while (maxDim / (sample * 2) >= workingPx) sample *= 2;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;

        Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        if (decoded == null) throw new IOException("解码失败");

        // 再缩一次到精确的 workingPx（inSampleSize 只能按 2 的幂）
        source = fitWithin(decoded, workingPx);
        if (source != decoded) decoded.recycle();

        w = source.getWidth();
        h = source.getHeight();

        BaseOptions base = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .build();
        InteractiveSegmenterLegacy.InteractiveSegmenterLegacyOptions options =
                InteractiveSegmenterLegacy.InteractiveSegmenterLegacyOptions.builder()
                        .setBaseOptions(base)
                        .setOutputConfidenceMasks(true)   // 要浮点置信度，不要类别掩码
                        .build();

        segmenter = InteractiveSegmenterLegacy.createFromOptions(ctx, options);
        mpImage = new BitmapImageBuilder(source).build();

        Log.i(TAG, "会话开始 " + w + "×" + h + " (sample=" + sample + ")");
    }

    /**
     * 在归一化坐标 (nx, ny) 处点一下，返回带 alpha 的结果图。
     * 调用方保证在后台线程。
     */
    public synchronized Bitmap segmentAt(float nx, float ny) throws IOException {
        if (!isOpen()) throw new IOException("会话未开始");

        NormalizedKeypoint point = NormalizedKeypoint.create(clamp(nx), clamp(ny));
        InteractiveSegmenterLegacy.RegionOfInterest roi =
                InteractiveSegmenterLegacy.RegionOfInterest.create(point);

        ImageSegmenterResult result = segmenter.segment(mpImage, roi);
        if (result == null) throw new IOException("分割没有返回结果");

        Optional<List<MPImage>> masks = result.confidenceMasks();
        if (!masks.isPresent() || masks.get().isEmpty()) {
            throw new IOException("分割没有返回置信度掩码");
        }

        MPImage maskImage = masks.get().get(0);
        try {
            return applyMask(maskImage);
        } finally {
            maskImage.close();
        }
    }

    private Bitmap applyMask(MPImage maskImage) throws IOException {
        int format = MPImage.IMAGE_FORMAT_UNKNOWN;
        for (MPImageProperties p : maskImage.getContainedImageProperties()) {
            format = p.getImageFormat();
            break;
        }

        ByteBuffer buf = ByteBufferExtractor.extract(maskImage);
        if (buf == null) throw new IOException("拿不到 mask 缓冲");

        int n = w * h;
        float[] conf = new float[n];

        if (format == MPImage.IMAGE_FORMAT_VEC32F1) {
            buf.order(ByteOrder.nativeOrder());
            FloatBuffer fb = buf.asFloatBuffer();
            int m = Math.min(n, fb.remaining());
            fb.get(conf, 0, m);
        } else if (format == MPImage.IMAGE_FORMAT_ALPHA) {
            int m = Math.min(n, buf.remaining());
            for (int i = 0; i < m; i++) conf[i] = (buf.get(i) & 0xFF) / 255f;
        } else {
            // 未知格式不要静默返回一张全不透明的图 —— 那看起来像"抠图没生效"，
            // 排查起来毫无线索。直接报错，让上层把格式打出来。
            throw new IOException("不认识的 mask 格式: " + format
                    + "（w=" + maskImage.getWidth() + " h=" + maskImage.getHeight() + "）");
        }

        int[] px = new int[n];
        source.getPixels(px, 0, w, 0, 0, w, h);

        // 置信度分布。阈值定错的表现是"抠图几乎全透明"，光看结果猜不出来 ——
        // 必须看数：min/max/mean 和超过 0.5 的像素占比。
        float mn = Float.MAX_VALUE, mx = -Float.MAX_VALUE, sum = 0f;
        int above = 0;
        for (int i = 0; i < n; i++) {
            float v = conf[i];
            if (v < mn) mn = v;
            if (v > mx) mx = v;
            sum += v;
            if (v > 0.5f) above++;
        }
        Log.i(TAG, String.format(
                "mask 统计: min=%.3f max=%.3f mean=%.3f >0.5=%.2f%% 阈值=[%.2f,%.2f] 格式=%d n=%d",
                mn, mx, sum / n, 100f * above / n, LO, HI, format, n));

        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            float a = (conf[i] - LO) / (HI - LO);
            if (a < 0f) a = 0f; else if (a > 1f) a = 1f;
            out[i] = (Math.round(a * 255f) << 24) | (px[i] & 0x00FFFFFF);
        }

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.setPixels(out, 0, w, 0, 0, w, h);
        return bmp;
    }

    /** 把抠好的图写成 PNG（保留 alpha）。 */
    public static void writePng(Bitmap bmp, File out) throws IOException {
        File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("建目录失败: " + parent);
        }
        try (FileOutputStream fos = new FileOutputStream(out)) {
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                throw new IOException("PNG 编码失败");
            }
        }
    }

    public synchronized void end() {
        if (mpImage != null) {
            try { mpImage.close(); } catch (Exception ignored) {}
            mpImage = null;
        }
        if (segmenter != null) {
            try { segmenter.close(); } catch (Exception ignored) {}
            segmenter = null;
        }
        if (source != null) {
            source.recycle();
            source = null;
        }
        w = h = 0;
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static Bitmap fitWithin(Bitmap src, int max) {
        int sw = src.getWidth(), sh = src.getHeight();
        int longSide = Math.max(sw, sh);
        if (longSide <= max) return src;
        float k = (float) max / longSide;
        return Bitmap.createScaledBitmap(src,
                Math.max(1, Math.round(sw * k)), Math.max(1, Math.round(sh * k)), true);
    }
}
