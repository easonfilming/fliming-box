package com.filmbox.archive;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 相册导入。
 *
 * 用 SAF（ACTION_OPEN_DOCUMENT）而不是 READ_MEDIA_IMAGES：SAF 在任何 API 级别
 * 都不需要运行时权限，而且给出的是稳定的 content:// URI。
 * 部分国产 ROM 精简了 DocumentsUI，所以 ActivityNotFoundException 要回退到
 * ACTION_GET_CONTENT。
 *
 * 不实现 onShowFileChooser —— 那会强制走 base64 往返，36 张图能把 JS 线程淹死。
 */
public class Importer {

    private static final String TAG = "FilmboxImport";
    public static final int REQ_PICK = 1001;

    /** 3 个并发：36 张同时解码必 OOM，再多线程收益也很小。 */
    private static final int THREADS = 3;

    public interface Callback {
        /** done 从 1 开始。失败时 r 为 null，用 failedName + error 描述。 */
        void onOne(int done, int total, PhotoStore.Result r, String failedName, String error);
        void onFinished(List<PhotoStore.Result> ok, List<String> failed, boolean cancelled);
    }

    private final Activity act;
    private final PhotoStore store;
    private final Callback cb;

    // 一次导入的请求参数，从 begin() 到 onActivityResult 之间存活
    private String rollId;
    private boolean keepOriginal;
    private int displayPx = 2048;
    private int thumbPx = 360;
    private int quality = 85;

    private volatile ExecutorService pool;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public Importer(Activity act, PhotoStore store, Callback cb) {
        this.act = act;
        this.store = store;
        this.cb = cb;
    }

    // ============================================================
    //  发起
    // ============================================================

    public void begin(String rollId, boolean keepOriginal, int displayPx, int thumbPx, int quality) {
        this.rollId = rollId;
        this.keepOriginal = keepOriginal;
        this.displayPx = displayPx > 0 ? displayPx : 2048;
        this.thumbPx = thumbPx > 0 ? thumbPx : 360;
        this.quality = quality > 0 ? quality : 85;

        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            act.startActivityForResult(i, REQ_PICK);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "没有 DocumentsUI，回退 ACTION_GET_CONTENT", e);
            Intent f = new Intent(Intent.ACTION_GET_CONTENT);
            f.addCategory(Intent.CATEGORY_OPENABLE);
            f.setType("image/*");
            f.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try {
                act.startActivityForResult(f, REQ_PICK);
            } catch (ActivityNotFoundException e2) {
                cb.onFinished(Collections.emptyList(),
                        Collections.singletonList("设备上没有可用的文件选择器"), false);
            }
        }
    }

    public void cancel() {
        cancelled.set(true);
    }

    // ============================================================
    //  结果处理
    // ============================================================

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_PICK) return;

        if (resultCode != Activity.RESULT_OK || data == null) {
            cb.onFinished(Collections.emptyList(), Collections.emptyList(), true);
            return;
        }

        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            int n = data.getClipData().getItemCount();
            for (int i = 0; i < n; i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }

        if (uris.isEmpty()) {
            cb.onFinished(Collections.emptyList(), Collections.emptyList(), true);
            return;
        }

        run(uris);
    }

    private void run(final List<Uri> uris) {
        final ContentResolver cr = act.getContentResolver();
        final int total = uris.size();
        final AtomicInteger done = new AtomicInteger(0);
        final List<PhotoStore.Result> ok = Collections.synchronizedList(new ArrayList<>());
        final List<String> failed = Collections.synchronizedList(new ArrayList<>());

        // 一次导入用一个池，跑完就关 —— 常驻池会在导入结束后白占着线程和内存
        final ExecutorService ex = Executors.newFixedThreadPool(Math.min(THREADS, total));
        pool = ex;

        final String base = "p_" + rollId + "_" + Long.toString(System.currentTimeMillis(), 36);

        for (int i = 0; i < total; i++) {
            final Uri uri = uris.get(i);
            final String pid = base + "_" + (i + 1);
            ex.execute(() -> {
                if (cancelled.get()) return;

                String name = null;
                try {
                    PhotoStore.Result r = store.importOne(
                            cr, uri, pid, rollId, keepOriginal, displayPx, thumbPx, quality);
                    ok.add(r);
                    cb.onOne(done.incrementAndGet(), total, r, null, null);
                } catch (Exception e) {
                    name = String.valueOf(uri.getLastPathSegment());
                    Log.w(TAG, "导入失败: " + name, e);
                    failed.add(name + "：" + e.getMessage());
                    cb.onOne(done.incrementAndGet(), total, null, name, String.valueOf(e.getMessage()));
                }
            });
        }

        ex.shutdown();
        // 收尾线程：等池跑完再回调，不阻塞调用方（onActivityResult 在主线程上）
        new Thread(() -> {
            try {
                ex.awaitTermination(10, TimeUnit.MINUTES);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            pool = null;
            cb.onFinished(new ArrayList<>(ok), new ArrayList<>(failed), cancelled.get());
        }, "import-finish").start();
    }

    // ============================================================
    //  体积预估 —— 导入前给用户看，避免 36 张扫描件悄悄吃掉 2.5GB
    // ============================================================

    public static long estimateBytes(ContentResolver cr, List<Uri> uris) {
        long sum = 0;
        for (Uri u : uris) {
            try (Cursor c = cr.query(u, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int i = c.getColumnIndex(OpenableColumns.SIZE);
                    if (i >= 0 && !c.isNull(i)) sum += c.getLong(i);
                }
            } catch (Exception ignored) {
                // 查不到就跳过，估算是尽力而为
            }
        }
        return sum;
    }

    /** 从最近一次选择里取出 URI 列表（给体积预估用）。 */
    public static List<Uri> urisOf(Intent data) {
        List<Uri> uris = new ArrayList<>();
        if (data == null) return uris;
        if (data.getClipData() != null) {
            int n = data.getClipData().getItemCount();
            for (int i = 0; i < n; i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        return uris;
    }
}
