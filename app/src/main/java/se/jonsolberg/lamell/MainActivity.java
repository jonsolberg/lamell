package se.jonsolberg.lamell;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hosts the Lamell interface (assets/index.html) in a WebView and gives it the
 * phone's camera, gallery, GPS and the offline image recognition.
 *
 * The page is served from https://appassets.androidplatform.net/ by
 * WebViewAssetLoader (nothing is downloaded); photos of finds are served from
 * the app's private storage under /photos/.
 *
 * Part of Lamell. Copyright (C) 2026 Jon Solberg.
 * Licensed under the GNU General Public License v3.
 */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START = "https://" + HOST + "/assets/index.html";
    private static final int REQ_CAMERA = 11, REQ_GALLERY = 12, REQ_LOCATION = 13;

    private WebView web;
    private File cameraFile;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean locating;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setBackgroundColor(getColor(R.color.bg));
        setContentView(web);
        if (savedInstanceState == null) UpdateChecker.start(this);

        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/photos/", new WebViewAssetLoader.InternalStoragePathHandler(this, Files.photoDir(this)))
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // settings (localStorage)
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return loader.shouldInterceptRequest(u);
                // Nothing else is ever loaded into the page: the app works offline.
                return new WebResourceResponse("text/plain", "utf-8", 403, "Offline", null, null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                // Links (Svampguiden, Artfakta, Libris, Giftinformation, Ko-fi, phone numbers …)
                // open in the phone's own apps.
                openExternal(u);
                return true;
            }
        });
        web.setWebChromeClient(new android.webkit.WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Android");

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START);
        handleShare(getIntent());

        // For the CI screenshots: adb shell am start ... --es testphoto <id> identifies a photo
        // already copied to files/photos/<id>.jpg.
        String test = getIntent().getStringExtra("testphoto");
        if (test != null && savedInstanceState == null) {
            main.postDelayed(() -> {
                try {
                    js("onPhoto", new JSONObject().put("id", test).put("shared", true).toString());
                } catch (Exception ignored) { }
            }, 4000);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShare(intent);
    }

    /** A photo shared to Lamell from another app goes straight to identification. */
    private void handleShare(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri != null) importPhoto(uri, true);
    }

    private void openExternal(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (ActivityNotFoundException ignored) {
            // no browser / dialler / maps app
        }
    }

    // ---- photos -------------------------------------------------------------------------

    private void takePhoto() {
        try {
            File dir = new File(getCacheDir(), "camera");
            if (!dir.isDirectory()) dir.mkdirs();
            cameraFile = new File(dir, "photo.jpg");
            Uri out = FileProvider.getUriForFile(this, getPackageName() + ".files", cameraFile);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, out);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_CAMERA);
        } catch (Exception e) {
            js("onPhotoError", JSONObject.quote("Ingen kameraapp hittades."));
        }
    }

    private void pickPhoto() {
        Intent i;
        if (Build.VERSION.SDK_INT >= 33) {
            i = new Intent(MediaStore.ACTION_PICK_IMAGES);
        } else {
            i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
        }
        i.setType("image/*");
        try {
            startActivityForResult(i, REQ_GALLERY);
        } catch (ActivityNotFoundException e) {
            js("onPhotoError", JSONObject.quote("Kunde inte öppna bildväljaren."));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAMERA) {
            if (resultCode == RESULT_OK && cameraFile != null && cameraFile.length() > 0) {
                importPhoto(Uri.fromFile(cameraFile), false);
            } else {
                js("onPhotoCancelled", "");
            }
        } else if (requestCode == REQ_GALLERY) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                importPhoto(data.getData(), false);
            } else {
                js("onPhotoCancelled", "");
            }
        }
    }

    /** Stores the photo (max 1600 px) and a thumbnail, then tells the page its id. */
    private void importPhoto(Uri uri, boolean shared) {
        js("onPhotoLoading", "");
        worker.execute(() -> {
            try {
                Bitmap bmp = Files.loadPhoto(this, uri, 1600);
                String id = "p" + System.currentTimeMillis();
                File dir = Files.photoDir(this);
                Files.saveJpeg(bmp, new File(dir, id + ".jpg"), 88);
                float k = 360f / Math.max(bmp.getWidth(), bmp.getHeight());
                Bitmap thumb = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * k),
                        Math.round(bmp.getHeight() * k), true);
                Files.saveJpeg(thumb, new File(dir, id + "_t.jpg"), 82);
                thumb.recycle();
                bmp.recycle();
                if (cameraFile != null) cameraFile.delete();
                JSONObject o = new JSONObject().put("id", id).put("shared", shared);
                js("onPhoto", o.toString());
            } catch (Exception e) {
                js("onPhotoError", JSONObject.quote("Bilden kunde inte läsas."));
            }
        });
    }

    private void classify(String id) {
        worker.execute(() -> {
            try {
                Bitmap bmp = Files.loadPhoto(this, Uri.fromFile(new File(Files.photoDir(this), id + ".jpg")), 1600);
                long t0 = System.currentTimeMillis();
                float[] p = Classifier.get(this).classify(bmp);
                bmp.recycle();
                JSONObject o = new JSONObject()
                        .put("id", id)
                        .put("ms", System.currentTimeMillis() - t0)
                        .put("top", Classifier.top(p, 10));
                js("onPredictions", o.toString());
            } catch (Throwable e) {
                JSONObject o = new JSONObject();
                try {
                    o.put("id", id).put("error", String.valueOf(e.getMessage()));
                } catch (Exception ignored) { }
                js("onPredictions", o.toString());
            }
        });
    }

    // ---- location -----------------------------------------------------------------------

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void locate() {
        if (!hasLocationPermission()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        if (locating) return;
        locating = true;
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        String provider = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER
                : lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER : null;
        if (provider == null) {
            locating = false;
            locationError("Platstjänster är avstängda. Slå på plats i telefonens inställningar.");
            return;
        }
        final boolean[] done = {false};
        final Location[] best = {null};
        try {
            for (String p : lm.getProviders(true)) {
                @SuppressLint("MissingPermission") Location l = lm.getLastKnownLocation(p);
                if (l != null && System.currentTimeMillis() - l.getTime() < 2 * 60 * 1000
                        && (best[0] == null || l.getAccuracy() < best[0].getAccuracy())) best[0] = l;
            }
        } catch (SecurityException ignored) { }

        android.location.LocationListener listener = new android.location.LocationListener() {
            @Override
            public void onLocationChanged(Location l) {
                if (best[0] == null || l.getAccuracy() <= best[0].getAccuracy()) best[0] = l;
                if (l.getAccuracy() <= 25) complete();
            }

            void complete() {
                if (done[0]) return;
                done[0] = true;
                locating = false;
                lm.removeUpdates(this);
                if (best[0] != null) sendLocation(best[0]);
                else locationError("Ingen position hittades. Försök igen utomhus med fri sikt mot himlen.");
            }

            @Override public void onProviderEnabled(String p) { }
            @Override public void onProviderDisabled(String p) { }
            @Override public void onStatusChanged(String p, int status, Bundle extras) { }
        };
        try {
            //noinspection MissingPermission
            lm.requestLocationUpdates(provider, 1000, 0, listener, Looper.getMainLooper());
            if (!provider.equals(LocationManager.NETWORK_PROVIDER) && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                //noinspection MissingPermission
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 0, listener, Looper.getMainLooper());
            }
        } catch (SecurityException e) {
            locating = false;
            locationError("Appen får inte använda platsen.");
            return;
        }
        // Give the GPS up to 25 s for a good fix; then use the best one so far.
        main.postDelayed(() -> {
            if (!done[0]) {
                done[0] = true;
                locating = false;
                lm.removeUpdates(listener);
                if (best[0] != null) sendLocation(best[0]);
                else locationError("Ingen position hittades. Försök igen utomhus med fri sikt mot himlen.");
            }
        }, 25000);
    }

    private void sendLocation(Location l) {
        try {
            JSONObject o = new JSONObject()
                    .put("lat", l.getLatitude())
                    .put("lon", l.getLongitude())
                    .put("acc", Math.round(l.getAccuracy()))
                    .put("time", l.getTime());
            if (l.hasAltitude()) o.put("alt", Math.round(l.getAltitude()));
            js("onLocation", o.toString());
        } catch (Exception ignored) { }
    }

    private void locationError(String msg) {
        try {
            js("onLocation", new JSONObject().put("error", msg).toString());
        } catch (Exception ignored) { }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_LOCATION) {
            if (hasLocationPermission()) locate();
            else locationError("Utan platsbehörighet kan fyndet sparas utan position.");
        }
    }

    // ---- plumbing ------------------------------------------------------------------------

    /** Calls window[fn](arg) in the page, on the UI thread. arg is a JS literal. */
    private void js(String fn, String arg) {
        main.post(() -> web.evaluateJavascript("window." + fn + "&&" + fn + "(" + arg + ")", null));
    }

    private boolean systemDark() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        boolean dark = (newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        web.evaluateJavascript("window.onSystemTheme&&onSystemTheme(" + dark + ")", null);
    }

    private void applyBars(boolean dark) {
        int color = getColor(dark ? R.color.bg_dark : R.color.bg_light);
        getWindow().setStatusBarColor(color);
        getWindow().setNavigationBarColor(color);
        web.setBackgroundColor(color);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(dark ? 0 : mask, mask);
                return;
            }
        }
        View d = getWindow().getDecorView();
        int f = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        d.setSystemUiVisibility(dark ? (d.getSystemUiVisibility() & ~f) : (d.getSystemUiVisibility() | f));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("window.onBack?onBack():false", value -> {
            if (!"true".equals(value)) MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        web.destroy();
        super.onDestroy();
    }

    /** Called from JavaScript as window.Android.* */
    private class Bridge {
        @JavascriptInterface
        public void takePhoto() {
            runOnUiThread(MainActivity.this::takePhoto);
        }

        @JavascriptInterface
        public void pickPhoto() {
            runOnUiThread(MainActivity.this::pickPhoto);
        }

        @JavascriptInterface
        public boolean canClassify() {
            return Classifier.available(MainActivity.this);
        }

        @JavascriptInterface
        public void classify(String photoId) {
            MainActivity.this.classify(photoId);
        }

        @JavascriptInterface
        public void locate() {
            runOnUiThread(MainActivity.this::locate);
        }

        @JavascriptInterface
        public String readFinds() {
            String s = Files.readText(new File(getFilesDir(), "finds.json"));
            return s == null ? "[]" : s;
        }

        @JavascriptInterface
        public boolean writeFinds(String json) {
            return Files.writeText(new File(getFilesDir(), "finds.json"), json);
        }

        /** Deletes stored photos that no saved find uses (keep = JSON array of ids). */
        @JavascriptInterface
        public void cleanPhotos(String keepJson) {
            worker.execute(() -> {
                try {
                    Set<String> keep = new HashSet<>();
                    JSONArray a = new JSONArray(keepJson);
                    for (int i = 0; i < a.length(); i++) keep.add(a.getString(i));
                    File[] files = Files.photoDir(MainActivity.this).listFiles();
                    if (files == null) return;
                    long hour = System.currentTimeMillis() - 60 * 60 * 1000;
                    for (File f : files) {
                        String id = f.getName().replace("_t.jpg", "").replace(".jpg", "");
                        // photos from the last hour may still be on the identify screen
                        if (!keep.contains(id) && f.lastModified() < hour) f.delete();
                    }
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public void share(String text, String title) {
            runOnUiThread(() -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_SUBJECT, title);
                send.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(send, getString(R.string.share_title)));
            });
        }

        /** Shares text as a file, e.g. finds as GPX or CSV. */
        @JavascriptInterface
        public void shareFile(String name, String mime, String content) {
            runOnUiThread(() -> {
                try {
                    File dir = new File(getCacheDir(), "share");
                    if (!dir.isDirectory()) dir.mkdirs();
                    File f = new File(dir, name);
                    Files.writeText(f, content);
                    Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", f);
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType(mime);
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.putExtra(Intent.EXTRA_SUBJECT, name);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, getString(R.string.share_title)));
                } catch (Exception ignored) { }
            });
        }

        /** Shares a find: text plus its photo, if any. */
        @JavascriptInterface
        public void shareFind(String text, String photoId) {
            runOnUiThread(() -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.putExtra(Intent.EXTRA_TEXT, text);
                send.setType("text/plain");
                try {
                    File src = new File(Files.photoDir(MainActivity.this), photoId + ".jpg");
                    if (photoId != null && !photoId.isEmpty() && src.exists()) {
                        File f = Files.copyToShare(MainActivity.this, src, "svampfynd.jpg");
                        send.setType("image/jpeg");
                        send.putExtra(Intent.EXTRA_STREAM,
                                FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", f));
                        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    }
                } catch (Exception ignored) { }
                startActivity(Intent.createChooser(send, getString(R.string.share_title)));
            });
        }

        @JavascriptInterface
        public void openUrl(String url) {
            runOnUiThread(() -> openExternal(Uri.parse(url)));
        }

        @JavascriptInterface
        public String version() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public boolean isSystemDark() {
            return systemDark();
        }

        @JavascriptInterface
        public void setDark(boolean dark) {
            runOnUiThread(() -> applyBars(dark));
        }
    }
}
