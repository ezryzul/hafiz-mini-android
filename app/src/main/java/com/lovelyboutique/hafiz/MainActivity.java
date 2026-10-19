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

    private static final String CHATGPT_PACKAGE = "com.openai.chatgpt";
    private static final String CHATGPT_WEB = "https://chatgpt.com/";
    private static final String LOVELY_ACCOUNTS_URL =
        "https://accounting.lovelyboutique.com.my/";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        findViewById(R.id.btnVoice).setOnClickListener(v ->
            openChatGPTWithPrompt("Hafiz, saya nak beri arahan akaun melalui suara.")
        );
        findViewById(R.id.btnSale).setOnClickListener(v ->
            openChatGPTWithPrompt("Hafiz, saya nak rekod jualan baru.")
        );
        findViewById(R.id.btnPurchase).setOnClickListener(v ->
            openChatGPTWithPrompt("Hafiz, saya nak rekod pembelian atau perbelanjaan baru.")
        );
        findViewById(R.id.btnQuotation).setOnClickListener(v ->
            openChatGPTWithPrompt("Hafiz, saya nak buat sebut harga baru.")
        );
        findViewById(R.id.btnToday).setOnClickListener(v ->
            openChatGPTWithPrompt("Hafiz, tunjuk laporan akaun hari ini.")
        );
        findViewById(R.id.btnAccounts).setOnClickListener(v ->
            openUrl(LOVELY_ACCOUNTS_URL)
        );
    }

    private void openChatGPTWithPrompt(String prompt) {
        ClipboardManager clipboard =
            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Arahan Hafiz", prompt));

        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(CHATGPT_PACKAGE);
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launchIntent);
            Toast.makeText(
                this,
                "ChatGPT dibuka. Arahan Hafiz telah disalin — tampal atau terus guna suara.",
                Toast.LENGTH_LONG
            ).show();
            return;
        }

        Toast.makeText(
            this,
            "Aplikasi ChatGPT tidak dijumpai. Membuka ChatGPT melalui pelayar.",
            Toast.LENGTH_LONG
        ).show();
        openUrl(CHATGPT_WEB);
    }

    private void openUrl(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(
                this,
                "Tiada pelayar atau aplikasi yang boleh membuka pautan ini.",
                Toast.LENGTH_LONG
            ).show();
        }
    }
}
