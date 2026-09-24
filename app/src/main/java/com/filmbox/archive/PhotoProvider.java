package com.filmbox.archive;

import android.webkit.WebResourceResponse;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 把 /photos/ /thumbs/ /gear/ 映射到 App 私有目录，让页面能用普通
 * &lt;img src&gt; 原生加载图片 —— 不用 base64 往返，WebView 自己会缓存和异步解码。
 *
 * 安全：PathHandler 对 WebView 加载的任何东西都可达，所以必须防目录穿越。
 * 先做字符串层拒绝，再用 getCanonicalPath() 确认落点在根目录内 —— 两道都要，
 * 因为符号链接和 Windows 短路径能让纯字符串检查失效。
 */
public class PhotoProvider implements WebViewAssetLoader.PathHandler {

    private static final Map<String, String> MIME = new HashMap<>();
    static {
        MIME.put("jpg", "image/jpeg");
        MIME.put("jpeg", "image/jpeg");
        MIME.put("png", "image/png");
        MIME.put("webp", "image/webp");
    }

    private final File root;
    private final String rootCanonical;

    public PhotoProvider(File root) {
        this.root = root;
        String c;
        try {
            c = root.getCanonicalPath();
        } catch (IOException e) {
            c = root.getAbsolutePath();
        }
        this.rootCanonical = c;
    }

    @Nullable
    @Override
    public WebResourceResponse handle(@NonNull String path) {
        if (path.isEmpty() || path.indexOf('\0') >= 0) return null;

        String clean = path.startsWith("/") ? path.substring(1) : path;
        if (clean.isEmpty() || clean.contains("..")) return null;

        File f = new File(root, clean);
        String canonical;
        try {
            canonical = f.getCanonicalPath();
        } catch (IOException e) {
            return null;
        }
        // 落点必须在根目录之内。加分隔符是为了挡住 /photos-evil 这种前缀相同的情况。
        if (!canonical.startsWith(rootCanonical + File.separator)) return null;
        if (!f.isFile()) return null;   // 返回 null 让 loader 走 404，而不是回空响应

        InputStream in;
        try {
            in = new FileInputStream(f);
        } catch (IOException e) {
            return null;
        }

        Map<String, String> headers = new HashMap<>();
        // 照片 id 一次性生成、永不复用，所以内容不可变 —— 可以放心长缓存。
        // 这很重要：render() 每次点击都会重建整个底片条的 <img>，
        // 没有强缓存的话每帧都要重新解码。
        headers.put("Cache-Control", "max-age=31536000, immutable");

        return new WebResourceResponse(mimeOf(clean), null, 200, "OK", headers, in);
    }

    private static String mimeOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "application/octet-stream";
        String m = MIME.get(name.substring(dot + 1).toLowerCase());
        return m != null ? m : "application/octet-stream";
    }
}
