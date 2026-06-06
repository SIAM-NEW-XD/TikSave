package com.tiksave.app;

import android.Manifest;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String APP_URL = "file:///android_asset/index.html";
    private long downloadId = -1;

    private BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id == downloadId) {
                saveToGallery(id);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        setupWebView();
        requestStoragePermission();

        // TikTok share থেকে এলে URL auto-paste করো
        handleSharedIntent(getIntent());

        // Download complete receiver register
        registerReceiver(downloadReceiver,
            new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMediaPlaybackRequiresUserGesture(false);

        // Cookies enable
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // External links browser এ খুলবে
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    if (!url.contains("tikwm.com") && !url.contains("tiktok.com")) {
                        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        startActivity(i);
                        return true;
                    }
                }
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient());

        // ডাউনলোড interceptor — Gallery তে সেভ করবে
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                                        String contentDisposition, String mimeType, long contentLength) {
                startDownload(url, contentDisposition, mimeType);
            }
        });

        // HTML asset load করো
        webView.loadUrl(APP_URL);
    }

    private void startDownload(String url, String contentDisposition, String mimeType) {
        // ফাইলের নাম বের করো
        String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
        if (!fileName.endsWith(".mp4")) fileName = fileName.replaceAll("\\.[^.]+$", "") + ".mp4";

        Toast.makeText(this, "⬇ ডাউনলোড শুরু হচ্ছে...", Toast.LENGTH_SHORT).show();

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setMimeType("video/mp4");
        request.addRequestHeader("User-Agent", "Mozilla/5.0 (Android)");
        request.setTitle("TikSave — " + fileName);
        request.setDescription("TikTok ভিডিও ডাউনলোড হচ্ছে…");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

        // Android 10+ এ MediaStore দিয়ে Gallery তে যাবে
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "TikSave/" + fileName);
        } else {
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DCIM, "TikSave/" + fileName);
        }
        request.allowScanningByMediaScanner();

        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm != null) {
            downloadId = dm.enqueue(request);
        }
    }

    // ডাউনলোড শেষে Gallery scan করো
    private void saveToGallery(long id) {
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return;

        DownloadManager.Query q = new DownloadManager.Query();
        q.setFilterById(id);
        Cursor c = dm.query(q);
        if (c != null && c.moveToFirst()) {
            int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                String localUri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));

                // Android 10+ Media scan
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && localUri != null) {
                    try {
                        Uri fileUri = Uri.parse(localUri);
                        File file = new File(fileUri.getPath());

                        ContentValues values = new ContentValues();
                        values.put(MediaStore.Video.Media.DISPLAY_NAME, file.getName());
                        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                        values.put(MediaStore.Video.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_DCIM + "/TikSave");

                        Uri collection = MediaStore.Video.Media.getContentUri(
                            MediaStore.VOLUME_EXTERNAL_PRIMARY);
                        Uri item = getContentResolver().insert(collection, values);

                        if (item != null) {
                            try (OutputStream os = getContentResolver().openOutputStream(item);
                                 InputStream is = new FileInputStream(file)) {
                                byte[] buf = new byte[8192];
                                int len;
                                while ((len = is.read(buf)) > 0) os.write(buf, 0, len);
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else if (localUri != null) {
                    // Android 9 এবং নিচে — Media Scanner
                    sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                        Uri.parse(localUri)));
                }

                runOnUiThread(() ->
                    Toast.makeText(this,
                        "✅ ভিডিও Gallery তে সেভ হয়েছে! (DCIM/TikSave)",
                        Toast.LENGTH_LONG).show()
                );
            }
            c.close();
        }
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ — READ_MEDIA_VIDEO
            if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_MEDIA_VIDEO},
                    PERMISSION_REQUEST_CODE);
            }
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            // Android 9 এবং নিচে
            if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    }, PERMISSION_REQUEST_CODE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "✅ Permission দেওয়া হয়েছে!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "⚠️ Storage permission দিন, নাহলে ডাউনলোড হবে না।",
                    Toast.LENGTH_LONG).show();
            }
        }
    }

    // TikTok Share intent handle
    private void handleSharedIntent(Intent intent) {
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (sharedText != null) {
                // URL extract করে WebView এ inject করো
                String url = extractUrl(sharedText);
                if (url != null) {
                    webView.evaluateJavascript(
                        "document.getElementById('url-input').value = '" + url.replace("'", "\\'") + "';",
                        null
                    );
                }
            }
        }
    }

    private String extractUrl(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("https?://[^\\s]*tiktok[^\\s]*")
            .matcher(text);
        return m.find() ? m.group() : null;
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleSharedIntent(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(downloadReceiver); } catch (Exception ignored) {}
    }
}
