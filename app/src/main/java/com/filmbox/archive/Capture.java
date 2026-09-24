package com.filmbox.archive;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;

/**
 * 调系统相机拍一张，落到 App 私有 tmp 目录。
 *
 * 不声明 CAMERA 权限 —— 一旦在清单里声明，ACTION_IMAGE_CAPTURE 就要求 App
 * 自己持有该权限，反而多出一道授权。不声明时相机应用自己负责权限，我们只是
 * 委托方。
 *
 * 输出走 FileProvider：直接传 file:// URI 给相机应用在 API 24+ 会抛
 * FileUriExposedException。
 */
public class Capture {

    private static final String TAG = "FilmboxCapture";
    public static final int REQ_CAPTURE = 1002;
    public static final int REQ_PICK_SINGLE = 1003;

    public interface Callback {
        void onCaptured(File file, String purpose);
        /** 从相册选了一张，URI 还没拷成文件（拷贝要后台线程，交给调用方）。 */
        void onPicked(android.net.Uri uri, String purpose);
        void onFailed(String message);
        void onCancelled(String purpose);
    }

    private final Activity act;
    private final File tmpDir;
    private final Callback cb;

    private File outFile;
    private String purpose = "";

    public Capture(Activity act, File tmpDir, Callback cb) {
        this.act = act;
        this.tmpDir = tmpDir;
        this.cb = cb;
    }

    public void begin(String purpose) {
        this.purpose = purpose == null ? "" : purpose;
        if (!tmpDir.isDirectory() && !tmpDir.mkdirs()) {
            cb.onFailed("建不了临时目录");
            return;
        }

        outFile = new File(tmpDir, "capture_" + System.currentTimeMillis() + ".jpg");

        Uri uri;
        try {
            uri = FileProvider.getUriForFile(
                    act, act.getPackageName() + ".fileprovider", outFile);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "FileProvider 配置不对", e);
            cb.onFailed("FileProvider 配置错误：" + e.getMessage());
            return;
        }

        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        i.putExtra(MediaStore.EXTRA_OUTPUT, uri);
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            act.startActivityForResult(i, REQ_CAPTURE);
        } catch (ActivityNotFoundException e) {
            cb.onFailed("设备上没有相机应用");
        }
    }

    /** 从相册选一张（不是拍照）。走 SAF，不需要任何权限。 */
    public void pickSingle(String purpose) {
        this.purpose = purpose == null ? "" : purpose;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        try {
            act.startActivityForResult(i, REQ_PICK_SINGLE);
        } catch (ActivityNotFoundException e) {
            cb.onFailed("设备上没有可用的文件选择器");
        }
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_PICK_SINGLE) {
            if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
                cb.onCancelled(purpose);
                return;
            }
            cb.onPicked(data.getData(), purpose);
            return;
        }

        if (requestCode != REQ_CAPTURE) return;

        if (resultCode != Activity.RESULT_OK) {
            if (outFile != null && outFile.isFile()) outFile.delete();
            cb.onCancelled(purpose);
            return;
        }
        if (outFile == null || !outFile.isFile() || outFile.length() == 0) {
            cb.onFailed("相机没有写出照片");
            return;
        }
        cb.onCaptured(outFile, purpose);
    }
}
