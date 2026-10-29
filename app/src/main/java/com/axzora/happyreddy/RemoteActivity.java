package com.axzora.happyreddy;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Happy Reddy 😄 — Android remote for the MARK XLVIII assistant.
 *
 * Connects to the Happy Reddy PC dashboard (FastAPI, port 8000/8001,
 * self-signed HTTPS). Supports login, encrypted commands, phone-mic
 * streaming (getUserMedia -> /ws/phone-audio), file upload and download.
 */
public class RemoteActivity extends Activity {

    private static final String PREFS       = "happyreddy_prefs";
    private static final String KEY_URL     = "server_url";
    private static final int    REQ_MIC     = 1;
    private static final int    REQ_FILE    = 2;

    private static final int BG      = Color.parseColor("#07090F");
    private static final int ACCENT  = Color.parseColor("#6366F1");
    private static final int TEXT    = Color.parseColor("#DDE3ED");
    private static final int MUTED   = Color.parseColor("#5E6A7E");
    private static final int SURFACE = Color.parseColor("#141824");

    private WebView webView;
    private String  serverHost = "";
    private PermissionRequest pendingMicRequest;
    private ValueCallback<Uri[]> pendingFileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        String saved = prefs().getString(KEY_URL, "");
        if (saved.isEmpty()) {
            showSetup(null);
        } else {
            showWeb(saved);
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    // ── Setup screen (programmatic UI, no layout XML) ────────────────────────

    private void showSetup(String error) {
        if (webView != null) {
            webView.loadUrl("about:blank");
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(BG);
        int pad = dp(28);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("😄 HAPPY REDDY");
        title.setTextColor(TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setGravity(Gravity.CENTER);
        root.addView(title, lp());

        TextView sub = new TextView(this);
        sub.setText("AXZORA  ·  MARK XLVIII  ·  REMOTE");
        sub.setTextColor(ACCENT);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(4), 0, dp(36));
        root.addView(sub, lp());

        final EditText input = new EditText(this);
        input.setHint("PC address — e.g. 192.168.29.83");
        input.setText(prefs().getString(KEY_URL, ""));
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        input.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(SURFACE);
        inputBg.setCornerRadius(dp(12));
        inputBg.setStroke(dp(1), Color.parseColor("#242A3A"));
        input.setBackground(inputBg);
        root.addView(input, lp());

        Button connect = new Button(this);
        connect.setText("CONNECT");
        connect.setTextColor(Color.WHITE);
        connect.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(ACCENT);
        btnBg.setCornerRadius(dp(12));
        connect.setBackground(btnBg);
        connect.setPadding(0, dp(14), 0, dp(14));
        LinearLayout.LayoutParams blp = lp();
        blp.topMargin = dp(14);
        root.addView(connect, blp);

        TextView err = new TextView(this);
        err.setTextColor(Color.parseColor("#F87171"));
        err.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        err.setGravity(Gravity.CENTER);
        err.setPadding(0, dp(14), 0, 0);
        if (error != null) {
            err.setText(error);
        }
        root.addView(err, lp());

        TextView tip = new TextView(this);
        tip.setText("Open “Remote Control” in Happy Reddy on your PC to see the "
                + "address and one-time key.\n\nLong-press Back anytime to return here.");
        tip.setTextColor(MUTED);
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, dp(30), 0, 0);
        root.addView(tip, lp());

        connect.setOnClickListener(v -> {
            String url = normalizeUrl(input.getText().toString());
            if (url == null) {
                err.setText("Enter the PC address shown by Happy Reddy.");
                return;
            }
            prefs().edit().putString(KEY_URL, url).apply();
            showWeb(url);
        });

        setContentView(root);
    }

    private LinearLayout.LayoutParams lp() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    /** "192.168.1.5" -> "https://192.168.1.5:8000"; keeps explicit scheme/port. */
    private String normalizeUrl(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return null;
        if (!s.contains("://")) s = "https://" + s;
        Uri u = Uri.parse(s);
        if (u.getHost() == null || u.getHost().isEmpty()) return null;
        if (u.getPort() == -1) {
            s = u.getScheme() + "://" + u.getHost() + ":8000"
                    + (u.getPath() == null ? "" : u.getPath());
        }
        return s;
    }

    // ── WebView screen ───────────────────────────────────────────────────────

    @SuppressWarnings("SetJavaScriptEnabled")
    private void showWeb(String url) {
        serverHost = Uri.parse(url).getHost();

        if (webView == null) {
            webView = new WebView(this);
            webView.setBackgroundColor(BG);
            WebSettings s = webView.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setDatabaseEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setLoadWithOverviewMode(true);
            s.setUseWideViewPort(true);
            s.setSupportZoom(false);
            CookieManager.getInstance().setAcceptCookie(true);

            webView.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onPermissionRequest(PermissionRequest request) {
                    boolean wantsAudio = false;
                    for (String r : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) {
                            wantsAudio = true;
                        }
                    }
                    if (!wantsAudio) {
                        request.deny();
                        return;
                    }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        pendingMicRequest = request;
                        requestPermissions(
                                new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                    }
                }

                @Override
                public boolean onShowFileChooser(WebView view,
                                                 ValueCallback<Uri[]> callback,
                                                 FileChooserParams params) {
                    if (pendingFileCallback != null) {
                        pendingFileCallback.onReceiveValue(null);
                    }
                    pendingFileCallback = callback;
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    try {
                        startActivityForResult(
                                Intent.createChooser(i, "Send file to Happy Reddy"), REQ_FILE);
                    } catch (Exception e) {
                        pendingFileCallback = null;
                        callback.onReceiveValue(null);
                        return false;
                    }
                    return true;
                }
            });

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onReceivedSslError(WebView view, SslErrorHandler handler,
                                               SslError error) {
                    // Happy Reddy uses a self-signed certificate. Trust it ONLY for
                    // the configured server host — everything else is rejected.
                    String h = Uri.parse(error.getUrl()).getHost();
                    if (h != null && h.equals(serverHost)) {
                        handler.proceed();
                    } else {
                        handler.cancel();
                    }
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView view,
                                                        WebResourceRequest request) {
                    Uri u = request.getUrl();
                    String h = u.getHost();
                    if (h != null && h.equals(serverHost)) {
                        return false; // keep dashboard navigation inside the app
                    }
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, u));
                    } catch (Exception ignored) { }
                    return true;
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request,
                                            WebResourceError error) {
                    if (request.isForMainFrame()) {
                        String desc = String.valueOf(error.getDescription());
                        showSetup("Can’t reach Happy Reddy (" + desc + ").\n"
                                + "Is it running on the PC? Same Wi-Fi network?");
                    }
                }
            });

            webView.setDownloadListener((dlUrl, userAgent, contentDisposition,
                                         mimeType, contentLength) ->
                    downloadFile(dlUrl, contentDisposition, mimeType));
        }

        setContentView(webView);
        webView.loadUrl(url);
    }

    // ── File download (self-signed HTTPS aware, saves to Downloads/JARVIS) ───

    private void downloadFile(String dlUrl, String contentDisposition, String mimeType) {
        String name = URLUtil.guessFileName(dlUrl, contentDisposition, mimeType);
        Toast.makeText(this, "Downloading " + name + "…", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                HttpURLConnection conn =
                        (HttpURLConnection) new URL(dlUrl).openConnection();
                if (conn instanceof HttpsURLConnection
                        && serverHost.equals(Uri.parse(dlUrl).getHost())) {
                    HttpsURLConnection sconn = (HttpsURLConnection) conn;
                    SSLContext ctx = SSLContext.getInstance("TLS");
                    ctx.init(null, new TrustManager[]{new X509TrustManager() {
                        public void checkClientTrusted(X509Certificate[] c, String a) { }
                        public void checkServerTrusted(X509Certificate[] c, String a) { }
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }}, null);
                    sconn.setSSLSocketFactory(ctx.getSocketFactory());
                    sconn.setHostnameVerifier((h, sess) -> h.equals(serverHost));
                }
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);

                if (conn.getResponseCode() != 200) {
                    toastUi("Download failed (HTTP " + conn.getResponseCode() + ")");
                    return;
                }

                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE,
                        mimeType == null || mimeType.isEmpty()
                                ? "application/octet-stream" : mimeType);
                cv.put(MediaStore.Downloads.RELATIVE_PATH, "Download/HappyReddy");
                Uri dest = getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (dest == null) {
                    toastUi("Could not create download file");
                    return;
                }

                try (InputStream in = conn.getInputStream();
                     OutputStream out = getContentResolver().openOutputStream(dest)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                }
                toastUi("Saved to Download/HappyReddy/" + name);
            } catch (Exception e) {
                toastUi("Download error: " + e.getMessage());
            }
        }).start();
    }

    private void toastUi(String msg) {
        runOnUiThread(() ->
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    // ── Permission / file-chooser results ────────────────────────────────────

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req == REQ_MIC && pendingMicRequest != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                pendingMicRequest.grant(
                        new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else {
                pendingMicRequest.deny();
                Toast.makeText(this,
                        "Microphone permission is needed for voice mode",
                        Toast.LENGTH_LONG).show();
            }
            pendingMicRequest = null;
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_FILE || pendingFileCallback == null) return;

        Uri[] uris = null;
        if (result == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                uris = new Uri[count];
                for (int i = 0; i < count; i++) {
                    uris[i] = data.getClipData().getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                uris = new Uri[]{data.getData()};
            }
        }
        pendingFileCallback.onReceiveValue(uris);
        pendingFileCallback = null;
    }

    // ── Navigation ───────────────────────────────────────────────────────────

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            showSetup(null); // long-press Back -> change server
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.getParent() != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            finish(); // return to the voice assistant screen
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}

