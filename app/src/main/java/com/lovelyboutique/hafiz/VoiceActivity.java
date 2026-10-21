package com.lovelyboutique.hafiz;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.*;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Bundle;
import android.util.Base64;
import android.view.Gravity;
import android.widget.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.*;

public class VoiceActivity extends Activity {
    private static final int REQ_MIC=501;
    private static final String API="https://accounting.lovelyboutique.com.my/api/hafiz-mobile/v1";

    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ExecutorService captureExec=Executors.newSingleThreadExecutor();
    private final ExecutorService playbackExec=Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private String mobileToken="";
    private String sessionId="";
    private String model="";
    private String clientSecret="";
    private volatile boolean starting=false;
    private volatile boolean ended=false;

    private TextView tvState,tvHint,tvError,tvCost,tvRemain,tvMeta,tvTranscript;
    private Button btnStart,btnStop;
    private ProgressBar progress;

    private OkHttpClient wsClient;
    private WebSocket ws;
    private final AtomicBoolean recording=new AtomicBoolean(false);
    private AudioRecord audioRecord;
    private int captureRate=24000;
    private AudioTrack audioTrack;
    private AcousticEchoCanceler aec;
    private NoiseSuppressor ns;
    private AudioManager audioManager;
    private int oldMode=AudioManager.MODE_NORMAL;
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
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18),dp(18),dp(18),dp(28));
        root.setBackgroundColor(Color.rgb(255,248,251));
        scroll.addView(root,new ScrollView.LayoutParams(-1,-2));

        root.addView(text("Hafiz",30,true,Color.rgb(67,35,58)));
        root.addView(text("Account Manager • Native Audio V5.6",14,false,Color.rgb(128,106,120)));

        LinearLayout usage=new LinearLayout(this);
        usage.setOrientation(LinearLayout.VERTICAL);
        usage.setPadding(dp(16),dp(14),dp(16),dp(14));
        usage.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams up=new LinearLayout.LayoutParams(-1,-2);
        up.setMargins(0,dp(18),0,0);
        root.addView(usage,up);
        usage.addView(text("HAFIZ VOICE USAGE",12,true,Color.rgb(139,45,97)));

        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        usage.addView(row);
        tvCost=text("RM0.00 / RM30.00",23,true,Color.rgb(52,35,49));
        row.addView(tvCost,new LinearLayout.LayoutParams(0,-2,1));
        tvRemain=text("Baki RM30.00",14,true,Color.rgb(38,115,77));
        row.addView(tvRemain);
        tvMeta=text("Native AudioRecord • WebSocket • bukan WebView/WebRTC",11,false,Color.rgb(128,106,120));
        usage.addView(tvMeta);

        LinearLayout voice=new LinearLayout(this);
        voice.setOrientation(LinearLayout.VERTICAL);
        voice.setGravity(Gravity.CENTER_HORIZONTAL);
        voice.setPadding(dp(18),dp(28),dp(18),dp(24));
        voice.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(-1,-2);
        vp.setMargins(0,dp(16),0,0);
        root.addView(voice,vp);

        TextView mic=text("🎙",76,false,Color.rgb(139,45,97));
        mic.setGravity(Gravity.CENTER);
        voice.addView(mic);
        tvState=text("Bersedia",25,true,Color.rgb(52,35,49));
        tvState.setGravity(Gravity.CENTER);
        voice.addView(tvState);
        tvHint=text("Tekan Mula Cakap. V5.6 tidak lagi menggunakan microphone WebView atau native WebRTC.",14,false,Color.rgb(128,106,120));
        tvHint.setGravity(Gravity.CENTER);
        tvHint.setPadding(0,dp(8),0,dp(12));
        voice.addView(tvHint);

        progress=new ProgressBar(this);
        progress.setVisibility(android.view.View.GONE);
        voice.addView(progress);

        LinearLayout buttons=new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0,dp(12),0,0);
        voice.addView(buttons,new LinearLayout.LayoutParams(-1,-2));

        btnStart=new Button(this);
        btnStart.setText("🎙 MULA CAKAP");
        btnStart.setAllCaps(false);
        buttons.addView(btnStart,new LinearLayout.LayoutParams(0,dp(58),1));

        btnStop=new Button(this);
        btnStop.setText("Tamat");
        btnStop.setAllCaps(false);
        btnStop.setEnabled(false);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,dp(58),0.45f);
        sp.setMargins(dp(8),0,0,0);
        buttons.addView(btnStop,sp);

        tvError=text("",13,false,Color.rgb(157,48,66));
        tvError.setPadding(dp(12),dp(12),dp(12),dp(12));
        tvError.setVisibility(android.view.View.GONE);
        voice.addView(tvError,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-1,-2);
        tp.setMargins(0,dp(18),0,0);
        root.addView(text("PERBUALAN",12,true,Color.rgb(139,45,97)),tp);
        tvTranscript=text("Belum ada perbualan.",14,false,Color.rgb(62,49,64));
        tvTranscript.setPadding(0,dp(8),0,0);
        root.addView(tvTranscript);

        btnStart.setOnClickListener(v->startVoice());
        btnStop.setOnClickListener(v->stopVoice(false));
        setContentView(scroll);
        refreshUsage();
    }

    private TextView text(String s,float size,boolean bold,int color){
        TextView v=new TextView(this);
        v.setText(s);v.setTextSize(size);v.setTextColor(color);
        if(bold)v.setTypeface(null,android.graphics.Typeface.BOLD);
        return v;
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}

    private void configureAudioRoute(){
        audioManager=(AudioManager)getSystemService(Context.AUDIO_SERVICE);
        if(audioManager!=null){
            try{
                oldMode=audioManager.getMode();
                oldSpeaker=audioManager.isSpeakerphoneOn();
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                audioManager.setSpeakerphoneOn(true);
            }catch(Exception ignored){}
        }
    }

    private void restoreAudioRoute(){
        if(audioManager!=null){
            try{audioManager.setMode(oldMode);audioManager.setSpeakerphoneOn(oldSpeaker);}catch(Exception ignored){}
        }
    }

    private void ready(){
        tvState.setText("Bersedia");
        tvHint.setText("Tekan Mula Cakap. Audio kini ditangkap terus oleh Android AudioRecord.");
        btnStart.setEnabled(true);
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if(requestCode==REQ_MIC){
            if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)ready();
            else{showError("Microphone permission diperlukan.");btnStart.setEnabled(false);}
        }
    }

    private void setBusy(final String state,final String hint){
        runOnUiThread(()->{
            tvState.setText(state);tvHint.setText(hint);
            progress.setVisibility(android.view.View.VISIBLE);
            btnStart.setEnabled(false);btnStop.setEnabled(false);
            tvError.setVisibility(android.view.View.GONE);
        });
    }

    private void liveState(){
        runOnUiThread(()->{
            tvState.setText("Saya dengar, Syam…");
            tvHint.setText("Terus bercakap. Hafiz akan jawab dengan suara.");
            progress.setVisibility(android.view.View.GONE);
            btnStart.setEnabled(false);btnStop.setEnabled(true);
        });
    }

    private void showError(final String msg){
        runOnUiThread(()->{
            progress.setVisibility(android.view.View.GONE);
            tvState.setText("Sambungan terhenti");
            tvHint.setText("V5.6 kekalkan skrin ini supaya ralat sebenar boleh dilihat.");
            tvError.setText(msg==null?"Voice gagal disambungkan.":msg);
            tvError.setVisibility(android.view.View.VISIBLE);
            btnStart.setText("SAMBUNG SEMULA");
            btnStart.setEnabled(true);btnStop.setEnabled(false);
        });
    }

    private void startVoice(){
        if(starting||ws!=null)return;
        if(android.os.Build.VERSION.SDK_INT>=23&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQ_MIC);return;
        }
        starting=true;ended=false;
        btnStart.setText("🎙 MULA CAKAP");
        setBusy("Menyambung…","Minta short-lived Voice token daripada Lovely Accounts.");
        io.submit(()->{
            try{
                JSONObject s=apiJson("POST","/voice/session",new JSONObject());
                sessionId=s.optString("session_id","");
                model=s.optString("model","");
                clientSecret=s.optString("client_secret","");
                if(clientSecret.isEmpty())throw new Exception("OpenAI Voice client secret tidak diterima.");
                renderUsage(s.optJSONObject("usage"));

                prepareAudioPlayback();
                prepareAudioCapture();
                connectWebSocket();
            }catch(Exception e){
                cleanupVoice();
                showError("V5.6: "+safeMsg(e));
            }finally{
                starting=false;
            }
        });
    }

    private void prepareAudioPlayback() throws Exception{
        int min=AudioTrack.getMinBufferSize(24000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
        if(min<=0)min=24000;
        AudioAttributes attrs=new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        AudioFormat fmt=new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(24000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        audioTrack=new AudioTrack(attrs,fmt,Math.max(min,48000),AudioTrack.MODE_STREAM,AudioManager.AUDIO_SESSION_ID_GENERATE);
        if(audioTrack.getState()!=AudioTrack.STATE_INITIALIZED)throw new Exception("Speaker AudioTrack gagal initialize.");
        audioTrack.play();
    }

    private void prepareAudioCapture() throws Exception{
        AudioRecord rec=buildRecorder(24000);
        if(rec!=null&&rec.getState()==AudioRecord.STATE_INITIALIZED){
            audioRecord=rec;captureRate=24000;
        }else{
            if(rec!=null)try{rec.release();}catch(Exception ignored){}
            rec=buildRecorder(48000);
            if(rec==null||rec.getState()!=AudioRecord.STATE_INITIALIZED){
                if(rec!=null)try{rec.release();}catch(Exception ignored){}
                throw new Exception("Android AudioRecord gagal initialize pada 24kHz dan 48kHz.");
            }
            audioRecord=rec;captureRate=48000;
        }
        try{
            int sid=audioRecord.getAudioSessionId();
            if(AcousticEchoCanceler.isAvailable()){aec=AcousticEchoCanceler.create(sid);if(aec!=null)aec.setEnabled(true);}
            if(NoiseSuppressor.isAvailable()){ns=NoiseSuppressor.create(sid);if(ns!=null)ns.setEnabled(true);}
        }catch(Exception ignored){}
    }

    private AudioRecord buildRecorder(int rate){
        int min=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        if(min<=0)return null;
        int[] sources=new int[]{MediaRecorder.AudioSource.VOICE_COMMUNICATION,MediaRecorder.AudioSource.VOICE_RECOGNITION,MediaRecorder.AudioSource.MIC};
        for(int source:sources){
            try{
                AudioRecord r=new AudioRecord(source,rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min*2,rate));
                if(r.getState()==AudioRecord.STATE_INITIALIZED)return r;
                r.release();
            }catch(Exception ignored){}
        }
        return null;
    }

    private void connectWebSocket() throws Exception{
        String wsUrl="wss://api.openai.com/v1/realtime?model="+java.net.URLEncoder.encode(model,"UTF-8");
        wsClient=new OkHttpClient.Builder()
                .pingInterval(20,java.util.concurrent.TimeUnit.SECONDS)
                .build();
        Request request=new Request.Builder()
                .url(wsUrl)
                .header("Authorization","Bearer "+clientSecret)
                .header("OpenAI-Beta","realtime=v1")
                .build();

        ws=wsClient.newWebSocket(request,new WebSocketListener(){
            @Override public void onOpen(WebSocket webSocket,Response response){
                try{
                    JSONObject upd=new JSONObject();
                    upd.put("type","session.update");
                    JSONObject session=new JSONObject();session.put("type","realtime");
                    JSONObject audio=new JSONObject();
                    JSONObject input=new JSONObject();
                    input.put("format",new JSONObject().put("type","audio/pcm").put("rate",24000));
                    JSONObject output=new JSONObject();
                    output.put("format",new JSONObject().put("type","audio/pcm").put("rate",24000));
                    audio.put("input",input);audio.put("output",output);
                    session.put("audio",audio);upd.put("session",session);
                    webSocket.send(upd.toString());
                    startCaptureLoop();
                    liveState();
                }catch(Exception e){showError("WebSocket open: "+safeMsg(e));}
            }
            @Override public void onMessage(WebSocket webSocket,String text){
                handleRealtimeEvent(text);
            }
            @Override public void onClosing(WebSocket webSocket,int code,String reason){
                if(!ended)showError("OpenAI Voice sedang tutup sambungan ("+code+"): "+reason);
            }
            @Override public void onClosed(WebSocket webSocket,int code,String reason){
                if(!ended)showError("OpenAI Voice tertutup ("+code+"): "+reason);
            }
            @Override public void onFailure(WebSocket webSocket,Throwable t,Response response){
                String extra=response!=null?" HTTP "+response.code():"";
                if(!ended)showError("WebSocket gagal"+extra+": "+safeMsg(t));
            }
        });
    }

    private void startCaptureLoop(){
        if(audioRecord==null)return;
        recording.set(true);
        captureExec.submit(()->{
            try{
                audioRecord.startRecording();
                if(audioRecord.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new Exception("AudioRecord tidak masuk RECORDING state.");
                int inSamples=(captureRate==24000?4800:9600);
                short[] in=new short[inSamples];
                while(recording.get()&&!ended){
                    int n=audioRecord.read(in,0,in.length,AudioRecord.READ_BLOCKING);
                    if(n<=0)continue;
                    byte[] pcm;
                    if(captureRate==24000){
                        pcm=shortsToBytes(in,n);
                    }else{
                        int outN=n/2;
                        short[] down=new short[outN];
                        for(int i=0;i<outN;i++)down[i]=in[i*2];
                        pcm=shortsToBytes(down,outN);
                    }
                    WebSocket current=ws;
                    if(current!=null){
                        JSONObject ev=new JSONObject();
                        ev.put("type","input_audio_buffer.append");
                        ev.put("audio",Base64.encodeToString(pcm,Base64.NO_WRAP));
                        current.send(ev.toString());
                    }
                }
            }catch(Exception e){
                if(!ended)showError("Native microphone: "+safeMsg(e));
            }finally{
                try{if(audioRecord!=null&&audioRecord.getRecordingState()==AudioRecord.RECORDSTATE_RECORDING)audioRecord.stop();}catch(Exception ignored){}
            }
        });
    }

    private byte[] shortsToBytes(short[] data,int n){
        byte[] out=new byte[n*2];
        for(int i=0,j=0;i<n;i++){
            short s=data[i];
            out[j++]=(byte)(s&0xff);
            out[j++]=(byte)((s>>8)&0xff);
        }
        return out;
    }

    private void playPcmDelta(String b64){
        if(b64==null||b64.isEmpty()||audioTrack==null)return;
        final byte[] data;
        try{data=Base64.decode(b64,Base64.DEFAULT);}catch(Exception e){return;}
        playbackExec.submit(()->{
            try{
                if(audioTrack!=null&&audioTrack.getState()==AudioTrack.STATE_INITIALIZED){
                    audioTrack.write(data,0,data.length,AudioTrack.WRITE_BLOCKING);
                }
            }catch(Exception ignored){}
        });
    }

    private void clearPlayback(){
        try{
            if(audioTrack!=null){
                audioTrack.pause();
                audioTrack.flush();
                audioTrack.play();
            }
        }catch(Exception ignored){}
    }

    private void handleRealtimeEvent(String raw){
        try{
            JSONObject evt=new JSONObject(raw);
            String type=evt.optString("type","");
            if("session.created".equals(type)||"session.updated".equals(type)){
                liveState();
            }else if("input_audio_buffer.speech_started".equals(type)){
                clearPlayback();
                runOnUiThread(()->{tvState.setText("Saya dengar, Syam…");tvHint.setText("Terus bercakap.");});
            }else if("input_audio_buffer.speech_stopped".equals(type)){
                runOnUiThread(()->{tvState.setText("Hafiz sedang fikir…");tvHint.setText("Sedang memahami arahan.");});
            }else if("conversation.item.input_audio_transcription.completed".equals(type)){
                appendTranscript("Syam",evt.optString("transcript",""));
            }else if("response.output_audio.delta".equals(type)){
                runOnUiThread(()->{tvState.setText("Hafiz sedang jawab…");tvHint.setText("Syam boleh mencelah bila-bila masa.");});
                playPcmDelta(evt.optString("delta",""));
            }else if("response.output_audio_transcript.done".equals(type)){
                appendTranscript("Hafiz",evt.optString("transcript",""));
            }else if("response.done".equals(type)){
                handleResponseDone(evt.optJSONObject("response"));
            }else if("error".equals(type)){
                JSONObject er=evt.optJSONObject("error");
                showError("OpenAI: "+(er!=null?er.optString("message","Ralat Realtime"):"Ralat Realtime"));
            }
        }catch(Exception ignored){}
    }

    private void handleResponseDone(JSONObject response){
        if(response==null)return;
        JSONObject usage=response.optJSONObject("usage");
        String responseId=response.optString("id","");
        if(usage!=null&&!responseId.isEmpty()){
            io.submit(()->{
                try{
                    JSONObject b=new JSONObject();
                    b.put("session_id",sessionId);b.put("response_id",responseId);b.put("model",model);b.put("usage",usage);
                    JSONObject r=apiJson("POST","/voice/usage",b);
                    renderUsage(r.optJSONObject("usage"));
                }catch(Exception ignored){}
            });
        }

        JSONArray output=response.optJSONArray("output");
        if(output!=null){
            for(int i=0;i<output.length();i++){
                JSONObject item=output.optJSONObject(i);
                if(item!=null&&"function_call".equals(item.optString("type")))dispatchTool(item);
            }
        }
        runOnUiThread(()->{tvState.setText("Saya dengar, Syam…");tvHint.setText("Cakap arahan seterusnya bila-bila masa.");});
    }

    private void dispatchTool(JSONObject item){
        final String callId=item.optString("call_id","");
        final String name=item.optString("name","");
        if(callId.isEmpty()||name.isEmpty())return;
        io.submit(()->{
            String output;
            try{
                JSONObject args;
                try{args=new JSONObject(item.optString("arguments","{}"));}catch(Exception e){args=new JSONObject();}
                JSONObject b=new JSONObject();b.put("name",name);b.put("arguments",args);
                JSONObject r=apiJson("POST","/voice/tool",b);
                Object payload=r.optBoolean("ok")?r.opt("result"):new JSONObject().put("error",r.optString("detail","Tool gagal"));
                output=String.valueOf(payload);
            }catch(Exception e){
                try{output=new JSONObject().put("error",safeMsg(e)).toString();}
                catch(Exception ignored){output="{\"error\":\"Tool failed\"}";}
            }
            try{
                JSONObject ev=new JSONObject();
                ev.put("type","conversation.item.create");
                JSONObject it=new JSONObject();
                it.put("type","function_call_output");it.put("call_id",callId);it.put("output",output);
                ev.put("item",it);
                sendWs(ev);
                sendWs(new JSONObject().put("type","response.create"));
            }catch(Exception ignored){}
        });
    }

    private void sendWs(JSONObject j){
        WebSocket current=ws;
        if(current!=null)current.send(j.toString());
    }

    private void appendTranscript(String who,String s){
        if(s==null||s.trim().isEmpty())return;
        runOnUiThread(()->{
            String old=tvTranscript.getText().toString();
            if("Belum ada perbualan.".equals(old))old="";
            tvTranscript.setText(who+": "+s.trim()+"\n\n"+old);
        });
    }

    private void renderUsage(JSONObject u){
        if(u==null)return;
        double spend=u.optDouble("effective_spend_myr",0);
        double limit=u.optDouble("monthly_limit_myr",30);
        double remain=u.optDouble("remaining_myr",Math.max(0,limit-spend));
        runOnUiThread(()->{
            tvCost.setText(String.format(Locale.US,"RM%.2f / RM%.2f",spend,limit));
            tvRemain.setText(String.format(Locale.US,"Baki RM%.2f",remain));
            tvMeta.setText(String.format(Locale.US,"Native AudioRecord • Live estimate RM%.2f",u.optDouble("estimated_live_myr",0)));
        });
    }

    private void refreshUsage(){
        io.submit(()->{
            try{renderUsage(apiJson("GET","/usage",null));}
            catch(Exception ignored){}
        });
    }

    private JSONObject apiJson(String method,String path,JSONObject body)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(API+path).openConnection();
        c.setRequestMethod(method);c.setConnectTimeout(20000);c.setReadTimeout(30000);
        c.setRequestProperty("Accept","application/json");
        c.setRequestProperty("Authorization","Bearer "+mobileToken);
        if(body!=null){
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type","application/json; charset=utf-8");
            try(OutputStream os=c.getOutputStream()){
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        int code=c.getResponseCode();
        String txt=readAll(code>=400?c.getErrorStream():c.getInputStream());
        JSONObject r=txt.trim().isEmpty()?new JSONObject():new JSONObject(txt);
        if(code>=400)throw new Exception(r.optString("detail","HTTP "+code));
        return r;
    }

    private String readAll(InputStream in)throws Exception{
        if(in==null)return "";
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        byte[] buf=new byte[8192];int n;
        while((n=in.read(buf))>0)b.write(buf,0,n);
        return b.toString("UTF-8");
    }

    private String safeMsg(Throwable t){
        if(t==null)return "Ralat tidak diketahui.";
        String m=t.getMessage();
        return (m==null||m.trim().isEmpty())?t.getClass().getSimpleName():m;
    }

    private void stopVoice(boolean silent){
        ended=true;
        recording.set(false);
        final String sid=sessionId;
        sessionId="";
        if(!sid.isEmpty()){
            io.submit(()->{
                try{apiJson("POST","/voice/end",new JSONObject().put("session_id",sid));}
                catch(Exception ignored){}
            });
        }
        cleanupVoice();
        if(!silent){
            runOnUiThread(()->{
                tvState.setText("Sesi tamat");
                tvHint.setText("Tekan Mula Cakap untuk sesi baru.");
                btnStart.setText("🎙 MULA CAKAP");
                btnStart.setEnabled(true);btnStop.setEnabled(false);
                progress.setVisibility(android.view.View.GONE);
            });
        }
    }

    private synchronized void cleanupVoice(){
        recording.set(false);
        try{if(audioRecord!=null&&audioRecord.getRecordingState()==AudioRecord.RECORDSTATE_RECORDING)audioRecord.stop();}catch(Exception ignored){}
        try{if(aec!=null)aec.release();}catch(Exception ignored){}aec=null;
        try{if(ns!=null)ns.release();}catch(Exception ignored){}ns=null;
        try{if(audioRecord!=null)audioRecord.release();}catch(Exception ignored){}audioRecord=null;
        try{if(audioTrack!=null){audioTrack.pause();audioTrack.flush();audioTrack.stop();audioTrack.release();}}catch(Exception ignored){}audioTrack=null;
        try{if(ws!=null)ws.close(1000,"done");}catch(Exception ignored){}ws=null;
        if(wsClient!=null){try{wsClient.dispatcher().executorService().shutdown();}catch(Exception ignored){}wsClient=null;}
        clientSecret="";
    }

    @Override public void onBackPressed(){stopVoice(true);super.onBackPressed();}

    @Override protected void onDestroy(){
        stopVoice(true);
        restoreAudioRoute();
        io.shutdownNow();captureExec.shutdownNow();playbackExec.shutdownNow();
        super.onDestroy();
    }
}
