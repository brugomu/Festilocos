package com.festilocos.juego;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

// The whole game lives in assets/index.html; this activity just shows it full screen, offline.
public class MainActivity extends Activity {
    private static final String TAG = "FESTI";
    private WebView web;
    private int crashes = 0;
    private FestiNet net;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WebView.setWebContentsDebuggingEnabled(true);
        createWebView();
    }

    private void createWebView() {
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);   // keeps coins, stickers and cosmetics between sessions
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient() {
            // if the page's renderer dies, rebuild it instead of taking the whole app down
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                Log.e(TAG, "renderer gone, crashed=" + (Build.VERSION.SDK_INT >= 26 && detail.didCrash()));
                if (web != null) { ((android.view.ViewGroup) web.getParent()).removeView(web); web.destroy(); web = null; }
                if (++crashes <= 2) { createWebView(); return true; }
                android.widget.TextView t = new android.widget.TextView(MainActivity.this);
                t.setText("El juego se cerró solo varias veces. Sacale una captura a esto y mandásela a Claude.\nDetalle: el motor del navegador interno se detuvo (crash=" + (Build.VERSION.SDK_INT >= 26 && detail.didCrash()) + ").");
                t.setTextColor(0xFFFFE2B8); t.setBackgroundColor(0xFF120A1A); t.setPadding(40, 40, 40, 40); t.setTextSize(18);
                setContentView(t);
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i(TAG, m.messageLevel() + " " + m.message() + " @" + m.lineNumber());
                return true;
            }
        });
        if (net == null) net = new FestiNet(this, web); else net.setWebView(web);
        web.addJavascriptInterface(net, "FestiNet");
        web.setBackgroundColor(0xFF120A1A);
        setContentView(web);
        hideBars();
        web.loadUrl("file:///android_asset/index.html");
    }

    private void hideBars() {
        if (web == null) return;
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    // the Android back button acts like Escape in the game (closes menus / pauses)
    @Override
    public void onBackPressed() {
        if (web != null) web.evaluateJavascript("window.dispatchEvent(new KeyboardEvent('keydown',{code:'Escape',key:'Escape'}));", null);
    }

    @Override
    protected void onDestroy() { if (net != null) net.shutdown(); super.onDestroy(); }

    @Override
    protected void onPause() { super.onPause(); if (web != null) web.onPause(); }

    @Override
    protected void onResume() { super.onResume(); if (web != null) { web.onResume(); hideBars(); } }
}
