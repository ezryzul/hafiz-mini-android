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
import android.widget.Toast;

public class VoiceActivity extends Activity {
    private static final int REQ_MIC=501;
    private static final String VOICE_URL="https://accounting.lovelyboutique.com.my/hafiz-voice-v5";
    private WebView web;
    private String token="";

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        SharedPreferences p=getSharedPreferences("hafiz_mobile",MODE_PRIVATE);
        token=p.getString("token","");
        if(token.isEmpty()){Toast.makeText(this,"Login Hafiz dahulu.",Toast.LENGTH_LONG).show();finish();return;}
        if(android.os.Build.VERSION.SDK_INT>=23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQ_MIC);
        }else initWeb();
    }

    private void initWeb(){
        web=new WebView(this);
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
                Uri u=req.getUrl();
                return !("https".equals(u.getScheme()) && "accounting.lovelyboutique.com.my".equals(u.getHost()));
            }
        });
        web.setWebChromeClient(new WebChromeClient(){
            @Override public void onPermissionRequest(final PermissionRequest request){
                runOnUiThread(() -> {
                    Uri origin=request.getOrigin();
                    if(origin!=null && "https".equals(origin.getScheme()) && "accounting.lovelyboutique.com.my".equals(origin.getHost())){
                        for(String r:request.getResources()) if(PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)){request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});return;}
                    }
                    request.deny();
                });
            }
        });
        web.loadUrl(VOICE_URL);
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if(requestCode==REQ_MIC){
            if(results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED)initWeb();
            else{Toast.makeText(this,"Microphone permission diperlukan untuk bercakap dengan Hafiz.",Toast.LENGTH_LONG).show();finish();}
        }
    }

    public class Bridge {
        @JavascriptInterface public String getToken(){return token;}
        @JavascriptInterface public void closeVoice(){runOnUiThread(() -> finish());}
    }

    @Override public void onBackPressed(){
        if(web!=null)web.evaluateJavascript("try{stopVoice(false)}catch(e){}",null);
        super.onBackPressed();
    }

    @Override protected void onDestroy(){
        if(web!=null){web.loadUrl("about:blank");web.destroy();web=null;}
        super.onDestroy();
    }
}
