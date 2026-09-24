package com.filmbox.archive;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

/**
 * 菲林档案 —— 外壳。
 *
 * 界面全部来自 assets/index.html（由 build_assets.py 从原型生成）。
 * 这一层负责：把页面挂到真实的 https 源上、注入 JS 桥、跟随主题改系统栏、托管返回键。
 */
public class MainActivity extends Activity {

    /** WebViewAssetLoader 的默认域。显式写出来，白名单要用同一个常量。 */
    public static final String DOMAIN = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + DOMAIN + "/assets/index.html";

    private static final int BG_DARKROOM = 0xFF15110D;
    private static final int BG_PAPER = 0xFFEFE6D6;

    private WebView web;
    private WebViewAssetLoader loader;
    private Bridge bridge;
    private DocStore store;
    private PhotoStore photos;
    private Importer importer;
    private Capture capture;

    /** 主题切换时同步系统栏。页面管 CSS，这里管状态栏/导航栏。 */
    public static void applyThemeBars(Activity act, String theme) {
        boolean paper = "paper".equals(theme);
        Window w = act.getWindow();
        w.setStatusBarColor(paper ? BG_PAPER : BG_DARKROOM);
        w.setNavigationBarColor(paper ? BG_PAPER : BG_DARKROOM);

        // 浅色主题下状态栏图标必须是深色，否则白字压白底看不见
        View decor = w.getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (paper) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        } else {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        decor.setSystemUiVisibility(flags);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        store = new DocStore(this);
        photos = new PhotoStore(this);

        loader = new WebViewAssetLoader.Builder()
                .setDomain(DOMAIN)
                .addPathHandler("/assets/", new AssetPathHandler(this))
                // 照片走普通 <img src> 原生加载，不用 base64 往返
                .addPathHandler("/photos/", new PhotoProvider(photos.photos))
                .addPathHandler("/thumbs/", new PhotoProvider(photos.thumbs))
                .addPathHandler("/gear/", new PhotoProvider(photos.gear))
                // 拍照结果落在 tmp/，页面要能立刻预览，所以也得挂出来
                .addPathHandler("/tmp/", new PhotoProvider(photos.tmp))
                .build();

        web = new WebView(this);
        web.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.setBackgroundColor(BG_DARKROOM);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage：崩溃恢复镜像和主题都要用
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setMediaPlaybackRequiresUserGesture(false);

        // 页面挂在 https 源上，不需要任何 file/content 访问。
        // 桥暴露给页面加载的一切，所以攻击面要收到最小。
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                return loader.shouldInterceptRequest(req.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                // 只允许我们自己的源。任何外部跳转都不接管 —— 否则一个远端页面
                // 就能拿到 window.AndroidBridge。
                return !DOMAIN.equals(req.getUrl().getHost());
            }
        });

        bridge = new Bridge(this, web, store, photos);
        importer = new Importer(this, photos, bridge);
        capture = new Capture(this, photos.tmp, bridge);
        bridge.attach(importer);
        bridge.attach(capture);
        web.addJavascriptInterface(bridge, "AndroidBridge");

        // 这个项目的失败模式全是静默的，没有 chrome://inspect 就是盲调
        WebView.setWebContentsDebuggingEnabled(true);

        setContentView(web);
        applyThemeBars(this, "darkroom");
        web.loadUrl(START_URL);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (importer != null) importer.onActivityResult(req, res, data);
        if (capture != null) capture.onActivityResult(req, res, data);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 必须同步落盘：DocStore 有 500ms 防抖，切后台就丢最近的编辑
        if (store != null) store.flush();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    protected void onDestroy() {
        if (bridge != null) bridge.markDead();
        if (store != null) store.shutdown();
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
