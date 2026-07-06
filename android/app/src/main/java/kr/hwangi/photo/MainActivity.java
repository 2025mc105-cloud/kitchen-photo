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
        /* ── v47: 실시간 폰 폴더 저장 (내부저장소/0.점검사진/급식실점검사진/…) ── */
        private final java.util.concurrent.ExecutorService fsExec = java.util.concurrent.Executors.newSingleThreadExecutor();
        private java.io.OutputStream fsOs; private java.io.File fsFile;
        private int fsOk = 0, fsFail = 0;
        private String fsSanP(String s){
            String p = (s == null ? "" : s).replace("\\", "/").replace("..", "_").trim();
            while (p.startsWith("/")) p = p.substring(1);
            while (p.endsWith("/")) p = p.substring(0, p.length()-1);
            return p;
        }
        private java.io.File fsDir(String relPath){
            String p = fsSanP(relPath);
            java.io.File base = new java.io.File(Environment.getExternalStorageDirectory(), "0.점검사진/급식실점검사진");
            return p.isEmpty() ? base : new java.io.File(base, p);
        }
        @JavascriptInterface public boolean fperm(){
            try{ return Environment.isExternalStorageManager(); }catch(Exception e){ return false; }
        }
        @JavascriptInterface public void freq(){
            runOnUiThread(new Runnable(){ public void run(){
                try{
                    android.content.Intent i = new android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                }catch(Exception e){
                    try{ startActivity(new android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }catch(Exception e2){}
                }
            } });
        }
        @JavascriptInterface public void fexplore(final String relPath){
            runOnUiThread(new Runnable(){ public void run(){
                java.io.File dir = fsDir(relPath);
                try{ dir.mkdirs(); }catch(Exception e){}
                try{
                    android.content.Intent it = new android.content.Intent("samsung.myfiles.intent.action.LAUNCH_MY_FILES");
                    it.putExtra("samsung.myfiles.intent.extra.START_PATH", dir.getAbsolutePath());
                    it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(it);
                    return;
                }catch(Exception e){}
                try{
                    String doc = "primary:" + dir.getAbsolutePath().replaceFirst("^/storage/emulated/0/", "");
                    Uri u = android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", doc);
                    android.content.Intent it2 = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                    it2.setDataAndType(u, "vnd.android.document/directory");
                    it2.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(it2);
                    return;
                }catch(Exception e){}
                try{
                    android.content.Intent it3 = getPackageManager().getLaunchIntentForPackage("com.sec.android.app.myfiles");
                    if (it3 != null) startActivity(it3);
                }catch(Exception e){}
            } });
        }
        @JavascriptInterface public void fbegin(){
            fsExec.execute(new Runnable(){ public void run(){ fsOk = 0; fsFail = 0; } });
        }
        @JavascriptInterface public void fopen(final String relPath, final String name){
            fsExec.execute(new Runnable(){ public void run(){
                try{
                    if (fsOs != null){ try{ fsOs.close(); }catch(Exception e){} fsOs = null; }
                    java.io.File dir = fsDir(relPath);
                    dir.mkdirs();
                    fsFile = new java.io.File(dir, fsSanP(name).replace("/", "_"));
                    fsOs = new java.io.FileOutputStream(fsFile);
                }catch(Exception e){ fsOs = null; fsFile = null; fsFail++; }
            } });
        }
        @JavascriptInterface public void fwrite(final String b64){
            fsExec.execute(new Runnable(){ public void run(){
                try{ if (fsOs != null) fsOs.write(Base64.decode(b64, Base64.NO_WRAP)); }
                catch(Exception e){ try{ if (fsOs != null) fsOs.close(); }catch(Exception e2){} fsOs = null; fsFail++; }
            } });
        }
        @JavascriptInterface public void fclose(final String tk){
            fsExec.execute(new Runnable(){ public void run(){
                try{
                    if (fsOs != null){ fsOs.close(); fsOk++;
                        if (fsFile != null){ try{ android.media.MediaScannerConnection.scanFile(MainActivity.this, new String[]{ fsFile.getAbsolutePath() }, null, null); }catch(Exception e){} }
                    }
                }catch(Exception e){ fsFail++; }
                fsOs = null; fsFile = null;
                final String t = (tk == null ? "" : tk.replaceAll("[^A-Za-z0-9_]", ""));
                runOnUiThread(new Runnable(){ public void run(){ web.evaluateJavascript("window.__fsAck&&window.__fsAck('" + t + "');", null); } });
            } });
        }
        @JavascriptInterface public void fdelete(final String relPath, final String name){
            fsExec.execute(new Runnable(){ public void run(){
                try{
                    java.io.File f = new java.io.File(fsDir(relPath), fsSanP(name).replace("/", "_"));
                    if (f.exists()){
                        String path = f.getAbsolutePath();
                        f.delete();
                        try{ android.media.MediaScannerConnection.scanFile(MainActivity.this, new String[]{ path }, null, null); }catch(Exception e){}
                    }
                }catch(Exception e){}
            } });
        }
        @JavascriptInterface public void fnotify(final String tag){
            fsExec.execute(new Runnable(){ public void run(){
                final int ok = fsOk, fail = fsFail;
                runOnUiThread(new Runnable(){ public void run(){ web.evaluateJavascript("window.__fsDone&&window.__fsDone(" + ok + "," + fail + ");", null); } });
            } });
        }
        /* ── LocalSend 전송: 세션 폴더를 노트북으로 폴더째 (프로토콜 v2) ── */
        private volatile boolean lsCancelFlag = false;
        private javax.net.ssl.SSLSocketFactory lsFactory = null;
        private final String lsFp = java.util.UUID.randomUUID().toString();
        private javax.net.ssl.SSLSocketFactory lsSsl(){
            if (lsFactory != null) return lsFactory;
            try{
                javax.net.ssl.TrustManager[] tm = new javax.net.ssl.TrustManager[]{ new javax.net.ssl.X509TrustManager(){
                    public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a){}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a){}
                    public java.security.cert.X509Certificate[] getAcceptedIssuers(){ return new java.security.cert.X509Certificate[0]; }
                }};
                javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
                sc.init(null, tm, new java.security.SecureRandom());
                lsFactory = sc.getSocketFactory();
            }catch(Exception e){}
            return lsFactory;
        }
        private java.net.HttpURLConnection lsOpen(String proto, String ip, String path, int ct, int rt) throws Exception {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(proto+"://"+ip+":53317"+path).openConnection();
            if (c instanceof javax.net.ssl.HttpsURLConnection){
                ((javax.net.ssl.HttpsURLConnection)c).setSSLSocketFactory(lsSsl());
                ((javax.net.ssl.HttpsURLConnection)c).setHostnameVerifier(new javax.net.ssl.HostnameVerifier(){ public boolean verify(String h, javax.net.ssl.SSLSession s){ return true; } });
            }
            c.setConnectTimeout(ct); c.setReadTimeout(rt);
            return c;
        }
        private String lsInfo(){
            return "{\"alias\":\"급식실 사진앱\",\"version\":\"2.0\",\"deviceModel\":\"Samsung\",\"deviceType\":\"mobile\",\"fingerprint\":\"" + lsFp + "\",\"port\":53317,\"protocol\":\"http\",\"download\":false}";
        }
        private void lsJs(final String js){
            runOnUiThread(new Runnable(){ public void run(){ web.evaluateJavascript(js, null); } });
        }
        private String lsQ(String s){ try{ return java.net.URLEncoder.encode(s, "UTF-8"); }catch(Exception e){ return s; } }
        private String lsJsonEsc(String s){ return s.replace("\\","\\\\").replace("\"","\\\""); }
        private String lsMime(String n){
            String l = n.toLowerCase();
            if (l.endsWith(".jpg")||l.endsWith(".jpeg")) return "image/jpeg";
            if (l.endsWith(".png")) return "image/png";
            if (l.endsWith(".txt")) return "text/plain";
            if (l.endsWith(".mp4")) return "video/mp4";
            if (l.endsWith(".pdf")) return "application/pdf";
            return "application/octet-stream";
        }
        private void lsWalk(java.io.File d, java.util.ArrayList<java.io.File> out){
            java.io.File[] fs = d.listFiles(); if (fs == null) return;
            java.util.Arrays.sort(fs);
            for (java.io.File f : fs){
                if (f.getName().startsWith(".")) continue;
                if (f.isDirectory()) lsWalk(f, out); else if (f.length() > 0) out.add(f);
            }
        }
        private org.json.JSONObject lsProbe(String ip){
            try{ java.net.Socket s = new java.net.Socket(); s.connect(new java.net.InetSocketAddress(ip, 53317), 400); s.close(); }catch(Exception e){ return null; }
            for (String proto : new String[]{"https","http"}){
                java.net.HttpURLConnection c = null;
                try{
                    c = lsOpen(proto, ip, "/api/localsend/v2/register", 1500, 3000);
                    c.setDoOutput(true); c.setRequestProperty("Content-Type","application/json");
                    java.io.OutputStream os = c.getOutputStream(); os.write(lsInfo().getBytes("UTF-8")); os.close();
                    int code = c.getResponseCode();
                    if (code >= 200 && code < 300){
                        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                        java.io.InputStream in = c.getInputStream(); byte[] b = new byte[4096]; int n;
                        while((n = in.read(b)) > 0) bo.write(b, 0, n);
                        in.close();
                        org.json.JSONObject r = new org.json.JSONObject(bo.toString("UTF-8"));
                        org.json.JSONObject d = new org.json.JSONObject();
                        d.put("ip", ip); d.put("alias", r.optString("alias", ip)); d.put("proto", proto);
                        return d;
                    }
                }catch(Exception e){}
                finally{ if (c != null) try{ c.disconnect(); }catch(Exception e2){} }
            }
            return null;
        }
        @JavascriptInterface public void lsScan(final String savedIp){
            new Thread(new Runnable(){ public void run(){
                final org.json.JSONArray found = new org.json.JSONArray();
                try{
                    if (savedIp != null && savedIp.trim().length() > 0){
                        org.json.JSONObject d = lsProbe(savedIp.trim());
                        if (d != null) found.put(d);
                    }
                    if (found.length() == 0){
                        java.util.LinkedHashSet<String> ips = new java.util.LinkedHashSet<String>();
                        java.util.Enumeration<java.net.NetworkInterface> nis = java.net.NetworkInterface.getNetworkInterfaces();
                        while(nis != null && nis.hasMoreElements()){
                            java.net.NetworkInterface ni = nis.nextElement();
                            try{ if (!ni.isUp() || ni.isLoopback()) continue; }catch(Exception e){ continue; }
                            for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()){
                                java.net.InetAddress ad = ia.getAddress();
                                if (!(ad instanceof java.net.Inet4Address)) continue;
                                String h = ad.getHostAddress();
                                if (h == null || h.startsWith("127.")) continue;
                                if (!(h.startsWith("10.")||h.startsWith("192.168.")||h.startsWith("172."))) continue;
                                String base = h.substring(0, h.lastIndexOf('.'));
                                for (int i = 1; i < 255; i++) ips.add(base + "." + i);
                                ips.remove(h);
                            }
                        }
                        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(48);
                        final Object lk = new Object();
                        for (final String ip : ips){
                            pool.execute(new Runnable(){ public void run(){
                                org.json.JSONObject d = lsProbe(ip);
                                if (d != null){ synchronized(lk){ found.put(d); } }
                            }});
                        }
                        pool.shutdown();
                        pool.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS);
                    }
                }catch(Exception e){}
                lsJs("window.__lsFound&&__lsFound(" + found.toString() + ")");
            }}).start();
        }
        @JavascriptInterface public void lsList(){
            new Thread(new Runnable(){ public void run(){
                org.json.JSONArray arr = new org.json.JSONArray();
                try{
                    java.io.File base = fsDir("");
                    java.io.File[] ds = base.listFiles();
                    if (ds != null){
                        java.util.Arrays.sort(ds, new java.util.Comparator<java.io.File>(){
                            public int compare(java.io.File a, java.io.File b){ return Long.compare(b.lastModified(), a.lastModified()); }
                        });
                        for (java.io.File d : ds) if (d.isDirectory()) arr.put(d.getName());
                    }
                }catch(Exception e){}
                lsJs("window.__lsPick&&__lsPick(" + arr.toString() + ")");
            }}).start();
        }
        @JavascriptInterface public void lsCancel(){ lsCancelFlag = true; }
        @JavascriptInterface public void lsSend(final String rel, final String ip, final String proto0, final String pin){
            lsCancelFlag = false;
            new Thread(new Runnable(){ public void run(){
                int ok = 0, fail = 0; int total = 0;
                try{
                    java.io.File root = fsDir(rel);
                    java.util.ArrayList<java.io.File> files = new java.util.ArrayList<java.io.File>();
                    if (root.isDirectory()) lsWalk(root, files);
                    total = files.size();
                    if (total == 0){ lsJs("window.__lsDone&&__lsDone(0,0,'NOFILES')"); return; }
                    String proto = (proto0 != null && proto0.equals("http")) ? "http" : "https";
                    String rootName = root.getName();
                    String rootPath = root.getAbsolutePath();
                    StringBuilder fb = new StringBuilder();
                    for (int i = 0; i < total; i++){
                        java.io.File f = files.get(i);
                        String rn = rootName + f.getAbsolutePath().substring(rootPath.length()).replace('\\', '/');
                        if (i > 0) fb.append(',');
                        fb.append("\"f").append(i).append("\":{\"id\":\"f").append(i)
                          .append("\",\"fileName\":\"").append(lsJsonEsc(rn))
                          .append("\",\"size\":").append(f.length())
                          .append(",\"fileType\":\"").append(lsMime(f.getName())).append("\"}");
                    }
                    String body = "{\"info\":" + lsInfo() + ",\"files\":{" + fb + "}}";
                    String q = (pin != null && pin.length() > 0) ? ("?pin=" + lsQ(pin)) : "";
                    java.net.HttpURLConnection c;
                    int code;
                    try{
                        c = lsOpen(proto, ip, "/api/localsend/v2/prepare-upload" + q, 6000, 180000);
                        c.setDoOutput(true); c.setRequestProperty("Content-Type","application/json");
                        java.io.OutputStream os = c.getOutputStream(); os.write(body.getBytes("UTF-8")); os.close();
                        code = c.getResponseCode();
                    }catch(Exception ce){ lsJs("window.__lsDone&&__lsDone(0,0,'CONNECT')"); return; }
                    if (code == 401){ lsJs("window.__lsDone&&__lsDone(0,0,'PIN')"); return; }
                    if (code == 403){ lsJs("window.__lsDone&&__lsDone(0,0,'REJECT')"); return; }
                    if (code == 409){ lsJs("window.__lsDone&&__lsDone(0,0,'BUSY')"); return; }
                    if (code < 200 || code >= 300){ lsJs("window.__lsDone&&__lsDone(0,0,'HTTP" + code + "')"); return; }
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    java.io.InputStream in = c.getInputStream(); byte[] rb = new byte[4096]; int rn2;
                    while((rn2 = in.read(rb)) > 0) bo.write(rb, 0, rn2);
                    in.close();
                    org.json.JSONObject resp = new org.json.JSONObject(bo.toString("UTF-8"));
                    String sess = resp.getString("sessionId");
                    org.json.JSONObject toks = resp.getJSONObject("files");
                    for (int i = 0; i < total; i++){
                        if (lsCancelFlag){
                            try{ java.net.HttpURLConnection cc = lsOpen(proto, ip, "/api/localsend/v2/cancel?sessionId=" + lsQ(sess), 3000, 5000); cc.setRequestMethod("POST"); cc.setDoOutput(true); cc.getOutputStream().close(); cc.getResponseCode(); cc.disconnect(); }catch(Exception e){}
                            break;
                        }
                        java.io.File f = files.get(i);
                        String fid = "f" + i;
                        if (!toks.has(fid)) continue;
                        try{
                            java.net.HttpURLConnection uc = lsOpen(proto, ip, "/api/localsend/v2/upload?sessionId=" + lsQ(sess) + "&fileId=" + fid + "&token=" + lsQ(toks.getString(fid)), 6000, 120000);
                            uc.setDoOutput(true); uc.setRequestProperty("Content-Type","application/octet-stream");
                            uc.setFixedLengthStreamingMode(f.length());
                            java.io.OutputStream uo = uc.getOutputStream();
                            java.io.FileInputStream fi = new java.io.FileInputStream(f);
                            byte[] buf = new byte[65536]; int n;
                            while((n = fi.read(buf)) > 0) uo.write(buf, 0, n);
                            fi.close(); uo.close();
                            int ucode = uc.getResponseCode();
                            uc.disconnect();
                            if (ucode >= 200 && ucode < 300) ok++; else fail++;
                        }catch(Exception ue){ fail++; }
                        lsJs("window.__lsProg&&__lsProg(" + (i+1) + "," + total + ")");
                    }
                    lsJs("window.__lsDone&&__lsDone(" + ok + "," + fail + ",'')");
                }catch(Exception e){
                    lsJs("window.__lsDone&&__lsDone(" + ok + "," + fail + ",'ERR')");
                }
            }}).start();
        }
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
