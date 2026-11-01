package app.offlineai.chat;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline AI - a thin Android shell around the Offline AI server that runs in Termux.
 * It starts the server through Termux (RUN_COMMAND), shows the chat full-screen in a WebView,
 * handles camera / photo / file pickers and saves downloads to Downloads/Offline AI.
 */
public class MainActivity extends Activity {

    private static final String HOME_URL = "http://127.0.0.1:8080/";
    private static final String STATUS_URL = "http://127.0.0.1:8080/api/status";
    private static final String TERMUX_PKG = "com.termux";
    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    private static final String TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final String PERM_RUN_COMMAND = "com.termux.permission.RUN_COMMAND";

    private static final int REQ_PERMISSION = 11;
    private static final int REQ_FILE = 12;
    private static final int REQ_CAMERA = 13;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraUri;
    private boolean appLoaded = false;
    private boolean waiting = false;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);
        applyBars();
        configureWebView();
        boot();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        if (appLoaded) {
            // If Android killed the server while we were in the background, start it again.
            new Thread(() -> {
                if (!isUp()) ui.post(() -> {
                    if (hasRunPermission()) startTermuxServer();
                });
            }).start();
        }
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyBars();
        pushTheme();
    }

    @Override
    public void onBackPressed() {
        if (!appLoaded || web == null) {
            moveTaskToBack(true);
            return;
        }
        web.evaluateJavascript("(window.oaiBack && window.oaiBack()) ? 'yes' : 'no'", value -> {
            if (value == null || !value.contains("yes")) moveTaskToBack(true);
        });
    }

    // ------------------------------------------------------------------ look

    private boolean isNight() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    @SuppressWarnings("deprecation")
    private void applyBars() {
        boolean night = isNight();
        int bg = night ? 0xFF1F1E1D : 0xFFFAF9F5;
        Window w = getWindow();
        w.setStatusBarColor(bg);
        w.setNavigationBarColor(bg);
        int flags = w.getDecorView().getSystemUiVisibility();
        int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        flags = night ? (flags & ~light) : (flags | light);
        w.getDecorView().setSystemUiVisibility(flags);
        if (web != null) web.setBackgroundColor(bg);
    }

    private void pushTheme() {
        if (web != null && appLoaded) {
            web.evaluateJavascript("window.oaiSetTheme && window.oaiSetTheme(" + isNight() + ")", null);
        }
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @SuppressWarnings("deprecation")
    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        if (Build.VERSION.SDK_INT >= 33) {
            s.setAlgorithmicDarkeningAllowed(false);
        } else {
            s.setForceDark(WebSettings.FORCE_DARK_OFF);
        }
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.addJavascriptInterface(new Bridge(), "Android");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String host = u.getHost();
                if ("127.0.0.1".equals(host) || "localhost".equals(host)) return false;
                String scheme = u.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme) || "mailto".equals(scheme)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, u));
                    } catch (Exception ignored) {
                        // no app can open it
                    }
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pushTheme();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && appLoaded) {
                    appLoaded = false;
                    showError("Can't reach Offline AI",
                            "The server stopped. Tap Try again to start it.", true);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    if (params.isCaptureEnabled()) {
                        Intent cam = cameraIntent();
                        if (cam != null) {
                            startActivityForResult(cam, REQ_CAMERA);
                            return true;
                        }
                    }
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    filePathCallback = null;
                    callback.onReceiveValue(null);
                    toast("Can't open the file picker");
                }
                return true;
            }
        });

        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                saveDownload(url, contentDisposition, mimeType));
    }

    /** Small bridge used by the "Try again" button on the start / error pages. */
    private class Bridge {
        @JavascriptInterface
        public void retry() {
            ui.post(MainActivity.this::boot);
        }
    }

    private String page(String title, String message, boolean spinner, boolean retry) {
        boolean n = isNight();
        String bg = n ? "#1f1e1d" : "#faf9f5";
        String fg = n ? "#f0eee6" : "#1f1e1d";
        String mu = n ? "#9c988c" : "#7a776f";
        return "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{margin:0;background:" + bg + ";color:" + fg + ";font-family:sans-serif;"
                + "display:flex;align-items:center;justify-content:center;min-height:100vh;text-align:center}"
                + ".b{padding:28px;max-width:420px}.t{font-size:22px;font-weight:700;margin-bottom:10px}"
                + ".m{color:" + mu + ";line-height:1.5;font-size:16px;white-space:pre-line}"
                + ".s{width:28px;height:28px;margin:24px auto;border:3px solid #8884;border-top-color:#c96442;"
                + "border-radius:50%;animation:r 1s linear infinite}@keyframes r{to{transform:rotate(360deg)}}"
                + "button{margin-top:22px;background:#c96442;color:#fff;border:0;border-radius:12px;"
                + "padding:12px 22px;font-size:16px;font-weight:600}</style></head><body><div class='b'>"
                + "<div class='t'>" + title + "</div><div class='m'>" + message + "</div>"
                + (spinner ? "<div class='s'></div>" : "")
                + (retry ? "<button onclick='Android.retry()'>Try again</button>" : "")
                + "</div></body></html>";
    }

    private void showSplash(String message) {
        appLoaded = false;
        web.loadDataWithBaseURL(null, page("Offline AI", message, true, false), "text/html", "utf-8", null);
    }

    private void showError(String title, String message, boolean retry) {
        waiting = false;
        appLoaded = false;
        if (web != null) {
            web.loadDataWithBaseURL(null, page(title, message, false, retry), "text/html", "utf-8", null);
        }
    }

    // ------------------------------------------------------------------ starting the server through Termux

    private boolean isTermuxInstalled() {
        try {
            getPackageManager().getPackageInfo(TERMUX_PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasRunPermission() {
        return checkSelfPermission(PERM_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isUp() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(STATUS_URL).openConnection();
            c.setConnectTimeout(1200);
            c.setReadTimeout(2500);
            return c.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void boot() {
        if (waiting) return;
        showSplash("Starting the AI engine...");
        if (!isTermuxInstalled()) {
            showError("Termux is not installed",
                    "Offline AI runs its engine inside Termux.\nInstall Termux, then run the Offline AI setup script in it.", true);
            return;
        }
        waiting = true;
        new Thread(() -> {
            if (isUp()) {
                ui.post(this::loadApp);
                return;
            }
            ui.post(() -> {
                if (hasRunPermission()) {
                    launchAndWait();
                } else {
                    waiting = false;
                    requestPermissions(new String[]{PERM_RUN_COMMAND}, REQ_PERMISSION);
                }
            });
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMISSION) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            waiting = true;
            launchAndWait();
        } else {
            showError("Permission needed",
                    "Allow \"Run commands in Termux environment\" for Offline AI:\n"
                            + "Settings > Apps > Offline AI > Permissions.\n\n"
                            + "Or open Termux and type offline-ai - this app connects by itself.", true);
        }
    }

    private void launchAndWait() {
        showSplash("Starting the AI engine...");
        waiting = true;
        if (!startTermuxServer()) {
            showError("Could not start Termux",
                    "Open Termux once, run the Offline AI setup script, then try again.", true);
            return;
        }
        new Thread(() -> {
            long end = System.currentTimeMillis() + 70000;
            while (System.currentTimeMillis() < end) {
                if (isUp()) {
                    ui.post(this::loadApp);
                    return;
                }
                try {
                    Thread.sleep(700);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
            ui.post(() -> showError("The AI engine did not start",
                    "Check these in Termux:\n"
                            + "1. Run the setup script again (it enables \"allow-external-apps\").\n"
                            + "2. Type offline-ai once and see if it shows an error.\n"
                            + "3. Android battery settings: set Termux to \"No restrictions\".", true));
        }).start();
    }

    private boolean startTermuxServer() {
        try {
            Intent i = new Intent("com.termux.RUN_COMMAND");
            i.setClassName(TERMUX_PKG, "com.termux.app.RunCommandService");
            i.putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BASH);
            i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{TERMUX_HOME + "/offline-ai/start.sh", "serve"});
            i.putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME);
            i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            startForegroundService(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void loadApp() {
        waiting = false;
        appLoaded = true;
        web.loadUrl(HOME_URL);
    }

    // ------------------------------------------------------------------ camera / file picker

    private Intent cameraIntent() {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, "OfflineAI_" + System.currentTimeMillis() + ".jpg");
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Offline AI");
            cameraUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (cameraUri == null) return null;
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (i.resolveActivity(getPackageManager()) == null) {
                getContentResolver().delete(cameraUri, null, null);
                cameraUri = null;
                return null;
            }
            return i;
        } catch (Exception e) {
            cameraUri = null;
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE || requestCode == REQ_CAMERA) {
            Uri[] result = null;
            if (resultCode == RESULT_OK) {
                if (requestCode == REQ_CAMERA && cameraUri != null) {
                    result = new Uri[]{cameraUri};
                } else {
                    result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                }
            } else if (requestCode == REQ_CAMERA && cameraUri != null) {
                try {
                    getContentResolver().delete(cameraUri, null, null);
                } catch (Exception ignored) {
                    // nothing to clean up
                }
            }
            if (filePathCallback != null) filePathCallback.onReceiveValue(result);
            filePathCallback = null;
            cameraUri = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // ------------------------------------------------------------------ downloads

    private static final Pattern CD_UTF8 = Pattern.compile("filename\\*\\s*=\\s*UTF-8''([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CD_PLAIN = Pattern.compile("filename\\s*=\\s*\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);

    private String fileNameFor(String url, String contentDisposition) {
        try {
            if (contentDisposition != null) {
                Matcher m = CD_UTF8.matcher(contentDisposition);
                if (m.find()) return URLDecoder.decode(m.group(1).trim(), "UTF-8");
                m = CD_PLAIN.matcher(contentDisposition);
                if (m.find()) return m.group(1).trim();
            }
            String last = Uri.parse(url).getLastPathSegment();
            if (last != null && !last.isEmpty()) return last;
        } catch (Exception ignored) {
            // fall through
        }
        return "download.bin";
    }

    private String mimeFor(String name, String fallback) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (m != null) return m;
            if (ext.equals("srt") || ext.equals("md") || ext.equals("log")) return "text/plain";
        }
        return fallback != null && !fallback.isEmpty() ? fallback : "application/octet-stream";
    }

    private void saveDownload(String url, String contentDisposition, String mimeType) {
        final String name = fileNameFor(url, contentDisposition).replaceAll("[\\\\/:*?\"<>|]", "_");
        toast("Saving " + name + "...");
        new Thread(() -> {
            HttpURLConnection c = null;
            Uri item = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(60000);
                if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, mimeFor(name, mimeType));
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Offline AI");
                v.put(MediaStore.Downloads.IS_PENDING, 1);
                item = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (item == null) throw new IOException("cannot create file");
                try (InputStream in = c.getInputStream();
                     OutputStream out = getContentResolver().openOutputStream(item)) {
                    if (out == null) throw new IOException("cannot write file");
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(item, done, null, null);
                ui.post(() -> toast("Saved: Downloads/Offline AI/" + name));
            } catch (Exception e) {
                if (item != null) {
                    try {
                        getContentResolver().delete(item, null, null);
                    } catch (Exception ignored) {
                        // nothing to clean up
                    }
                }
                final String msg = e.getMessage();
                ui.post(() -> toast("Download failed: " + msg));
            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
}
