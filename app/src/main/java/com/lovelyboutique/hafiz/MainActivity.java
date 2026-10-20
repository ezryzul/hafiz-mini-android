package com.lovelyboutique.hafiz;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String BASE = "https://accounting.lovelyboutique.com.my/api/hafiz-mobile/v1";
    private static final int PICK_RECEIPT = 4401;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private String token = "";
    private JSONObject lastParsed;
    private AlertDialog loginDialog;
    private boolean loginInFlight=false;

    private Spinner spDocType;
    private EditText etSmartText, etCustomerName, etCustomerPhone, etCustomerEmail, etCustomerAddress, etItems, etDiscount, etDelivery;
    private TextView tvMatch, tvDocResult, tvReceiptResult, tvSummary, tvVoiceCost, tvVoiceRemaining, tvVoiceActual, tvDeviceName;
    private EditText etReceiptDate, etSupplier, etReceiptAmount, etReceiptCategory, etReceiptRef;
    private Button btnCreateDoc;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("hafiz_mobile", MODE_PRIVATE);
        token = prefs.getString("token", "");
        bindViews();
        setupDocType();
        setupActions();
        tvDeviceName.setText(getDeviceName());
        if (token.isEmpty()) showLogin(); else refreshUsage();
    }

    @Override protected void onResume() {
        super.onResume();
        if(!token.isEmpty()) refreshUsage();
    }

    private void bindViews() {
        spDocType=findViewById(R.id.spDocType); etSmartText=findViewById(R.id.etSmartText); tvMatch=findViewById(R.id.tvMatch);
        etCustomerName=findViewById(R.id.etCustomerName); etCustomerPhone=findViewById(R.id.etCustomerPhone); etCustomerEmail=findViewById(R.id.etCustomerEmail); etCustomerAddress=findViewById(R.id.etCustomerAddress);
        etItems=findViewById(R.id.etItems); etDiscount=findViewById(R.id.etDiscount); etDelivery=findViewById(R.id.etDelivery); btnCreateDoc=findViewById(R.id.btnCreateDoc); tvDocResult=findViewById(R.id.tvDocResult);
        etReceiptDate=findViewById(R.id.etReceiptDate); etSupplier=findViewById(R.id.etSupplier); etReceiptAmount=findViewById(R.id.etReceiptAmount); etReceiptCategory=findViewById(R.id.etReceiptCategory); etReceiptRef=findViewById(R.id.etReceiptRef); tvReceiptResult=findViewById(R.id.tvReceiptResult); tvSummary=findViewById(R.id.tvSummary);
        tvVoiceCost=findViewById(R.id.tvVoiceCost); tvVoiceRemaining=findViewById(R.id.tvVoiceRemaining); tvVoiceActual=findViewById(R.id.tvVoiceActual); tvDeviceName=findViewById(R.id.tvDeviceName);
    }

    private void setupDocType() {
        ArrayAdapter<String> a=new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"Quotation","Invoice"});
        spDocType.setAdapter(a);
        spDocType.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id){btnCreateDoc.setText(pos==0?"CREATE QUOTATION":"CREATE INVOICE");}
            public void onNothingSelected(android.widget.AdapterView<?> p){}
        });
    }

    private void setupActions() {
        findViewById(R.id.btnVoiceLive).setOnClickListener(v -> openVoice());
        findViewById(R.id.btnDevices).setOnClickListener(v -> showDevices());
        findViewById(R.id.btnExtract).setOnClickListener(v -> extractSmartText());
        btnCreateDoc.setOnClickListener(v -> createDocument());
        findViewById(R.id.btnReceipt).setOnClickListener(v -> pickReceipt());
        findViewById(R.id.btnSavePurchase).setOnClickListener(v -> savePurchase());
        findViewById(R.id.btnDaily).setOnClickListener(v -> getDaily());
        findViewById(R.id.btnOutstanding).setOnClickListener(v -> getOutstanding());
        findViewById(R.id.btnOpenAccounts).setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://accounting.lovelyboutique.com.my/"))));
        findViewById(R.id.btnLogout).setOnClickListener(v -> logout());
    }

    private String getHafizDeviceId(){
        String id=prefs.getString("device_id","");
        if(id.isEmpty()){id=UUID.randomUUID().toString();prefs.edit().putString("device_id",id).apply();}
        return id;
    }

    private String getDeviceName(){
        String maker=(Build.MANUFACTURER==null?"Android":Build.MANUFACTURER).trim();
        String model=(Build.MODEL==null?"Phone":Build.MODEL).trim();
        if(model.toLowerCase().startsWith(maker.toLowerCase())) return model;
        return maker+" "+model;
    }

    private void showLogin() {
        if(loginDialog!=null && loginDialog.isShowing()) return;
        final EditText pass=new EditText(this);
        pass.setHint("HAFIZ_OAUTH_PASSWORD");
        pass.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setPadding(30,20,30,20);
        AlertDialog dlg=new AlertDialog.Builder(this)
                .setTitle("Sambung Hafiz")
                .setMessage("Masukkan password Hafiz sekali untuk sambungkan phone ini dengan Lovely Accounts.")
                .setView(pass)
                .setCancelable(false)
                .setPositiveButton("Sambung", null)
                .setNegativeButton("Tutup",(d,w)->finish())
                .create();
        loginDialog=dlg;
        dlg.setOnDismissListener(d->{loginInFlight=false;loginDialog=null;});
        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if(loginInFlight) return;
            login(pass.getText().toString(), dlg);
        }));
        dlg.show();
    }

    private void login(String password, AlertDialog dlg) {
        if(password.trim().isEmpty()){toast("Masukkan password Hafiz.");return;}
        loginInFlight=true;
        Button connect=dlg.getButton(AlertDialog.BUTTON_POSITIVE);
        if(connect!=null){connect.setEnabled(false);connect.setText("Menyambung...");}
        io.submit(() -> {
            try{
                JSONObject body=new JSONObject();
                body.put("password",password);
                body.put("device_id",getHafizDeviceId());
                body.put("device_name",getDeviceName());
                body.put("platform","android");
                body.put("app_version","5.2.0");
                JSONObject r=requestJson("POST", BASE+"/login", body, false);
                String t=r.optString("token","");
                if(t.isEmpty()) throw new Exception(r.optString("detail","Login gagal"));
                token=t;
                prefs.edit().putString("token",t).commit();
                runOnUiThread(() -> {
                    View focus=dlg.getCurrentFocus();
                    hideKeyboard(focus!=null?focus:getWindow().getDecorView());
                    if(dlg.isShowing()) dlg.dismiss();
                    toast("Phone ini sudah disambungkan dengan Hafiz.");
                    tvVoiceActual.setText("Connected • Hafiz V5.2");
                    new android.os.Handler(getMainLooper()).postDelayed(this::refreshUsage,700);
                });
            }catch(Exception e){
                runOnUiThread(() -> {
                    loginInFlight=false;
                    if(connect!=null){connect.setEnabled(true);connect.setText("Sambung");}
                    handleError(e);
                });
            }
        });
    }

    private void hideKeyboard(View v){
        try{
            InputMethodManager imm=(InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
            if(imm!=null) imm.hideSoftInputFromWindow(v.getWindowToken(),0);
        }catch(Exception ignored){}
    }

    private void logout(){
        final String old=token;
        token=""; prefs.edit().remove("token").apply();
        if(!old.isEmpty()) io.submit(() -> {try{requestJsonWithToken("POST",BASE+"/logout",new JSONObject(),old);}catch(Exception ignored){}});
        showLogin();
    }

    private void checkStatus(){runIo(() -> {JSONObject r=requestJson("GET",BASE+"/status",null,true); runOnUiThread(() -> {toast(r.optBoolean("ok")?"Lovely Accounts bersedia.":"Connection belum bersedia.");refreshUsage();});});}

    private void openVoice(){
        if(token.isEmpty()){showLogin();return;}
        Intent i=new Intent(this, VoiceActivity.class);
        startActivity(i);
    }

    private void refreshUsage(){
        if(token.isEmpty())return;
        runIo(() -> {
            JSONObject r=requestJson("GET",BASE+"/usage",null,true);
            runOnUiThread(() -> {
                double effective=r.optDouble("effective_spend_myr",0),limit=r.optDouble("monthly_limit_myr",30),remaining=r.optDouble("remaining_myr",Math.max(0,limit-effective));
                tvVoiceCost.setText(String.format(java.util.Locale.US,"RM%.2f / RM%.2f",effective,limit));
                tvVoiceRemaining.setText(String.format(java.util.Locale.US,"Baki RM%.2f",remaining));
                if(r.optBoolean("actual_cost_available",false)) tvVoiceActual.setText(String.format(java.util.Locale.US,"Actual OpenAI ±RM%.2f  •  Live estimate RM%.2f",r.optDouble("actual_openai_myr_estimate",0),r.optDouble("estimated_live_myr",0)));
                else tvVoiceActual.setText(String.format(java.util.Locale.US,"Live estimate RM%.2f  •  Actual cost perlukan OpenAI Admin Key",r.optDouble("estimated_live_myr",0)));
            });
        });
    }

    private void showDevices(){
        if(token.isEmpty()){showLogin();return;}
        runIo(() -> {
            JSONObject r=requestJson("GET",BASE+"/devices",null,true); JSONArray arr=r.optJSONArray("devices"); if(arr==null)arr=new JSONArray();
            final JSONArray devices=arr; final String[] labels=new String[devices.length()];
            for(int i=0;i<devices.length();i++){JSONObject d=devices.optJSONObject(i);String s=d.optString("device_name","Phone");if(d.optBoolean("current"))s+="  • phone ini";if(!d.optBoolean("active",true))s+="  • revoked";labels[i]=s;}
            runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Devices Connected").setItems(labels,(dialog,which)->{
                JSONObject d=devices.optJSONObject(which);if(d==null)return;if(d.optBoolean("current")){toast("Ini phone yang sedang digunakan.");return;}if(!d.optBoolean("active",true)){toast("Device ini sudah direvoke.");return;}
                new AlertDialog.Builder(this).setTitle("Revoke device?").setMessage(d.optString("device_name")).setPositiveButton("REVOKE",(x,w)->revokeDevice(d.optString("device_id"))).setNegativeButton("Batal",null).show();
            }).setNegativeButton("Tutup",null).show());
        });
    }

    private void revokeDevice(String id){
        runIo(() -> {JSONObject r=requestJson("POST",BASE+"/devices/"+id+"/revoke",new JSONObject(),true);runOnUiThread(() -> toast(r.optBoolean("ok")?"Device access telah direvoke.":"Revoke gagal."));});
    }

    private void extractSmartText() {
        String text=etSmartText.getText().toString().trim(); if(text.isEmpty()){toast("Paste atau tulis detail customer dan produk dahulu.");return;}
        setBusy("Extracting...");
        runIo(() -> {
            JSONObject b=new JSONObject(); b.put("text",text); b.put("type",spDocType.getSelectedItemPosition()==1?"invoice":"quotation");
            JSONObject r=requestJson("POST",BASE+"/smart-text/parse",b,true);
            lastParsed=r;
            JSONObject p=r.getJSONObject("parsed"), c=p.getJSONObject("customer"), match=r.optJSONObject("customer_match");
            JSONObject existing=match!=null?match.optJSONObject("customer"):null;
            final String name=existing!=null?existing.optString("full_name",c.optString("name")):c.optString("name");
            final String phone=existing!=null?existing.optString("phone",c.optString("phone")):c.optString("phone");
            final String email=existing!=null?existing.optString("email",c.optString("email")):c.optString("email");
            final String address=existing!=null?existing.optString("billing_address",c.optString("address")):c.optString("address");
            JSONArray items=p.optJSONArray("items"); StringBuilder itemText=new StringBuilder();
            if(items!=null)for(int i=0;i<items.length();i++){JSONObject it=items.getJSONObject(i);if(i>0)itemText.append("\n");itemText.append(it.optString("description")).append(" | ").append(it.optDouble("qty",1)).append(" | ").append(String.format(java.util.Locale.US,"%.2f",it.optDouble("unit_price",0)));}
            String matchText="Customer: "+(match==null?"tiada match":match.optString("match","none"));
            if(existing!=null)matchText+=" — guna existing customer #"+existing.optInt("id");
            final String finalMatch=matchText, finalItems=itemText.toString();
            runOnUiThread(() -> {
                etCustomerName.setText(name);etCustomerPhone.setText(phone);etCustomerEmail.setText(email);etCustomerAddress.setText(address);etItems.setText(finalItems);
                etDiscount.setText(p.optDouble("discount",0)>0?String.valueOf(p.optDouble("discount")):"");etDelivery.setText(p.optDouble("delivery_fee",0)>0?String.valueOf(p.optDouble("delivery_fee")):"");
                tvMatch.setText(finalMatch);tvDocResult.setText("Semak detail. Edit jika perlu, kemudian Create.");setBusy(null);
            });
        });
    }

    private JSONArray parseItemsField() throws Exception {
        JSONArray arr=new JSONArray(); String[] lines=etItems.getText().toString().split("\\r?\\n");
        for(String line:lines){if(line.trim().isEmpty())continue;String[] p=line.split("\\|");if(p.length<3)throw new Exception("Format item mesti: Item | Qty | Harga");JSONObject o=new JSONObject();o.put("description",p[0].trim());o.put("qty",Double.parseDouble(p[1].trim()));o.put("unit","unit");o.put("unit_price",Double.parseDouble(p[2].trim()));arr.put(o);} if(arr.length()==0)throw new Exception("Masukkan sekurang-kurangnya satu item."); return arr;
    }

    private void createDocument() {
        setBusy("Creating document...");
        runIo(() -> {
            JSONObject b=new JSONObject(); String type=spDocType.getSelectedItemPosition()==1?"invoice":"quotation"; b.put("doc_type",type);
            String name=etCustomerName.getText().toString().trim();if(name.isEmpty())throw new Exception("Nama customer diperlukan.");
            b.put("customer_name",name);b.put("customer_phone",etCustomerPhone.getText().toString().trim());b.put("customer_email",etCustomerEmail.getText().toString().trim());b.put("customer_address",etCustomerAddress.getText().toString().trim());
            b.put("items",parseItemsField());b.put("discount",num(etDiscount));b.put("delivery_fee",num(etDelivery));b.put("auto_create_products",true);
            if(lastParsed!=null){JSONObject cm=lastParsed.optJSONObject("customer_match");JSONObject ex=cm!=null?cm.optJSONObject("customer"):null;if(ex!=null)b.put("customer_id",ex.optInt("id"));JSONObject pp=lastParsed.optJSONObject("parsed");int days=type.equals("quotation")?pp.optInt("valid_days",0):pp.optInt("due_days",0);if(days>0)b.put("due_date",LocalDate.now().plusDays(days).toString());}
            JSONObject r=requestJson("POST",BASE+"/documents",b,true);
            runOnUiThread(() -> {tvDocResult.setText("Berjaya: "+r.optString("doc_no","Document")+" • Total RM"+String.format(java.util.Locale.US,"%.2f",r.optDouble("total",0)));setBusy(null);});
        });
    }

    private void pickReceipt(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/jpeg","image/png","image/webp","application/pdf"});startActivityForResult(i,PICK_RECEIPT);}

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==PICK_RECEIPT&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null)scanReceipt(data.getData());
    }

    private void scanReceipt(Uri uri){tvReceiptResult.setText("Membaca resit...");runIo(() -> {JSONObject r=uploadReceipt(uri);runOnUiThread(() -> {etReceiptDate.setText(r.optString("document_date",LocalDate.now().toString()));etSupplier.setText(r.optString("supplier"));etReceiptAmount.setText(r.optDouble("amount",0)>0?String.valueOf(r.optDouble("amount")):"");etReceiptCategory.setText(r.optString("category"));etReceiptRef.setText(r.optString("document_no"));tvReceiptResult.setText("OCR siap. Confidence "+r.optDouble("confidence",0)+"%. Semak sebelum simpan.");});});}

    private void savePurchase(){runIo(() -> {JSONObject b=new JSONObject();String supplier=etSupplier.getText().toString().trim();double amt=num(etReceiptAmount);if(amt<=0)throw new Exception("Jumlah pembelian mesti lebih daripada RM0.");b.put("purchase_date",emptyOr(etReceiptDate,LocalDate.now().toString()));b.put("supplier",supplier);b.put("description",supplier.isEmpty()?"Pembelian / perbelanjaan":"Pembelian dari "+supplier);b.put("amount",amt);b.put("category",etReceiptCategory.getText().toString().trim());b.put("reference",etReceiptRef.getText().toString().trim());b.put("payment_method","Tunai / Bank");JSONObject r=requestJson("POST",BASE+"/purchases",b,true);runOnUiThread(() -> tvReceiptResult.setText("Pembelian berjaya direkod. "+r.optString("reference","")));});}

    private void getDaily(){tvSummary.setText("Mengambil laporan...");runIo(() -> {JSONObject r=requestJson("GET",BASE+"/daily-report",null,true);runOnUiThread(() -> tvSummary.setText(prettyDaily(r)));});}
    private void getOutstanding(){tvSummary.setText("Mengambil outstanding...");runIo(() -> {JSONObject r=requestJson("GET",BASE+"/outstanding",null,true);runOnUiThread(() -> tvSummary.setText("Outstanding: RM"+fmt(r.optDouble("total_outstanding",0))+"\nInvoice belum selesai: "+r.optInt("invoice_count",0)));});}

    private String prettyDaily(JSONObject r){
        JSONObject s=r.optJSONObject("manual_sales"),p=r.optJSONObject("purchases"),i=r.optJSONObject("invoices_issued"),pay=r.optJSONObject("invoice_payments");
        return "Jualan: RM"+fmt(s==null?0:s.optDouble("amount",0))+"\nPembelian: RM"+fmt(p==null?0:p.optDouble("amount",0))+"\nInvoice dikeluarkan: RM"+fmt(i==null?0:i.optDouble("amount",0))+"\nBayaran invoice: RM"+fmt(pay==null?0:pay.optDouble("amount",0));
    }
    private String fmt(double n){return String.format(java.util.Locale.US,"%.2f",n);}
    private double num(EditText e){try{return Double.parseDouble(e.getText().toString().trim());}catch(Exception x){return 0;}}
    private String emptyOr(EditText e,String d){String s=e.getText().toString().trim();return s.isEmpty()?d:s;}
    private void setBusy(String s){if(s!=null)tvDocResult.setText(s);}

    private void runIo(IOTask task){io.submit(() -> {try{task.run();}catch(Exception e){e.printStackTrace();runOnUiThread(() -> handleError(e));}});}
    private void handleError(Exception e){
        String m=e.getMessage()==null?"Ralat":e.getMessage();
        String ml=m.toLowerCase();
        boolean authExpired=ml.contains("mobile login required")||ml.contains("hafiz_mobile_auth_required")||ml.contains("hafiz_mobile_relogin_required")||ml.contains("hafiz_mobile_session_revoked")||ml.contains("device session has ended")||ml.contains("was revoked")||ml.contains("please login again on this device");
        if(authExpired && token!=null && !token.isEmpty()){
            prefs.edit().remove("token").apply(); token="";
            toast("Sesi phone tidak sah. Tekan Log Keluar untuk sambung semula — app tidak akan buka password berulang.");
            tvVoiceActual.setText("Perlu sambung semula");
        }else if(m.contains("Incorrect Hafiz password")) toast("Password Hafiz tidak betul. Semak HAFIZ_OAUTH_PASSWORD di Hostinger.");
        else if(m.contains("HTTP 401")) toast("Phone sudah login, tetapi backend accounting menolak salah satu request. Session phone dikekalkan.");
        else toast(m);
        setBusy(null);
    }
    private interface IOTask{void run() throws Exception;}

    private JSONObject requestJson(String method,String url,JSONObject body,boolean auth) throws Exception {return requestJsonWithToken(method,url,body,auth?token:"");}
    private JSONObject requestJsonWithToken(String method,String url,JSONObject body,String authToken) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setRequestMethod(method);c.setConnectTimeout(20000);c.setReadTimeout(30000);c.setRequestProperty("Accept","application/json");if(authToken!=null&&!authToken.isEmpty())c.setRequestProperty("Authorization","Bearer "+authToken);
        if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");try(OutputStream os=c.getOutputStream()){os.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
        int code=c.getResponseCode();InputStream is=code>=400?c.getErrorStream():c.getInputStream();String text=readAll(is);JSONObject r=text.trim().isEmpty()?new JSONObject():new JSONObject(text);if(code>=400)throw new Exception("HTTP "+code+": "+r.optString("detail","Request gagal"));return r;
    }

    private JSONObject uploadReceipt(Uri uri) throws Exception {
        String boundary="----Hafiz"+System.currentTimeMillis();HttpURLConnection c=(HttpURLConnection)new URL(BASE+"/receipts/scan").openConnection();c.setRequestMethod("POST");c.setDoOutput(true);c.setConnectTimeout(20000);c.setReadTimeout(90000);c.setRequestProperty("Authorization","Bearer "+token);c.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);
        String name="receipt";String mime=getContentResolver().getType(uri);if(mime==null)mime="application/octet-stream";
        try(OutputStream os=c.getOutputStream();InputStream in=getContentResolver().openInputStream(uri)){
            os.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+name+"\"\r\nContent-Type: "+mime+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));byte[] buf=new byte[8192];int n;while((n=in.read(buf))>0)os.write(buf,0,n);os.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        int code=c.getResponseCode();String text=readAll(code>=400?c.getErrorStream():c.getInputStream());JSONObject r=new JSONObject(text);if(code>=400)throw new Exception("HTTP "+code+": "+r.optString("detail","Scan gagal"));return r;
    }

    private String readAll(InputStream in) throws Exception {if(in==null)return "";ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))>0)b.write(buf,0,n);return b.toString("UTF-8");}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
}
