package com.filmbox.archive;

import android.app.Activity;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 页面与原生之间的桥，注册成 window.AndroidBridge。
 *
 * 线程规则（决定了每个方法的签名）：@JavascriptInterface 方法跑在 WebView 的
 * JavaBridge 线程上。阻塞它不会卡 UI，但会**冻住所有 JS** —— 渲染、点击全部停摆。
 * 所以规则是：任何可能超过 16ms 的方法一律 void 立即返回，结果用 evaluateJavascript
 * 回调过去。导入 36 张要十几秒，绝不能同步。
 */
public class Bridge implements Importer.Callback, Capture.Callback {

    private static final String TAG = "FilmboxBridge";

    private final Activity act;
    private final WebView web;
    private final DocStore store;
    private final PhotoStore photos;

    /** 单线程就够：导出是低频操作，串行还能避免同时写多张大图。 */
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bridge-io");
        t.setDaemon(true);
        return t;
    });

    private Importer importer;
    private Capture capture;
    private volatile String pendingRollId = "";
    private volatile boolean dead = false;

    public Bridge(Activity act, WebView web, DocStore store, PhotoStore photos) {
        this.act = act;
        this.web = web;
        this.store = store;
        this.photos = photos;
    }

    /** 循环依赖：Importer 要 Bridge 当回调，Bridge 要 Importer 发起导入。 */
    public void attach(Importer i) {
        this.importer = i;
    }

    public void attach(Capture c) {
        this.capture = c;
    }

    /** Activity 销毁后所有回调都要停，否则会往一个已 destroy 的 WebView 上打。 */
    public void markDead() {
        dead = true;
    }

    /**
     * 回调进页面。
     * 永远用 JSONObject.quote() 包一层再让 JS 端 JSON.parse —— U+2028 / U+2029
     * 和 &lt;/script&gt; 都是合法 JSON 字符，字符串拼 JSON 会炸 eval。
     */
    private void call(String fn, String json) {
        if (dead) return;
        final String js = "window.__bridge&&window.__bridge." + fn + "(" + JSONObject.quote(json) + ")";
        web.post(() -> {
            if (dead) return;
            try {
                web.evaluateJavascript(js, null);
            } catch (Exception e) {
                Log.w(TAG, "evaluateJavascript 失败: " + fn, e);
            }
        });
    }

    private void fail(String fn, String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("message", message);
        } catch (JSONException ignored) {}
        call(fn, o.toString());
    }

    // ============================================================
    //  同步方法 —— 必须快（<16ms），直接返回值
    // ============================================================

    @JavascriptInterface
    public String appInfo() {
        JSONObject o = new JSONObject();
        try {
            o.put("mode", "native");
            o.put("versionName", BuildConfig.VERSION_NAME);
            o.put("versionCode", BuildConfig.VERSION_CODE);
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("abi", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown");
            o.put("freeBytes", new File(act.getFilesDir().getAbsolutePath()).getUsableSpace());
        } catch (JSONException e) {
            Log.w(TAG, "appInfo 组装失败", e);
        }
        return o.toString();
    }

    @JavascriptInterface
    public String docLoad() {
        String s = store.load();
        return s == null ? "null" : s;
    }

    @JavascriptInterface
    public void docSave(String json) {
        if (json == null || json.isEmpty()) return;
        store.save(json);
    }

    @JavascriptInterface
    public void setTheme(String theme) {
        final String t = "paper".equals(theme) ? "paper" : "darkroom";
        act.runOnUiThread(() -> MainActivity.applyThemeBars(act, t));
    }

    @JavascriptInterface
    public void log(String level, String msg) {
        String line = "[web] " + (msg == null ? "" : msg);
        if ("error".equals(level)) Log.e(TAG, line);
        else if ("warn".equals(level)) Log.w(TAG, line);
        else Log.i(TAG, line);
    }

    // ============================================================
    //  异步方法 —— 立即返回，结果走回调
    // ============================================================

    /** 打开系统文件选择器。选完才开始真正导入。 */
    @JavascriptInterface
    public void importPhotos(String reqJson) {
        if (importer == null) {
            fail("onImportError", "导入器未就绪");
            return;
        }
        try {
            JSONObject o = new JSONObject(reqJson);
            pendingRollId = o.optString("rollId", "");
            if (pendingRollId.isEmpty()) {
                fail("onImportError", "缺少 rollId");
                return;
            }
            importer.begin(
                    pendingRollId,
                    o.optBoolean("keepOriginal", false),
                    o.optInt("displayPx", 2048),
                    o.optInt("thumbPx", 360),
                    o.optInt("quality", 85));
        } catch (JSONException e) {
            fail("onImportError", "请求格式错误: " + e.getMessage());
        }
    }

    @JavascriptInterface
    public void cancelImport() {
        if (importer != null) importer.cancel();
    }

    /**
     * 把页面用 canvas 合成好的图写进系统相册。
     * 合成在 JS 侧做（canvas），原生只负责落盘 —— 这样排版逻辑只有一份。
     */
    @JavascriptInterface
    public void saveToAlbum(String reqJson) {
        final String dataUrl;
        final String name;
        try {
            JSONObject o = new JSONObject(reqJson);
            dataUrl = o.optString("dataUrl", "");
            name = Exporter.safeName(o.optString("name", ""),
                    "filmbox_" + System.currentTimeMillis());
        } catch (JSONException e) {
            fail("onExportError", "请求格式错误");
            return;
        }
        if (dataUrl.isEmpty()) {
            fail("onExportError", "没有图片数据");
            return;
        }

        // base64 解码 + 落盘可能上兆，绝不能占着 JavaBridge 线程
        io.execute(() -> {
            try {
                Uri uri = Exporter.saveDataUrl(act, dataUrl, name + ".jpg");
                JSONObject r = new JSONObject();
                r.put("uri", uri.toString());
                r.put("name", name);
                call("onExportDone", r.toString());
            } catch (Exception e) {
                Log.e(TAG, "写相册失败", e);
                fail("onExportError", String.valueOf(e.getMessage()));
            }
        });
    }

    /** 调系统相机拍一张，落到 tmp/，再回调给页面。 */
    @JavascriptInterface
    public void capturePhoto(String reqJson) {
        if (capture == null) {
            fail("onCaptureError", "相机未就绪");
            return;
        }
        String purpose = "";
        try {
            purpose = new JSONObject(reqJson).optString("purpose", "");
        } catch (JSONException ignored) {}
        final String p = purpose;
        act.runOnUiThread(() -> capture.begin(p));
    }

    // ============================================================
    //  MediaPipe 抠图 —— 会话式
    // ============================================================
    //  begin 打开一张图，之后每次 cutoutPoint 只跑推理，不重新解码也不重建
    //  segmenter。用户微调通常要点三五次，这个差别决定了它能不能用。
    //  segment() 同步且要 0.5–3 秒，全部放 io 线程。

    private final CutoutEngine cutout = new CutoutEngine();
    private volatile String cutoutOutId = "";

    @JavascriptInterface
    public void cutoutBegin(String reqJson) {
        final String src;
        final int px;
        try {
            JSONObject o = new JSONObject(reqJson);
            src = o.optString("source", "");
            px = o.optInt("workingPx", 1024);
            cutoutOutId = o.optString("outId", "gear_" + System.currentTimeMillis());
        } catch (JSONException e) {
            fail("onCutoutError", "请求格式错误");
            return;
        }
        if (src.isEmpty()) {
            fail("onCutoutError", "缺少源图路径");
            return;
        }

        final String outId = cutoutOutId;
        io.execute(() -> {
            try {
                cutout.begin(act, new File(src), px);
                JSONObject r = new JSONObject();
                r.put("outId", outId);
                r.put("w", cutout.width());
                r.put("h", cutout.height());
                call("onCutoutReady", r.toString());
            } catch (Exception e) {
                Log.e(TAG, "抠图会话开启失败", e);
                fail("onCutoutError", "开启失败：" + e.getMessage());
            }
        });
    }

    @JavascriptInterface
    public void cutoutPoint(String reqJson) {
        if (!cutout.isOpen()) {
            fail("onCutoutError", "会话未开始");
            return;
        }
        final float nx, ny;
        try {
            JSONObject o = new JSONObject(reqJson);
            nx = (float) o.optDouble("x", 0.5);
            ny = (float) o.optDouble("y", 0.5);
        } catch (JSONException e) {
            fail("onCutoutError", "请求格式错误");
            return;
        }

        final String outId = cutoutOutId;
        io.execute(() -> {
            try {
                Bitmap bmp = cutout.segmentAt(nx, ny);
                File out = new File(photos.gear, outId + ".png");
                CutoutEngine.writePng(bmp, out);
                bmp.recycle();

                JSONObject r = new JSONObject();
                r.put("outId", outId);
                // 加时间戳当缓存穿透：PathHandler 对 /gear/ 发的是 immutable，
                // 同一个 URL 会被 WebView 缓存住，换落点后看到的还是上一张
                r.put("url", "/gear/" + outId + ".png?t=" + System.currentTimeMillis());
                r.put("bytes", out.length());
                call("onCutoutResult", r.toString());
            } catch (Exception e) {
                Log.e(TAG, "抠图失败", e);
                fail("onCutoutError", "抠图失败：" + e.getMessage());
            }
        });
    }

    @JavascriptInterface
    public void cutoutEnd() {
        io.execute(cutout::end);
    }

    // ---- Capture.Callback（主线程回调）----

    /** 从相册选一张做设备照片（不走整卷导入流程）。 */
    @JavascriptInterface
    public void pickPhoto(String reqJson) {
        if (capture == null) {
            fail("onCaptureError", "相机未就绪");
            return;
        }
        String purpose = "";
        try {
            purpose = new JSONObject(reqJson).optString("purpose", "");
        } catch (JSONException ignored) {}
        final String p = purpose;
        act.runOnUiThread(() -> capture.pickSingle(p));
    }

    @Override
    public void onPicked(Uri uri, String purpose) {
        final Uri u = uri;
        final String p = purpose;
        // 拷贝可能上兆，放 io 线程；拷完复用 onCaptured 那条回调路径
        io.execute(() -> {
            try {
                File out = new File(photos.tmp, "picked_" + System.currentTimeMillis() + ".jpg");
                try (java.io.InputStream in = act.getContentResolver().openInputStream(u);
                     java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                    if (in == null) throw new java.io.IOException("打不开输入流");
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
                if (out.length() == 0) throw new java.io.IOException("选到的文件是空的");
                onCaptured(out, p);
            } catch (Exception e) {
                Log.e(TAG, "读取所选图片失败", e);
                fail("onCaptureError", "读取图片失败：" + e.getMessage());
            }
        });
    }
    @Override
    public void onCaptured(java.io.File file, String purpose) {
        JSONObject o = new JSONObject();
        try {
            o.put("purpose", purpose);
            o.put("path", file.getAbsolutePath());
            o.put("bytes", file.length());
        } catch (JSONException ignored) {}
        call("onCaptureDone", o.toString());
    }

    @Override
    public void onFailed(String message) {
        fail("onCaptureError", message);
    }

    @Override
    public void onCancelled(String purpose) {
        JSONObject o = new JSONObject();
        try {
            o.put("purpose", purpose);
            o.put("cancelled", true);
        } catch (JSONException ignored) {}
        call("onCaptureDone", o.toString());
    }

    /** 删掉一卷的照片文件。元数据的删除由 JS 侧负责，这里只管文件。 */
    @JavascriptInterface
    public void deletePhotos(String reqJson) {
        try {
            JSONObject o = new JSONObject(reqJson);
            String rollId = o.optString("rollId", "");
            JSONArray ids = o.optJSONArray("ids");
            int n = 0;
            if (ids != null) {
                for (int i = 0; i < ids.length(); i++) {
                    String pid = ids.getString(i);
                    if (deleteQuietly(photos.photoFile(rollId, pid))) n++;
                    if (deleteQuietly(photos.thumbFile(rollId, pid))) n++;
                    for (String ext : new String[]{"jpg", "jpeg", "png", "webp", "heic", "heif", "tif", "tiff", "bmp"}) {
                        deleteQuietly(photos.originalFile(rollId, pid, ext));
                    }
                }
            }
            JSONObject res = new JSONObject();
            res.put("deleted", n);
            call("onPhotosDeleted", res.toString());
        } catch (JSONException e) {
            fail("onPhotosDeleted", "请求格式错误: " + e.getMessage());
        }
    }

    private static boolean deleteQuietly(File f) {
        try {
            return f.isFile() && f.delete();
        } catch (SecurityException e) {
            return false;
        }
    }

    // ============================================================
    //  Importer.Callback —— 在后台线程上被调用，call() 内部会切回 UI 线程
    // ============================================================

    @Override
    public void onOne(int done, int total, PhotoStore.Result r, String failedName, String error) {
        JSONObject o = new JSONObject();
        try {
            o.put("rollId", pendingRollId);
            o.put("done", done);
            o.put("total", total);
            if (r != null) o.put("photo", photoJson(r));
            if (error != null) {
                o.put("failedName", failedName);
                o.put("error", error);
            }
        } catch (JSONException ignored) {}
        call("onImportProgress", o.toString());
    }

    @Override
    public void onFinished(List<PhotoStore.Result> ok, List<String> failed, boolean cancelled) {
        JSONObject o = new JSONObject();
        try {
            o.put("rollId", pendingRollId);
            JSONArray arr = new JSONArray();
            long bytes = 0;
            for (PhotoStore.Result r : ok) {
                arr.put(photoJson(r));
                bytes += r.bytes + r.origBytes;
            }
            o.put("photos", arr);
            o.put("totalBytes", bytes);
            o.put("cancelled", cancelled);
            JSONArray f = new JSONArray();
            for (String s : failed) f.put(s);
            o.put("failures", f);
        } catch (JSONException ignored) {}
        call("onImportDone", o.toString());
    }

    private static JSONObject photoJson(PhotoStore.Result r) throws JSONException {
        JSONObject p = new JSONObject();
        p.put("id", r.id);
        p.put("name", r.name);
        p.put("w", r.w);
        p.put("h", r.h);
        p.put("thumbW", r.thumbW);
        p.put("thumbH", r.thumbH);
        p.put("bytes", r.bytes);
        p.put("original", r.original);
        p.put("origBytes", r.origBytes);
        if (r.origExt != null) p.put("origExt", r.origExt);
        if (r.takenAt != null) p.put("takenAt", r.takenAt);
        return p;
    }
}
