package com.lovelyboutique.hafiz;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.View;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class VoiceActivity extends Activity {
    private static final int REQ_MIC=501;
    private static final String VOICE_ORIGIN="https://accounting.lovelyboutique.com.my/";
    private WebView web;
    private String token="";

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        SharedPreferences p=getSharedPreferences("hafiz_mobile",MODE_PRIVATE);
        token=p.getString("token","");
        if(token.isEmpty()){
            Toast.makeText(this,"Login Hafiz dahulu.",Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if(android.os.Build.VERSION.SDK_INT>=23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQ_MIC);
        }else initWeb();
    }

    private String readAsset(String name) throws Exception{
        try(InputStream in=getAssets().open(name); ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[8192];
            int n;
            while((n=in.read(buf))>0) out.write(buf,0,n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void initWeb(){
        web=new WebView(this);
        web.setLayerType(View.LAYER_TYPE_HARDWARE,null);
        setContentView(web);
        WebSettings s=web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        web.addJavascriptInterface(new Bridge(),"HafizApp");
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req){
                // The Voice screen is bundled inside the APK. Never navigate to the Lovely Accounts
                // web login page or any external page from this activity.
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient(){
            @Override public void onPermissionRequest(final PermissionRequest request){
                runOnUiThread(() -> {
                    Uri origin=request.getOrigin();
                    if(origin!=null && "https".equals(origin.getScheme()) && "accounting.lovelyboutique.com.my".equals(origin.getHost())){
                        for(String r:request.getResources()){
                            if(PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)){
                                request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                                return;
                            }
                        }
                    }
                    request.deny();
                });
            }
        });

        try{
            String html=readAsset("hafiz_voice_v5.html");
            // Load the bundled Voice UI with a secure Lovely Accounts base origin. This keeps
            // microphone/WebRTC support while avoiding any dependency on the website login page.
            web.loadDataWithBaseURL(
                    VOICE_ORIGIN+"hafiz-voice-v5-local",
                    html,
                    "text/html",
                    "UTF-8",
                    null
            );
        }catch(Exception e){
            Toast.makeText(this,"Voice screen gagal dibuka: "+e.getMessage(),Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if(requestCode==REQ_MIC){
            if(results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED)initWeb();
            else{
                Toast.makeText(this,"Microphone permission diperlukan untuk bercakap dengan Hafiz.",Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    public class Bridge {
        @JavascriptInterface public String getToken(){return token;}
        @JavascriptInterface public boolean hasMicPermission(){
            return android.os.Build.VERSION.SDK_INT<23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;
        }
        @JavascriptInterface public void closeVoice(){runOnUiThread(() -> finish());}
    }

    @Override public void onBackPressed(){
        if(web!=null) web.evaluateJavascript("try{stopVoice(false)}catch(e){}",null);
        super.onBackPressed();
    }

    @Override protected void onDestroy(){
        if(web!=null){
            web.loadUrl("about:blank");
            web.destroy();
            web=null;
        }
        super.onDestroy();
    }
}
