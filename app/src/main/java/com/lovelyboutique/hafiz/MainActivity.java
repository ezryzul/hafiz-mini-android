package com.lovelyboutique.hafiz;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final String HAFIZ_PLUGIN_URL =
        "https://chatgpt.com/plugins/plugin_asdk_app_6ad632b1e1a88191a59da0bae4188241";
    private static final String LOVELY_ACCOUNTS_URL =
        "https://accounting.lovelyboutique.com.my/";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        findViewById(R.id.btnVoice).setOnClickListener(v ->
            openHafizWithPrompt("Hafiz, saya nak beri arahan akaun melalui suara.")
        );

        findViewById(R.id.btnSale).setOnClickListener(v ->
            openHafizWithPrompt("Hafiz, saya nak rekod jualan baru.")
        );

        findViewById(R.id.btnPurchase).setOnClickListener(v ->
            openHafizWithPrompt("Hafiz, saya nak rekod pembelian atau perbelanjaan baru.")
        );

        findViewById(R.id.btnQuotation).setOnClickListener(v ->
            openHafizWithPrompt("Hafiz, saya nak buat sebut harga baru.")
        );

        findViewById(R.id.btnToday).setOnClickListener(v ->
            openHafizWithPrompt("Hafiz, tunjuk laporan akaun hari ini.")
        );

        findViewById(R.id.btnAccounts).setOnClickListener(v ->
            openUrl(LOVELY_ACCOUNTS_URL)
        );
    }

    private void openHafizWithPrompt(String prompt) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Arahan Hafiz", prompt));
        Toast.makeText(
            this,
            "Arahan telah disalin. Bila ChatGPT terbuka, tampal arahan atau tekan ikon suara.",
            Toast.LENGTH_LONG
        ).show();
        openUrl(HAFIZ_PLUGIN_URL);
    }

    private void openUrl(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Tiada pelayar atau aplikasi yang boleh membuka pautan ini.", Toast.LENGTH_LONG).show();
        }
    }
}
