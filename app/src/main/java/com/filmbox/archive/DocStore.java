package com.filmbox.archive;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * doc.json 的持久化：防抖 + 原子写。
 *
 * 写入序列 tmp → fsync → rename。ext4/f2fs 上同文件系统内 rename 是原子的，
 * 所以中途被杀只会留下「旧的」或「新的」，不会出现写了一半的文件。
 * rename 之前把现有 doc.json 挪成 .bak，多一层保险。
 */
public class DocStore {

    private static final String TAG = "FilmboxDoc";
    private static final long DEBOUNCE_MS = 500;

    private final File doc;
    private final File bak;
    private final File tmp;
    private final ScheduledExecutorService exec =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "docstore");
                t.setDaemon(true);
                return t;
            });

    private volatile String pending;
    private ScheduledFuture<?> job;

    public DocStore(Context ctx) {
        File dir = ctx.getFilesDir();
        doc = new File(dir, "doc.json");
        bak = new File(dir, "doc.json.bak");
        tmp = new File(dir, "doc.json.tmp");
    }

    public String load() {
        // 上次可能在 rename 两步之间被杀，留下 .bak 而没有 doc.json。
        // 不处理的话会读成「首次运行」，然后拿种子数据覆盖掉真实数据。
        if (!doc.exists() && bak.exists()) {
            Log.w(TAG, "doc.json 缺失但备份存在，从备份恢复");
            bak.renameTo(doc);
        }
        if (!doc.exists()) return null;

        try {
            return read(doc);
        } catch (IOException e) {
            Log.w(TAG, "doc.json 读取失败，退回备份", e);
            if (bak.exists()) {
                try {
                    return read(bak);
                } catch (IOException e2) {
                    Log.e(TAG, "备份也读不了", e2);
                }
            }
            return null;
        }
    }

    /** 桥收到新文档时调用。立即返回，实际落盘在 500ms 后。 */
    public synchronized void save(String json) {
        pending = json;
        if (job != null) job.cancel(false);
        job = exec.schedule(this::flush, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 立刻落盘。Activity.onPause 必须调 —— 否则编辑后 500ms 内切后台就丢。
     * 约 200KB 同步写，几毫秒，UI 线程上可以接受。
     */
    public synchronized void flush() {
        if (job != null) {
            job.cancel(false);
            job = null;
        }
        String json = pending;
        if (json == null) return;
        pending = null;
        writeAtomic(json);
    }

    private void writeAtomic(String json) {
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.flush();
            // 必须 fsync：否则 rename 成功后内容可能还只在页缓存里，断电就没了
            out.getFD().sync();
        } catch (IOException e) {
            Log.e(TAG, "写临时文件失败，本次改动未落盘", e);
            return;
        }

        if (doc.exists()) {
            if (bak.exists() && !bak.delete()) Log.w(TAG, "旧备份删不掉，忽略");
            if (!doc.renameTo(bak)) Log.w(TAG, "备份轮换失败，继续");
        }
        if (!tmp.renameTo(doc)) {
            Log.e(TAG, "rename 失败，本次改动未落盘");
        }
    }

    private static String read(File f) throws IOException {
        byte[] buf = new byte[(int) f.length()];
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            raf.readFully(buf);
        }
        return new String(buf, StandardCharsets.UTF_8);
    }

    public void shutdown() {
        flush();
        exec.shutdown();
    }
}
