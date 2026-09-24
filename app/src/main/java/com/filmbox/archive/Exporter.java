package com.filmbox.archive;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;

import java.io.IOException;
import java.io.OutputStream;

/**
 * 把图写进系统相册。
 *
 * minSdk 29 起 MediaStore 支持 RELATIVE_PATH + IS_PENDING，写 Pictures/ 不需要
 * 任何权限。IS_PENDING 是必须的：先标记为"写入中"再落盘，否则相册应用可能在
 * 字节还没写完时就来读，显示一张残缺的图。
 */
public class Exporter {

    public static final String ALBUM = "菲林档案";

    /** 从 data URL（"data:image/jpeg;base64,...."）写进相册，返回 content:// URI。 */
    public static Uri saveDataUrl(Context ctx, String dataUrl, String displayName) throws IOException {
        int comma = dataUrl.indexOf(',');
        if (comma < 0) throw new IOException("不是合法的 data URL");
        String b64 = dataUrl.substring(comma + 1);
        byte[] bytes;
        try {
            bytes = Base64.decode(b64, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            throw new IOException("base64 解码失败", e);
        }
        if (bytes.length == 0) throw new IOException("图片内容为空");
        return saveJpeg(ctx, bytes, displayName);
    }

    public static Uri saveJpeg(Context ctx, byte[] jpeg, String displayName) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/" + ALBUM);
        v.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("MediaStore 拒绝插入");

        try (OutputStream out = cr.openOutputStream(uri)) {
            if (out == null) throw new IOException("打不开 MediaStore 输出流");
            out.write(jpeg);
            out.flush();
        } catch (IOException e) {
            // 写失败要把半成品删掉，否则相册里会留一张打不开的图
            cr.delete(uri, null, null);
            throw e;
        }

        ContentValues done = new ContentValues();
        done.put(MediaStore.Images.Media.IS_PENDING, 0);
        cr.update(uri, done, null, null);
        return uri;
    }

    /** 文件名里不能有的字符，换成下划线。 */
    public static String safeName(String raw, String fallback) {
        if (raw == null || raw.trim().isEmpty()) return fallback;
        String s = raw.trim().replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        if (s.length() > 60) s = s.substring(0, 60);
        return s.isEmpty() ? fallback : s;
    }
}
