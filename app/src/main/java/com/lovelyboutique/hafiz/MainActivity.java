package com.lovelyboutique.hafiz;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String BASE = "https://accounting.lovelyboutique.com.my/api/hafiz-mobile/v1";
    private static final int PICK_RECEIPT = 4401;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private String token = "";
    private JSONObject lastParsed;

    private Spinner spDocType;
    private EditText etSmartText, etCustomerName, etCustomerPhone, etCustomerEmail, etCustomerAddress, etItems, etDiscount, etDelivery;
    private TextView tvMatch, tvDocResult, tvReceiptResult, tvSummary;
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
        if (token.isEmpty()) showLogin(); else checkStatus();
    }

    private void bindViews() {
        spDocType=findViewById(R.id.spDocType); etSmartText=findViewById(R.id.etSmartText); tvMatch=findViewById(R.id.tvMatch);
        etCustomerName=findViewById(R.id.etCustomerName); etCustomerPhone=findViewById(R.id.etCustomerPhone); etCustomerEmail=findViewById(R.id.etCustomerEmail); etCustomerAddress=findViewById(R.id.etCustomerAddress);
        etItems=findViewById(R.id.etItems); etDiscount=findViewById(R.id.etDiscount); etDelivery=findViewById(R.id.etDelivery); btnCreateDoc=findViewById(R.id.btnCreateDoc); tvDocResult=findViewById(R.id.tvDocResult);
        etReceiptDate=findViewById(R.id.etReceiptDate); etSupplier=findViewById(R.id.etSupplier); etReceiptAmount=findViewById(R.id.etReceiptAmount); etReceiptCategory=findViewById(R.id.etReceiptCategory); etReceiptRef=findViewById(R.id.etReceiptRef); tvReceiptResult=findViewById(R.id.tvReceiptResult); tvSummary=findViewById(R.id.tvSummary);
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
        findViewById(R.id.btnExtract).setOnClickListener(v -> extractSmartText());
        btnCreateDoc.setOnClickListener(v -> createDocument());
        findViewById(R.id.btnReceipt).setOnClickListener(v -> pickReceipt());
        findViewById(R.id.btnSavePurchase).setOnClickListener(v -> savePurchase());
        findViewById(R.id.btnDaily).setOnClickListener(v -> getDaily());
        findViewById(R.id.btnOutstanding).setOnClickListener(v -> getOutstanding());
        findViewById(R.id.btnLogout).setOnClickListener(v -> {prefs.edit().remove("token").apply(); token=""; showLogin();});
    }

    private void showLogin() {
        final EditText pass=new EditText(this); pass.setHint("HAFIZ_OAUTH_PASSWORD"); pass.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD); pass.setPadding(30,20,30,20);
        AlertDialog dlg=new AlertDialog.Builder(this).setTitle("Sambung Hafiz").setMessage("Masukkan password Hafiz sekali untuk sambungkan app dengan Lovely Accounts.").setView(pass).setCancelable(false)
                .setPositiveButton("Sambung", null).setNegativeButton("Tutup",(d,w)->finish()).create();
        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> login(pass.getText().toString(), dlg)));
        dlg.show();
    }

    private void login(String password, AlertDialog dlg) {
        if(password.trim().isEmpty()){toast("Masukkan password Hafiz.");return;}
        runIo(() -> {
            JSONObject body=new JSONObject(); body.put("password",password);
            JSONObject r=requestJson("POST", BASE+"/login", body, false);
            String t=r.optString("token",""); if(t.isEmpty()) throw new Exception(r.optString("detail","Login gagal"));
            token=t; prefs.edit().putString("token",t).apply();
            runOnUiThread(() -> {dlg.dismiss();toast("Hafiz sudah disambungkan.");checkStatus();});
        });
    }

    private void checkStatus(){runIo(() -> {JSONObject r=requestJson("GET",BASE+"/status",null,true); runOnUiThread(() -> toast(r.optBoolean("ok")?"Lovely Accounts bersedia.":"Connection belum bersedia."));});}

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
                tvMatch.setText(finalMatch);tvDocResult.setText("Semak detail di atas. Edit jika perlu, kemudian tekan Create.");setBusy(null);
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

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(requestCode==PICK_RECEIPT&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null)scanReceipt(data.getData());}

    private void scanReceipt(Uri uri){tvReceiptResult.setText("Membaca resit...");runIo(() -> {JSONObject r=uploadReceipt(uri);runOnUiThread(() -> {etReceiptDate.setText(r.optString("document_date",LocalDate.now().toString()));etSupplier.setText(r.optString("supplier"));etReceiptAmount.setText(r.optDouble("amount",0)>0?String.valueOf(r.optDouble("amount")):"");etReceiptCategory.setText(r.optString("category"));etReceiptRef.setText(r.optString("document_no"));tvReceiptResult.setText("OCR siap. Confidence "+r.optDouble("confidence",0)+"%. Semak sebelum simpan.");});});}

    private void savePurchase(){runIo(() -> {JSONObject b=new JSONObject();String supplier=etSupplier.getText().toString().trim();double amt=num(etReceiptAmount);if(amt<=0)throw new Exception("Jumlah pembelian mesti lebih daripada RM0.");b.put("purchase_date",emptyOr(etReceiptDate,LocalDate.now().toString()));b.put("supplier",supplier);b.put("description",supplier.isEmpty()?"Pembelian / perbelanjaan":"Pembelian dari "+supplier);b.put("amount",amt);b.put("category",etReceiptCategory.getText().toString().trim());b.put("reference",etReceiptRef.getText().toString().trim());b.put("payment_method","Tunai / Bank");JSONObject r=requestJson("POST",BASE+"/purchases",b,true);runOnUiThread(() -> tvReceiptResult.setText("Pembelian berjaya direkod. "+r.optString("purchase_no",r.optString("reference",""))));});}

    private void getDaily(){tvSummary.setText("Mengambil laporan...");runIo(() -> {JSONObject r=requestJson("GET",BASE+"/daily-report",null,true);runOnUiThread(() -> tvSummary.setText(prettyDaily(r)));});}
    private void getOutstanding(){tvSummary.setText("Mengambil outstanding...");runIo(() -> {JSONObject r=requestJson("GET",BASE+"/outstanding",null,true);runOnUiThread(() -> tvSummary.setText("Outstanding: RM"+String.format(java.util.Locale.US,"%.2f",r.optDouble("total_outstanding",0))+"\nInvoice belum selesai: "+(r.optJSONArray("invoices")!=null?r.optJSONArray("invoices").length():0)));});}

    private String prettyDaily(JSONObject r){return "Jualan: RM"+fmt(r.optDouble("sales_total",r.optDouble("manual_sales_total",0)))+"\nPembelian: RM"+fmt(r.optDouble("purchases_total",0))+"\nInvoice dikeluarkan: RM"+fmt(r.optDouble("invoices_issued_total",0))+"\nBayaran invoice: RM"+fmt(r.optDouble("invoice_payments_total",0));}
    private String fmt(double n){return String.format(java.util.Locale.US,"%.2f",n);}
    private double num(EditText e){try{return Double.parseDouble(e.getText().toString().trim());}catch(Exception x){return 0;}}
    private String emptyOr(EditText e,String d){String s=e.getText().toString().trim();return s.isEmpty()?d:s;}
    private void setBusy(String s){if(s!=null)tvDocResult.setText(s);}

    private void runIo(IOTask task){io.submit(() -> {try{task.run();}catch(Exception e){e.printStackTrace();runOnUiThread(() -> handleError(e));}});}
    private void handleError(Exception e){String m=e.getMessage()==null?"Ralat":e.getMessage();if(m.contains("401")||m.toLowerCase().contains("mobile login required")){prefs.edit().remove("token").apply();token="";toast("Sesi Hafiz tamat. Login semula.");showLogin();}else toast(m);setBusy(null);}
    private interface IOTask{void run() throws Exception;}

    private JSONObject requestJson(String method,String url,JSONObject body,boolean auth) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setRequestMethod(method);c.setConnectTimeout(20000);c.setReadTimeout(30000);c.setRequestProperty("Accept","application/json");if(auth&&!token.isEmpty())c.setRequestProperty("Authorization","Bearer "+token);
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
