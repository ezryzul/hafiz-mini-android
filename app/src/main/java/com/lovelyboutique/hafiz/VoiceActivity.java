package com.lovelyboutique.hafiz;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.*;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VoiceActivity extends Activity {
    private static final int REQ_MIC=501;
    private static final String API="https://accounting.lovelyboutique.com.my/api/hafiz-mobile/v1";
    private static final String OPENAI_CALLS="https://api.openai.com/v1/realtime/calls";

    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private String mobileToken="";
    private String sessionId="";
    private String model="";
    private boolean starting=false, ended=false;

    private LinearLayout root;
    private TextView tvState,tvHint,tvError,tvCost,tvRemain,tvMeta,tvTranscript;
    private Button btnStart,btnStop;
    private ProgressBar progress;

    private PeerConnectionFactory factory;
    private PeerConnection peer;
    private DataChannel dataChannel;
    private AudioSource audioSource;
    private AudioTrack localAudioTrack;
    private JavaAudioDeviceModule audioDeviceModule;
    private AudioManager audioManager;
    private int oldAudioMode=AudioManager.MODE_NORMAL;
    private boolean oldSpeaker=false;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        prefs=getSharedPreferences("hafiz_mobile",MODE_PRIVATE);
        mobileToken=prefs.getString("token","");
        if(mobileToken.isEmpty()){
            Toast.makeText(this,"Login Hafiz dahulu.",Toast.LENGTH_LONG).show();
            finish();return;
        }
        buildUi();
        configureAudioRoute();
        if(android.os.Build.VERSION.SDK_INT>=23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQ_MIC);
        }else ready();
    }

    private void buildUi(){
        ScrollView scroll=new ScrollView(this);
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(18),dp(18),dp(28));root.setBackgroundColor(Color.rgb(255,248,251));
        scroll.addView(root,new ScrollView.LayoutParams(-1,-2));

        TextView title=text("Hafiz",30,true,Color.rgb(67,35,58));root.addView(title);
        root.addView(text("Account Manager • Native Voice V5.5",14,false,Color.rgb(128,106,120)));

        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(16),dp(14),dp(16),dp(14));card.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.setMargins(0,dp(18),0,0);root.addView(card,cp);
        card.addView(text("HAFIZ VOICE USAGE",12,true,Color.rgb(139,45,97)));
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);card.addView(row);
        tvCost=text("RM0.00 / RM30.00",23,true,Color.rgb(52,35,49));row.addView(tvCost,new LinearLayout.LayoutParams(0,-2,1));
        tvRemain=text("Baki RM30.00",14,true,Color.rgb(38,115,77));row.addView(tvRemain);
        tvMeta=text("Native Android mic • bukan WebView",11,false,Color.rgb(128,106,120));card.addView(tvMeta);

        LinearLayout voiceCard=new LinearLayout(this);voiceCard.setOrientation(LinearLayout.VERTICAL);voiceCard.setGravity(Gravity.CENTER_HORIZONTAL);voiceCard.setPadding(dp(18),dp(30),dp(18),dp(24));voiceCard.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(-1,-2);vp.setMargins(0,dp(16),0,0);root.addView(voiceCard,vp);

        TextView mic=text("🎙",76,false,Color.rgb(139,45,97));mic.setGravity(Gravity.CENTER);voiceCard.addView(mic);
        tvState=text("Bersedia",25,true,Color.rgb(52,35,49));tvState.setGravity(Gravity.CENTER);voiceCard.addView(tvState);
        tvHint=text("V5.5 guna microphone native Android terus. Tekan Mula Cakap.",14,false,Color.rgb(128,106,120));tvHint.setGravity(Gravity.CENTER);tvHint.setPadding(0,dp(8),0,dp(12));voiceCard.addView(tvHint);

        progress=new ProgressBar(this);progress.setVisibility(View.GONE);voiceCard.addView(progress);

        LinearLayout buttons=new LinearLayout(this);buttons.setOrientation(LinearLayout.HORIZONTAL);buttons.setPadding(0,dp(12),0,0);voiceCard.addView(buttons,new LinearLayout.LayoutParams(-1,-2));
        btnStart=new Button(this);btnStart.setText("🎙 MULA CAKAP");btnStart.setAllCaps(false);buttons.addView(btnStart,new LinearLayout.LayoutParams(0,dp(58),1));
        btnStop=new Button(this);btnStop.setText("Tamat");btnStop.setAllCaps(false);btnStop.setEnabled(false);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,dp(58),0.45f);sp.setMargins(dp(8),0,0,0);buttons.addView(btnStop,sp);

        tvError=text("",13,false,Color.rgb(157,48,66));tvError.setPadding(dp(12),dp(12),dp(12),dp(12));tvError.setVisibility(View.GONE);voiceCard.addView(tvError,new LinearLayout.LayoutParams(-1,-2));

        root.addView(text("PERBUALAN",12,true,Color.rgb(139,45,97)),new LinearLayout.LayoutParams(-1,-2));
        tvTranscript=text("Belum ada perbualan.",14,false,Color.rgb(62,49,64));tvTranscript.setPadding(0,dp(8),0,0);tvTranscript.setMovementMethod(new ScrollingMovementMethod());root.addView(tvTranscript);

        btnStart.setOnClickListener(v->startVoice());
        btnStop.setOnClickListener(v->stopVoice(false));
        setContentView(scroll);
        refreshUsage();
    }

    private TextView text(String s,float size,boolean bold,int color){
        TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);if(bold)v.setTypeface(null,android.graphics.Typeface.BOLD);return v;
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}

    private void configureAudioRoute(){
        audioManager=(AudioManager)getSystemService(Context.AUDIO_SERVICE);
        if(audioManager!=null){
            oldAudioMode=audioManager.getMode();
            oldSpeaker=audioManager.isSpeakerphoneOn();
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(true);
        }
    }
    private void restoreAudioRoute(){
        if(audioManager!=null){
            try{audioManager.setMode(oldAudioMode);audioManager.setSpeakerphoneOn(oldSpeaker);}catch(Exception ignored){}
        }
    }

    private void ready(){
        tvState.setText("Bersedia");
        tvHint.setText("Tekan Mula Cakap. Mic sekarang menggunakan native Android WebRTC, bukan browser/WebView.");
        btnStart.setEnabled(true);
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if(requestCode==REQ_MIC){
            if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)ready();
            else{showError("Microphone permission diperlukan.");btnStart.setEnabled(false);}
        }
    }

    private void setBusy(String state,String hint){
        runOnUiThread(()->{tvState.setText(state);tvHint.setText(hint);progress.setVisibility(View.VISIBLE);btnStart.setEnabled(false);tvError.setVisibility(View.GONE);});
    }
    private void liveState(){
        runOnUiThread(()->{tvState.setText("Saya dengar, Syam…");tvHint.setText("Terus bercakap. Hafiz akan jawab dengan suara.");progress.setVisibility(View.GONE);btnStart.setEnabled(false);btnStop.setEnabled(true);});
    }
    private void showError(String msg){
        runOnUiThread(()->{progress.setVisibility(View.GONE);tvState.setText("Sambungan terhenti");tvHint.setText("Native Voice tidak menggunakan WebView microphone.");tvError.setText(msg);tvError.setVisibility(View.VISIBLE);btnStart.setText("SAMBUNG SEMULA");btnStart.setEnabled(true);btnStop.setEnabled(false);});
    }

    private void startVoice(){
        if(starting||peer!=null)return;
        if(android.os.Build.VERSION.SDK_INT>=23&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQ_MIC);return;
        }
        starting=true;ended=false;setBusy("Menyambung…","Menyediakan native audio + OpenAI Realtime.");
        io.submit(()->{
            try{
                JSONObject s=apiJson("POST","/voice/session",new JSONObject());
                sessionId=s.optString("session_id","");
                model=s.optString("model","");
                String secret=s.optString("client_secret","");
                if(secret.isEmpty())throw new Exception("OpenAI Voice belum dikonfigurasi pada backend.");
                renderUsage(s.optJSONObject("usage"));
                initWebRtc();
                createAndSendOffer(secret);
            }catch(Exception e){
                cleanupRtc();
                showError(e.getMessage()==null?"Voice gagal disambungkan.":e.getMessage());
            }finally{starting=false;}
        });
    }

    private void initWebRtc() throws Exception{
        runOnUiThread(()->tvHint.setText("Membuka native microphone Android…"));
        PeerConnectionFactory.InitializationOptions init=PeerConnectionFactory.InitializationOptions.builder(getApplicationContext()).createInitializationOptions();
        PeerConnectionFactory.initialize(init);

        audioDeviceModule=JavaAudioDeviceModule.builder(getApplicationContext())
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .setAudioRecordErrorCallback(new JavaAudioDeviceModule.AudioRecordErrorCallback(){
                    @Override public void onWebRtcAudioRecordInitError(String e){showError("Native mic init error: "+e);}
                    @Override public void onWebRtcAudioRecordStartError(JavaAudioDeviceModule.AudioRecordStartErrorCode c,String e){showError("Native mic start error: "+e);}
                    @Override public void onWebRtcAudioRecordError(String e){showError("Native mic error: "+e);}
                })
                .setAudioTrackErrorCallback(new JavaAudioDeviceModule.AudioTrackErrorCallback(){
                    @Override public void onWebRtcAudioTrackInitError(String e){showError("Speaker init error: "+e);}
                    @Override public void onWebRtcAudioTrackStartError(JavaAudioDeviceModule.AudioTrackStartErrorCode c,String e){showError("Speaker start error: "+e);}
                    @Override public void onWebRtcAudioTrackError(String e){showError("Speaker error: "+e);}
                })
                .createAudioDeviceModule();

        factory=PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioDeviceModule)
                .createPeerConnectionFactory();

        PeerConnection.RTCConfiguration cfg=new PeerConnection.RTCConfiguration(new ArrayList<>());
        cfg.sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN;
        cfg.continualGatheringPolicy=PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;

        peer=factory.createPeerConnection(cfg,new PeerConnection.Observer(){
            @Override public void onSignalingChange(PeerConnection.SignalingState s){}
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s){
                if(s==PeerConnection.IceConnectionState.FAILED)showError("WebRTC ICE connection gagal.");
            }
            @Override public void onStandardizedIceConnectionChange(PeerConnection.IceConnectionState s){}
            @Override public void onConnectionChange(PeerConnection.PeerConnectionState s){
                if(s==PeerConnection.PeerConnectionState.CONNECTED)liveState();
                if((s==PeerConnection.PeerConnectionState.FAILED||s==PeerConnection.PeerConnectionState.DISCONNECTED)&&!ended)showError("Native WebRTC terputus: "+s);
            }
            @Override public void onIceConnectionReceivingChange(boolean b){}
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s){}
            @Override public void onIceCandidate(IceCandidate c){}
            @Override public void onIceCandidatesRemoved(IceCandidate[] c){}
            @Override public void onAddStream(MediaStream s){}
            @Override public void onRemoveStream(MediaStream s){}
            @Override public void onDataChannel(DataChannel dc){}
            @Override public void onRenegotiationNeeded(){}
            @Override public void onAddTrack(RtpReceiver receiver,MediaStream[] streams){
                MediaStreamTrack t=receiver.track();
                if(t instanceof AudioTrack)((AudioTrack)t).setEnabled(true);
            }
            @Override public void onTrack(RtpTransceiver transceiver){
                MediaStreamTrack t=transceiver.getReceiver().track();
                if(t instanceof AudioTrack)((AudioTrack)t).setEnabled(true);
            }
        });
        if(peer==null)throw new Exception("PeerConnection native gagal dibuat.");

        MediaConstraints ac=new MediaConstraints();
        ac.optional.add(new MediaConstraints.KeyValuePair("googEchoCancellation","true"));
        ac.optional.add(new MediaConstraints.KeyValuePair("googNoiseSuppression","true"));
        audioSource=factory.createAudioSource(ac);
        localAudioTrack=factory.createAudioTrack("hafiz-mic",audioSource);
        localAudioTrack.setEnabled(true);
        List<String> streamIds=new ArrayList<>();streamIds.add("hafiz-stream");
        RtpSender sender=peer.addTrack(localAudioTrack,streamIds);
        if(sender==null)throw new Exception("Native microphone track gagal ditambah.");

        DataChannel.Init di=new DataChannel.Init();di.ordered=true;
        dataChannel=peer.createDataChannel("oai-events",di);
        dataChannel.registerObserver(new DataChannel.Observer(){
            @Override public void onBufferedAmountChange(long previousAmount){}
            @Override public void onStateChange(){
                if(dataChannel.state()==DataChannel.State.OPEN)liveState();
            }
            @Override public void onMessage(DataChannel.Buffer buffer){
                if(buffer.binary)return;
                ByteBuffer bb=buffer.data;byte[] bytes=new byte[bb.remaining()];bb.get(bytes);
                handleRealtimeEvent(new String(bytes,StandardCharsets.UTF_8));
            }
        });
    }

    private void createAndSendOffer(String secret) throws Exception{
        final Object lock=new Object();
        final SessionDescription[] offerOut=new SessionDescription[1];
        final Exception[] err=new Exception[1];
        MediaConstraints mc=new MediaConstraints();
        peer.createOffer(new SimpleSdpObserver(){
            @Override public void onCreateSuccess(SessionDescription sdp){
                peer.setLocalDescription(new SimpleSdpObserver(){
                    @Override public void onSetSuccess(){synchronized(lock){offerOut[0]=sdp;lock.notifyAll();}}
                    @Override public void onSetFailure(String e){synchronized(lock){err[0]=new Exception(e);lock.notifyAll();}}
                },sdp);
            }
            @Override public void onCreateFailure(String e){synchronized(lock){err[0]=new Exception(e);lock.notifyAll();}}
        },mc);
        synchronized(lock){if(offerOut[0]==null&&err[0]==null)lock.wait(15000);}
        if(err[0]!=null)throw err[0];
        if(offerOut[0]==null)throw new Exception("Timeout create WebRTC offer.");

        String answerSdp=postSdp(offerOut[0].description,secret);
        final Object lock2=new Object();final Exception[] err2=new Exception[1];final boolean[] ok={false};
        peer.setRemoteDescription(new SimpleSdpObserver(){
            @Override public void onSetSuccess(){synchronized(lock2){ok[0]=true;lock2.notifyAll();}}
            @Override public void onSetFailure(String e){synchronized(lock2){err2[0]=new Exception(e);lock2.notifyAll();}}
        },new SessionDescription(SessionDescription.Type.ANSWER,answerSdp));
        synchronized(lock2){if(!ok[0]&&err2[0]==null)lock2.wait(15000);}
        if(err2[0]!=null)throw err2[0];
        if(!ok[0])throw new Exception("Timeout set OpenAI WebRTC answer.");
    }

    private String postSdp(String sdp,String secret) throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(OPENAI_CALLS).openConnection();
        c.setRequestMethod("POST");c.setConnectTimeout(20000);c.setReadTimeout(30000);c.setDoOutput(true);
        c.setRequestProperty("Authorization","Bearer "+secret);
        c.setRequestProperty("Content-Type","application/sdp");
        c.setRequestProperty("Accept","application/sdp");
        try(OutputStream os=c.getOutputStream()){os.write(sdp.getBytes(StandardCharsets.UTF_8));}
        int code=c.getResponseCode();
        String body=readAll(code>=400?c.getErrorStream():c.getInputStream());
        if(code>=400)throw new Exception("OpenAI Realtime gagal ("+code+"): "+body);
        return body;
    }

    private void handleRealtimeEvent(String raw){
        try{
            JSONObject evt=new JSONObject(raw);String type=evt.optString("type","");
            if(type.equals("input_audio_buffer.speech_started"))runOnUiThread(()->{tvState.setText("Saya dengar, Syam…");tvHint.setText("Terus bercakap.");});
            else if(type.equals("input_audio_buffer.speech_stopped"))runOnUiThread(()->{tvState.setText("Hafiz sedang fikir…");tvHint.setText("Sedang memahami arahan.");});
            else if(type.equals("conversation.item.input_audio_transcription.completed"))appendTranscript("Syam",evt.optString("transcript",""));
            else if(type.equals("response.output_audio_transcript.done"))appendTranscript("Hafiz",evt.optString("transcript",""));
            else if(type.equals("response.done"))handleResponseDone(evt.optJSONObject("response"));
            else if(type.equals("error")){
                JSONObject er=evt.optJSONObject("error");showError(er!=null?er.optString("message","Ralat Realtime"):"Ralat Realtime");
            }
        }catch(Exception ignored){}
    }

    private void handleResponseDone(JSONObject response){
        if(response==null)return;
        JSONObject usage=response.optJSONObject("usage");
        String responseId=response.optString("id","");
        if(usage!=null&&!responseId.isEmpty())io.submit(()->{
            try{
                JSONObject b=new JSONObject();b.put("session_id",sessionId);b.put("response_id",responseId);b.put("model",model);b.put("usage",usage);
                JSONObject r=apiJson("POST","/voice/usage",b);renderUsage(r.optJSONObject("usage"));
            }catch(Exception ignored){}
        });

        JSONArray output=response.optJSONArray("output");
        if(output!=null)for(int i=0;i<output.length();i++){
            JSONObject item=output.optJSONObject(i);
            if(item!=null&&"function_call".equals(item.optString("type")))dispatchTool(item);
        }
        runOnUiThread(()->{tvState.setText("Saya dengar, Syam…");tvHint.setText("Cakap arahan seterusnya bila-bila masa.");});
    }

    private void dispatchTool(JSONObject item){
        final String callId=item.optString("call_id",""),name=item.optString("name","");
        if(callId.isEmpty()||name.isEmpty())return;
        io.submit(()->{
            String output;
            try{
                JSONObject args;try{args=new JSONObject(item.optString("arguments","{}"));}catch(Exception e){args=new JSONObject();}
                JSONObject b=new JSONObject();b.put("name",name);b.put("arguments",args);
                JSONObject r=apiJson("POST","/voice/tool",b);output=(r.optBoolean("ok")?r.opt("result"):new JSONObject().put("error",r.optString("detail","Tool gagal"))).toString();
            }catch(Exception e){output="{\"error\":\""+escape(e.getMessage())+"\"}";}
            try{
                JSONObject ev=new JSONObject();ev.put("type","conversation.item.create");
                JSONObject it=new JSONObject();it.put("type","function_call_output");it.put("call_id",callId);it.put("output",output);ev.put("item",it);
                sendEvent(ev);sendEvent(new JSONObject().put("type","response.create"));
            }catch(Exception ignored){}
        });
    }

    private String escape(String s){return String.valueOf(s).replace("\\","\\\\").replace(""","\\"");}
    private void sendEvent(JSONObject j){
        if(dataChannel==null||dataChannel.state()!=DataChannel.State.OPEN)return;
        ByteBuffer bb=ByteBuffer.wrap(j.toString().getBytes(StandardCharsets.UTF_8));
        dataChannel.send(new DataChannel.Buffer(bb,false));
    }
    private void appendTranscript(String who,String s){
        if(s==null||s.trim().isEmpty())return;
        runOnUiThread(()->{
            String old=tvTranscript.getText().toString();
            if(old.equals("Belum ada perbualan."))old="";
            tvTranscript.setText(who+": "+s.trim()+"\n\n"+old);
        });
    }

    private void renderUsage(JSONObject u){
        if(u==null)return;
        double spend=u.optDouble("effective_spend_myr",0),limit=u.optDouble("monthly_limit_myr",30),remain=u.optDouble("remaining_myr",Math.max(0,limit-spend));
        runOnUiThread(()->{tvCost.setText(String.format(Locale.US,"RM%.2f / RM%.2f",spend,limit));tvRemain.setText(String.format(Locale.US,"Baki RM%.2f",remain));tvMeta.setText(String.format(Locale.US,"Native Android mic • Live estimate RM%.2f",u.optDouble("estimated_live_myr",0)));});
    }
    private void refreshUsage(){io.submit(()->{try{renderUsage(apiJson("GET","/usage",null));}catch(Exception ignored){}});}

    private JSONObject apiJson(String method,String path,JSONObject body)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(API+path).openConnection();
        c.setRequestMethod(method);c.setConnectTimeout(20000);c.setReadTimeout(30000);c.setRequestProperty("Accept","application/json");c.setRequestProperty("Authorization","Bearer "+mobileToken);
        if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");try(OutputStream os=c.getOutputStream()){os.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
        int code=c.getResponseCode();String txt=readAll(code>=400?c.getErrorStream():c.getInputStream());JSONObject r=txt.trim().isEmpty()?new JSONObject():new JSONObject(txt);if(code>=400)throw new Exception(r.optString("detail","HTTP "+code));return r;
    }
    private String readAll(InputStream in)throws Exception{if(in==null)return "";ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))>0)b.write(buf,0,n);return b.toString("UTF-8");}

    private void stopVoice(boolean silent){
        ended=true;
        final String sid=sessionId;sessionId="";
        if(!sid.isEmpty())io.submit(()->{try{JSONObject b=new JSONObject().put("session_id",sid);apiJson("POST","/voice/end",b);}catch(Exception ignored){}});
        cleanupRtc();
        if(!silent)runOnUiThread(()->{tvState.setText("Sesi tamat");tvHint.setText("Tekan Mula Cakap untuk sesi baru.");btnStart.setText("🎙 MULA CAKAP");btnStart.setEnabled(true);btnStop.setEnabled(false);progress.setVisibility(View.GONE);});
    }

    private void cleanupRtc(){
        try{if(dataChannel!=null){dataChannel.unregisterObserver();dataChannel.close();dataChannel.dispose();}}catch(Exception ignored){}dataChannel=null;
        try{if(peer!=null){peer.close();peer.dispose();}}catch(Exception ignored){}peer=null;
        try{if(localAudioTrack!=null)localAudioTrack.dispose();}catch(Exception ignored){}localAudioTrack=null;
        try{if(audioSource!=null)audioSource.dispose();}catch(Exception ignored){}audioSource=null;
        try{if(factory!=null)factory.dispose();}catch(Exception ignored){}factory=null;
        try{if(audioDeviceModule!=null)audioDeviceModule.release();}catch(Exception ignored){}audioDeviceModule=null;
    }

    @Override public void onBackPressed(){stopVoice(true);super.onBackPressed();}
    @Override protected void onDestroy(){stopVoice(true);restoreAudioRoute();io.shutdownNow();super.onDestroy();}

    private abstract static class SimpleSdpObserver implements SdpObserver{
        @Override public void onCreateSuccess(SessionDescription sdp){}
        @Override public void onSetSuccess(){}
        @Override public void onCreateFailure(String error){}
        @Override public void onSetFailure(String error){}
    }
}
