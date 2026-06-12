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
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final String APP_URL = "https://2025mc105-cloud.github.io/kitchen-photo/";
    private WebView web;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient(){
            @Override public void onPermissionRequest(final PermissionRequest req){
                runOnUiThread(new Runnable(){ public void run(){
                    if (webPermsOk(req)) req.grant(req.getResources());
                    else { pendingReq = req; askPerms(); }
                }});
            }
        });
        web.addJavascriptInterface(new Bridge(), "HwangiBridge");
        setContentView(web);
        askPerms();
        web.loadUrl(APP_URL);
    }

    private PermissionRequest pendingReq;

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
         || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, 1);
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
