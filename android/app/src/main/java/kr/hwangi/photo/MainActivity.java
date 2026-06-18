package kr.hwangi.photo;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.webkit.WebViewAssetLoader;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final String APP_URL = "https://appassets.androidplatform.net/assets/index.html";
    private WebView web;
    private WebViewAssetLoader assetLoader;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        assetLoader = new WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();
        web.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request){
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
        });
        web.setWebChromeClient(new WebChromeClient(){
            @Override public void onPermissionRequest(final PermissionRequest req){
                runOnUiThread(new Runnable(){ public void run(){
                    if (webPermsOk(req)) req.grant(req.getResources());
                    else { pendingReq = req; askPerms(); }
                }});
            }
            // 갤러리에서 가져오기: WebView <input type=file> → 안드로이드 파일 선택창
            @Override public boolean onShowFileChooser(WebView webView,
                    ValueCallback<Uri[]> cb, FileChooserParams params){
                if (filePathCallback != null){ filePathCallback.onReceiveValue(null); }
                filePathCallback = cb;
                boolean wantCamera = false;
                try{ wantCamera = params.isCaptureEnabled(); }catch(Exception e){}
                if (wantCamera && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED){
                    try{
                        ContentValues cv = new ContentValues();
                        cv.put(MediaStore.Images.Media.DISPLAY_NAME, "cap_" + System.currentTimeMillis() + ".jpg");
                        cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                        captureUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                        android.content.Intent cam = new android.content.Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        cam.putExtra(MediaStore.EXTRA_OUTPUT, captureUri);
                        cam.addFlags(android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        if (captureUri != null && cam.resolveActivity(getPackageManager()) != null){
                            startActivityForResult(cam, 1002);
                            return true;
                        }
                    }catch(Exception e){ captureUri = null; }
                }
                try{
                    android.content.Intent intent = params.createIntent();
                    intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivityForResult(android.content.Intent.createChooser(intent, "사진 선택"), 1001);
                }catch(Exception e){
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback){
                callback.invoke(origin, true, false);
            }
        });
        web.addJavascriptInterface(new Bridge(), "HwangiBridge");
        setContentView(web);
        askPerms();
        web.loadUrl(APP_URL);
    }

    private PermissionRequest pendingReq;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri captureUri;
    private boolean camLooping = false;

    private boolean webPermsOk(PermissionRequest req){
        for (String r : req.getResources()){
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)
             && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return false;
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)
             && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private void askPerms(){
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
         || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
         || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res){
        super.onRequestPermissionsResult(code, perms, res);
        if (pendingReq != null){
            final PermissionRequest req = pendingReq; pendingReq = null;
            runOnUiThread(new Runnable(){ public void run(){
                try{ if (webPermsOk(req)) req.grant(req.getResources()); else req.deny(); }catch(Exception e){}
            }});
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data){
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1003){
            if (resultCode == Activity.RESULT_OK && captureUri != null){
                Uri u = captureUri; captureUri = null;
                deliverPhoto(u);
                if (camLooping){ web.postDelayed(new Runnable(){ public void run(){ if (camLooping) launchCam(); } }, 600); }
            } else {
                camLooping = false;
                if (captureUri != null){ try{ getContentResolver().delete(captureUri, null, null); }catch(Exception e){} captureUri = null; }
            }
            return;
        }
        if (requestCode == 1002){
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && captureUri != null){
                results = new Uri[]{ captureUri };
            } else if (captureUri != null){
                try{ getContentResolver().delete(captureUri, null, null); }catch(Exception e){}
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
            captureUri = null;
            return;
        }
        if (requestCode == 1001){
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null){
                if (data.getClipData() != null){
                    int n = data.getClipData().getItemCount();
                    results = new Uri[n];
                    for (int i = 0; i < n; i++) results[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null){
                    results = new Uri[]{ data.getData() };
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    private void launchCam(){
        try{
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, "cap_" + System.currentTimeMillis() + ".jpg");
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            captureUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            android.content.Intent cam = new android.content.Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            cam.putExtra(MediaStore.EXTRA_OUTPUT, captureUri);
            cam.addFlags(android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (captureUri != null && cam.resolveActivity(getPackageManager()) != null){
                startActivityForResult(cam, 1003);
            } else { camLooping = false; }
        }catch(Exception e){ camLooping = false; captureUri = null; }
    }
    private void deliverPhoto(Uri u){
        try{
            java.io.InputStream is = getContentResolver().openInputStream(u);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536]; int r;
            while((r = is.read(buf)) > 0) bos.write(buf, 0, r);
            is.close();
            byte[] data = bos.toByteArray();
            try{ getContentResolver().delete(u, null, null); }catch(Exception e){}
            final String b64 = Base64.encodeToString(data, Base64.NO_WRAP);
            final int CH = 1000000;
            web.evaluateJavascript("window.__camStart&&window.__camStart();", null);
            for(int p = 0; p < b64.length(); p += CH){
                String part = b64.substring(p, Math.min(p + CH, b64.length()));
                web.evaluateJavascript("window.__camChunk&&window.__camChunk('" + part + "');", null);
            }
            web.evaluateJavascript("window.__camDone&&window.__camDone();", null);
        }catch(Exception e){}
    }
    private boolean isShutterKey(int code){
        return code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
            || code == KeyEvent.KEYCODE_CAMERA || code == KeyEvent.KEYCODE_HEADSETHOOK
            || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER
            || code == KeyEvent.KEYCODE_SPACE;
    }

    @Override public boolean onKeyDown(int code, KeyEvent ev){
        if (isShutterKey(code)){
            if (ev.getRepeatCount() == 0)
                web.evaluateJavascript("window.__btShutter&&window.__btShutter();", null);
            return true;
        }
        return super.onKeyDown(code, ev);
    }
    @Override public boolean onKeyUp(int code, KeyEvent ev){
        if (isShutterKey(code)) return true;
        return super.onKeyUp(code, ev);
    }

    @Override public void onBackPressed(){
        web.evaluateJavascript("window.__btBack?window.__btBack():'exit'", new ValueCallback<String>(){
            public void onReceiveValue(String v){
                if (v != null && v.contains("exit")) moveTaskToBack(true);
            }
        });
    }

    class Bridge {
        private OutputStream os;
        private Uri lastUri;
        @JavascriptInterface public void start(String name){
            try{
                ContentResolver cr = getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                lastUri = uri;
                os = cr.openOutputStream(uri);
            }catch(Exception e){ os = null; }
        }
        @JavascriptInterface public void share(){
            runOnUiThread(new Runnable(){ public void run(){
                try{
                    if (lastUri == null) return;
                    android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_SEND);
                    i.setType("application/zip");
                    i.putExtra(android.content.Intent.EXTRA_STREAM, lastUri);
                    i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(android.content.Intent.createChooser(i, "ZIP 공유"));
                }catch(Exception e){}
            }});
        }
        @JavascriptInterface public void openSettings(){
            runOnUiThread(new Runnable(){ public void run(){
                try{
                    android.content.Intent i = new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                }catch(Exception e){}
            }});
        }
        @JavascriptInterface public void phoneCamLoop(){
            runOnUiThread(new Runnable(){ public void run(){ camLooping = true; launchCam(); } });
        }
        private volatile boolean micRun = false;
        @JavascriptInterface public void startMic(){
            if (micRun) return;
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED){ askPerms(); return; }
            micRun = true;
            new Thread(new Runnable(){ public void run(){
                android.media.AudioRecord rec = null;
                try{
                    int sr = 16000;
                    int minB = android.media.AudioRecord.getMinBufferSize(sr,
                        android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT);
                    if (minB < 3200) minB = 3200;
                    rec = new android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC,
                        sr, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, minB);
                    rec.startRecording();
                    short[] data = new short[800];
                    long lastFire = 0;
                    while (micRun){
                        int n = rec.read(data, 0, data.length);
                        int peak = 0;
                        for (int i = 0; i < n; i++){ int v = data[i]; if (v < 0) v = -v; if (v > peak) peak = v; }
                        long now = System.currentTimeMillis();
                        if (peak > 13000 && now - lastFire > 1500){
                            lastFire = now;
                            runOnUiThread(new Runnable(){ public void run(){
                                web.evaluateJavascript("window.__sndTrigger&&window.__sndTrigger();", null);
                            }});
                        }
                    }
                }catch(Exception e){}
                try{ if (rec != null){ rec.stop(); rec.release(); } }catch(Exception e){}
            }}).start();
        }
        @JavascriptInterface public void stopMic(){ micRun = false; }
        @JavascriptInterface public void append(String b64){
            try{ if(os != null) os.write(Base64.decode(b64, Base64.DEFAULT)); }catch(Exception e){}
        }
        @JavascriptInterface public void finish(){
            try{ if(os != null) os.close(); }catch(Exception e){}
            os = null;
            runOnUiThread(new Runnable(){ public void run(){
                Toast.makeText(MainActivity.this, "다운로드 폴더에 저장 완료", Toast.LENGTH_LONG).show();
            }});
        }
    }
}
