package com.filmbox.archive;

import android.content.Context;
import android.content.res.AssetManager;
import android.webkit.WebResourceResponse;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.webkit.WebViewAssetLoader;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 把 /assets/ 映射到 APK 内的 assets，带**显式** MIME 表。
 *
 * 为什么不用 androidx.webkit 自带的 AssetsPathHandler：它委托给
 * AssetHelper.guessMimeType()，而后者基于 URLConnection.guessContentTypeFromName，
 * java.net 的类型表里**没有 .woff2**，会退回 text/plain。
 *
 * 以前走 file:// 时 Chromium 用自己的扩展名表（认识 font/woff2），所以没暴露；
 * 换成 WebViewAssetLoader 后会静默改变行为。这个项目已经因为静默字体回退栽过一次
 * —— 字体加载不到页面不报任何错，只是看起来"有点不一样"。20 行的显式表买断这类风险。
 */
public class AssetPathHandler implements WebViewAssetLoader.PathHandler {

    private static final Map<String, String> MIME = new HashMap<>();

    static {
        MIME.put("html", "text/html");
        MIME.put("js", "application/javascript");
        MIME.put("css", "text/css");
        MIME.put("json", "application/json");
        MIME.put("svg", "image/svg+xml");
        MIME.put("png", "image/png");
        MIME.put("jpg", "image/jpeg");
        MIME.put("jpeg", "image/jpeg");
        MIME.put("webp", "image/webp");
        MIME.put("gif", "image/gif");
        MIME.put("woff2", "font/woff2");
        MIME.put("woff", "font/woff");
        MIME.put("ttf", "font/ttf");
        MIME.put("otf", "font/otf");
        // MediaPipe 的模型：必须按二进制原样发
        MIME.put("tflite", "application/octet-stream");
        MIME.put("task", "application/octet-stream");
    }

    private final AssetManager assets;

    public AssetPathHandler(Context ctx) {
        this.assets = ctx.getAssets();
    }

    @Nullable
    @Override
    public WebResourceResponse handle(@NonNull String path) {
        // path 是相对于注册前缀的（"index.html"、"spacemono-400.woff2"）
        if (path.isEmpty() || path.contains("..") || path.indexOf('\0') >= 0) return null;

        String clean = path.startsWith("/") ? path.substring(1) : path;
        if (clean.isEmpty()) clean = "index.html";

        InputStream in = null;
        try {
            in = assets.open(clean);
        } catch (IOException e) {
            return null;   // 让 loader 走 404，而不是回一个空响应
        }

        Map<String, String> headers = new HashMap<>();
        if ("index.html".equals(clean)) {
            // 页面本身不缓存，重新构建后能立刻看到
            headers.put("Cache-Control", "no-cache");
        } else {
            // 字体和模型随包发布，文件名不会变内容就变
            headers.put("Cache-Control", "max-age=31536000, immutable");
        }

        return new WebResourceResponse(mimeOf(clean), null, 200, "OK", headers, in);
    }

    private static String mimeOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "application/octet-stream";
        String m = MIME.get(name.substring(dot + 1).toLowerCase());
        return m != null ? m : "application/octet-stream";
    }
}
