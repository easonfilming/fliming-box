package com.filmbox.archive;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.os.Build;
import android.provider.MediaStore;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;

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
    /** 压缩包走的是文件选择器，跟相册那条路不是一个 Intent。 */
    public static final int REQ_PICK_ZIP = 1004;

    /** 3 个并发：36 张同时解码必 OOM，再多线程收益也很小。 */
    private static final int THREADS = 3;

    public interface Callback {
        /** done 从 1 开始。失败时 r 为 null，用 failedName + error 描述。 */
        void onOne(int done, int total, PhotoStore.Result r, String failedName, String error);
        void onFinished(List<PhotoStore.Result> ok, List<String> failed, boolean cancelled);
        /**
         * 压缩包阶段进度。
         * phase = "prepare" 时 done/total 是已拷贝字节数；
         * phase = "scan" 时 done 是已认出的图片数、total 是 -1（未知）。
         */
        void onZipProgress(String phase, long done, long total);
        /** 扫描完了，把结果交给界面确认。error 非 null 就是不能导。 */
        void onZipScanned(ZipImporter.Scan s);
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

    // 压缩包：选区之后要停在「等用户确认」这一步，所以拷好的临时文件、
    // 原始 URI 和排好序的 entry 名单都得留着，等 startZip() 用
    private Uri pendingZipUri;
    private File pendingZipFile;
    private List<String> pendingZipNames;

    public Importer(Activity act, PhotoStore store, Callback cb) {
        this.act = act;
        this.store = store;
        this.cb = cb;
    }

    // ============================================================
    //  发起
    // ============================================================

    public void begin(String rollId, boolean keepOriginal, int displayPx, int thumbPx, int quality) {
        // 复位取消标志。以前不复位：只要「停止导入」按过一次，之后每次导入
        // 都在第一个任务开头就撞上 cancelled=true 直接返回 —— 表现是
        // 「点了导入，转了半秒，说没有导入任何照片」，而且完全没有报错。
        cancelled.set(false);
        this.rollId = rollId;
        this.keepOriginal = keepOriginal;
        this.displayPx = displayPx > 0 ? displayPx : 2048;
        this.thumbPx = thumbPx > 0 ? thumbPx : 360;
        this.quality = quality > 0 ? quality : 85;

        // 优先用系统的照片选择器（Android 13+）：相册式网格、支持多选，
        // 而且不需要任何权限。ACTION_OPEN_DOCUMENT 打开的是 DocumentsUI ——
        // 一个文件管理器，用来「从相册选照片」体验很差。
        Intent i = photoPicker();
        if (i == null) i = documentPicker();

        try {
            act.startActivityForResult(i, REQ_PICK);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "首选选择器不可用，回退 ACTION_GET_CONTENT", e);
            Intent f = new Intent(Intent.ACTION_GET_CONTENT);
            f.addCategory(Intent.CATEGORY_OPENABLE);
            f.setType("image/*");
            f.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try {
                act.startActivityForResult(f, REQ_PICK);
            } catch (ActivityNotFoundException e2) {
                cb.onFinished(Collections.emptyList(),
                        Collections.singletonList("设备上没有可用的照片选择器"), false);
            }
        }
    }

    /** 系统照片选择器。Android 13 以下返回 null。 */
    private Intent photoPicker() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null;
        Intent i = new Intent(MediaStore.ACTION_PICK_IMAGES);
        i.setType("image/*");
        // 上限必须 ≤ MediaStore.getPickImagesMaxLimit()。写死一个偏大的数字
        // （比如 500）会让选择器自己抛 IllegalArgumentException 然后立刻关掉 ——
        // 表现是「点了没反应」，很容易误判成 Intent 根本没发出去。
        try {
            i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit());
        } catch (Throwable t) {
            Log.w(TAG, "拿不到照片选择器上限，退回单选", t);
        }
        if (i.resolveActivity(act.getPackageManager()) == null) return null;
        return i;
    }

    /** 回退方案：SAF 文件选择器。任何 API 级别都不需要运行时权限。 */
    private Intent documentPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return i;
    }

    /**
     * 选一个 zip 压缩包。选完先扫描一遍（数张数、量解压后体积），
     * 结果回给界面确认了再真导 —— 冲扫包动辄上 GB，不能选完就闷头干。
     */
    public void beginZip() {
        Intent i = zipPicker();
        try {
            act.startActivityForResult(i, REQ_PICK_ZIP);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "文件选择器不可用，回退 ACTION_GET_CONTENT", e);
            Intent f = new Intent(Intent.ACTION_GET_CONTENT);
            f.addCategory(Intent.CATEGORY_OPENABLE);
            f.setType("*/*");
            try {
                act.startActivityForResult(f, REQ_PICK_ZIP);
            } catch (ActivityNotFoundException e2) {
                cb.onZipScanned(zipPickFail("设备上没有可用的文件选择器"));
            }
        }
    }

    private static ZipImporter.Scan cancelledScan() {
        ZipImporter.Scan s = new ZipImporter.Scan();
        s.cancelled = true;
        return s;
    }

    private static ZipImporter.Scan zipPickFail(String msg) {
        ZipImporter.Scan s = new ZipImporter.Scan();
        s.error = msg;
        return s;
    }

    /**
     * 文件选择器。这里**不能**用 photoPicker —— 那是照片网格，看不见 zip。
     *
     * type 必须是 *&#47;*：网盘下来的 zip 十有八九被标成
     * application/octet-stream，写死 application/zip 的话它在选择器里直接
     * 变灰不可选，用户只会觉得「点了没反应」。
     */
    private Intent zipPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        // 把 rar / 7z 也列进来，让它们**能选中** —— 选不中用户只会觉得
        // 「我那个包怎么不见了」，选中了才能告诉他「这是 rar，暂时只能导 zip」
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip", "application/x-zip-compressed",
                "application/x-zip", "multipart/x-zip",
                "application/vnd.rar", "application/x-rar-compressed",
                "application/x-7z-compressed",
                "application/octet-stream"});
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return i;
    }

    /** 用户在确认弹窗里点了「开始导入」。 */
    public void startZip(String rollId, boolean keepOriginal,
                         int displayPx, int thumbPx, int quality) {
        if (pendingZipFile == null || pendingZipNames == null || pendingZipNames.isEmpty()) {
            cb.onFinished(Collections.emptyList(),
                    Collections.singletonList("压缩包已经不在了，重新选一次"), false);
            return;
        }
        this.rollId = rollId;
        this.keepOriginal = keepOriginal;
        this.displayPx = displayPx > 0 ? displayPx : 2048;
        this.thumbPx = thumbPx > 0 ? thumbPx : 360;
        this.quality = quality > 0 ? quality : 85;
        cancelled.set(false);
        runZip(pendingZipNames);
    }

    public void cancel() {
        cancelled.set(true);
    }

    // ============================================================
    //  结果处理
    // ============================================================

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_PICK_ZIP) {
            onZipPicked(resultCode, data);
            return;
        }
        if (requestCode != REQ_PICK) return;

        if (resultCode != Activity.RESULT_OK || data == null) {
            cb.onFinished(Collections.emptyList(), Collections.emptyList(), true);
            return;
        }

        // 记一笔选择器返回了什么。不同选择器（系统照片选择器 / SAF / 厂商定制）
        // 把结果放在哪儿不完全一致，万一「选了照片没反应」，这条日志能直接定位。
        Log.i(TAG, "选择器返回: resultCode=" + resultCode
                + " clipCount=" + (data.getClipData() != null ? data.getClipData().getItemCount() : 0)
                + " data=" + data.getData());

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

    /** 选完压缩包：扫描不能占着主线程 —— onActivityResult 就在主线程上。 */
    private void onZipPicked(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            cb.onFinished(Collections.emptyList(), Collections.emptyList(), true);
            return;
        }
        final Uri uri = data.getData();
        pendingZipUri = uri;
        pendingZipFile = null;
        pendingZipNames = null;

        new Thread(() -> {
            try {
                // 先整个拷成真文件 —— ZipFile 要能 seek，content URI 给不了。
                // 这一步同时也就把「源文件到底完整不完整」验了一遍。
                final long[] last = {0L};
                File archive = ZipImporter.stage(act.getContentResolver(), uri, store.tmp,
                        (copied, total) -> {
                            if (cancelled.get()) return false;
                            long now = System.currentTimeMillis();
                            if (now - last[0] < 120 && (total <= 0 || copied < total)) return true;
                            last[0] = now;
                            cb.onZipProgress("prepare", copied, total);
                            return true;
                        });
                if (archive == null) {                 // 用户中止了拷贝
                    cb.onZipScanned(cancelledScan());
                    return;
                }
                pendingZipFile = archive;
                cb.onZipProgress("scan", 0, -1);       // 读中央目录，基本是瞬间的
                ZipImporter.Scan s = ZipImporter.scan(archive);
                if (s.error != null) {
                    // 这个包没用了，别占着几十上百 MB 的临时空间
                    ZipImporter.deleteQuietly(archive);
                    pendingZipFile = null;
                } else {
                    pendingZipNames = s.names;
                }
                cb.onZipScanned(s);
            } catch (Exception e) {
                Log.w(TAG, "准备压缩包失败", e);
                ZipImporter.Scan s = new ZipImporter.Scan();
                s.error = String.valueOf(e.getMessage());
                cb.onZipScanned(s);
            }
        }, "zip-prepare").start();
    }

    /**
     * 解压 + 逐张导入。
     *
     * 这一条路是**串行**的：ZipInputStream 只能顺着读，解出第 N+1 个 entry
     * 之前必须先读完第 N 个。要并行得加一条「读的人 + 解码池 + 有界队列」的
     * 流水线，临时文件占磁盘不占内存，所以代价不高，但取消和异常传播都会
     * 复杂一圈。第一版先串行 —— 进度条在走，几十秒是能接受的。
     */
    private void runZip(final List<String> names) {
        final File archive = pendingZipFile;
        final int total = names.size();
        final AtomicInteger done = new AtomicInteger(0);
        final List<String> failed = new ArrayList<>();
        // 按 names 里的下标预分配槽位。extract() 是顺着压缩包走的 ——
        // 归档顺序和排好的顺序通常不一样，直接 append 会让底片条按
        // 「压缩包里的存放顺序」排，前面的自然排序就白做了。
        final PhotoStore.Result[] slots = new PhotoStore.Result[total];
        final String base = "p_" + rollId + "_" + Long.toString(System.currentTimeMillis(), 36);

        new Thread(() -> {
            try {
                // 按 names 的顺序逐个取 —— ZipFile 能随机访问，不用顺着压缩包走
                ZipImporter.extract(archive, names, store.tmp, (tmp, nm, at, tot) -> {
                    if (cancelled.get()) return false;
                    String pid = base + "_" + (at + 1);
                    try {
                        PhotoStore.Result r = store.importOneFile(
                                tmp, nm, pid, rollId, keepOriginal, displayPx, thumbPx, quality);
                        slots[at] = r;
                        cb.onOne(done.incrementAndGet(), total, r, null, null);
                    } catch (Exception e) {
                        Log.w(TAG, "压缩包导入失败: " + nm, e);
                        failed.add(nm + "：" + e.getMessage());
                        cb.onOne(done.incrementAndGet(), total, null, nm, String.valueOf(e.getMessage()));
                    } finally {
                        // keepOriginal 成功时 importOneFile 已经把临时文件改名进
                        // originals/ 了，这次 delete 是无害的空操作；失败和取消
                        // 的情况才真删
                        if (tmp != null) tmp.delete();
                    }
                    return true;
                });
            } catch (Exception e) {
                Log.w(TAG, "解压中断", e);
                failed.add("解压中断：" + e.getMessage());
            } finally {
                // 拷出来的那份包用完了就删，别占着几十上百 MB
                ZipImporter.deleteQuietly(archive);
                pendingZipFile = null;
                pendingZipNames = null;
            }
            // 按 names 的顺序交出去
            List<PhotoStore.Result> ordered = new ArrayList<>();
            for (PhotoStore.Result r : slots) {
                if (r != null) ordered.add(r);
            }
            cb.onFinished(ordered, failed, cancelled.get());
        }, "zip-import").start();
    }

    private void run(final List<Uri> uris) {
        final ContentResolver cr = act.getContentResolver();
        final int total = uris.size();
        final AtomicInteger done = new AtomicInteger(0);
        final List<PhotoStore.Result> ok = Collections.synchronizedList(new ArrayList<>());
        final List<String> failed = Collections.synchronizedList(new ArrayList<>());
        // 按【输入下标】预分配槽位。3 个线程并行解码，完成顺序是乱的 ——
        // 直接往 ok 里加会让导入的照片顺序随机，不是用户选的顺序。
        final PhotoStore.Result[] slots = new PhotoStore.Result[total];

        // 一次导入用一个池，跑完就关 —— 常驻池会在导入结束后白占着线程和内存
        final ExecutorService ex = Executors.newFixedThreadPool(Math.min(THREADS, total));
        pool = ex;

        final String base = "p_" + rollId + "_" + Long.toString(System.currentTimeMillis(), 36);

        for (int i = 0; i < total; i++) {
            final int idx = i;                    // lambda 只能捕获 effectively final 的变量
            final Uri uri = uris.get(i);
            final String pid = base + "_" + (i + 1);
            ex.execute(() -> {
                if (cancelled.get()) return;

                String name = null;
                try {
                    PhotoStore.Result r = store.importOne(
                            cr, uri, pid, rollId, keepOriginal, displayPx, thumbPx, quality);
                    slots[idx] = r;
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
            // 按选择顺序交出去，不是完成顺序
            List<PhotoStore.Result> ordered = new ArrayList<>();
            for (PhotoStore.Result r : slots) {
                if (r != null) ordered.add(r);
            }
            cb.onFinished(ordered, new ArrayList<>(failed), cancelled.get());
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
