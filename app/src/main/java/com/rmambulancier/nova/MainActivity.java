package com.rmambulancier.nova;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private android.content.SharedPreferences prefs;
    private TextView accessText;
    private TextView overlayText;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("nova", MODE_PRIVATE);
        if (android.os.Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        setContentView(buildUi());
    }

    @Override protected void onResume() {
        super.onResume();
        if (accessText != null) refreshStatus();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(7, 8, 10));
        root.setPadding(dp(22), dp(18), dp(22), dp(24));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(content, new ViewGroup.LayoutParams(-1, -1));

        content.addView(text("NOVA", 34, Color.WHITE, Typeface.DEFAULT_BOLD));
        content.addView(text("Dynamic Spot 4.0", 18, Color.LTGRAY, Typeface.DEFAULT_BOLD), top(2));
        content.addView(text("Android 17 • Pixel • 100 % local", 13, Color.GRAY, Typeface.DEFAULT), top(6));

        LinearLayout status = card();
        accessText = text("Accès notifications : …", 15, Color.WHITE, Typeface.DEFAULT_BOLD);
        overlayText = text("Overlay : …", 15, Color.WHITE, Typeface.DEFAULT_BOLD);
        status.addView(accessText);
        status.addView(overlayText, top(8));
        content.addView(status, top(24));

        content.addView(button("Autoriser l’overlay", () -> {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        }), top(14));
        content.addView(button("Autoriser l’accès aux notifications", () -> {
            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
        }), top(10));

        LinearLayout features = card();
        features.addView(text("FONCTIONS", 12, Color.GRAY, Typeface.DEFAULT_BOLD));
        addSwitch(features, "🎵 Média", "Musique, podcasts, pochette, progression", "media", true);
        addSwitch(features, "🔔 Notifications", "Alertes courtes", "notifications", true);
        addSwitch(features, "🔋 Batterie", "Charge et batterie faible", "battery", true);
        content.addView(features, top(22));

        LinearLayout info = card();
        info.addView(text("Architecture V4", 15, Color.WHITE, Typeface.DEFAULT_BOLD));
        info.addView(text("Base Android native uniquement. Aucun Compose, aucune librairie UI, aucun serveur, aucune publicité, aucun analytics.", 13, Color.LTGRAY, Typeface.DEFAULT), top(8));
        content.addView(info, top(14));

        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        return root;
    }

    private void addSwitch(LinearLayout parent, String title, String subtitle, String key, boolean def) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(4));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 15, Color.WHITE, Typeface.DEFAULT_BOLD));
        labels.addView(text(subtitle, 12, Color.GRAY, Typeface.DEFAULT), top(3));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch sw = new Switch(this);
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean(key, checked).apply());
        row.addView(sw);
        parent.addView(row);
    }

    private LinearLayout card() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(16), dp(15), dp(16), dp(15));
        v.setBackgroundColor(Color.rgb(22, 24, 28));
        return v;
    }

    private TextView button(String label, final Runnable action) {
        TextView v = text(label, 14, Color.BLACK, Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER);
        v.setBackgroundColor(Color.WHITE);
        v.setPadding(dp(12), dp(13), dp(12), dp(13));
        v.setOnClickListener(view -> action.run());
        return v;
    }

    private TextView text(String value, float size, int color, Typeface face) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setTypeface(face);
        return v;
    }

    private LinearLayout.LayoutParams top(int px) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(px);
        return p;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void refreshStatus() {
        boolean access = NovaNotificationListenerService.isAccessGranted(this);
        boolean overlay = Settings.canDrawOverlays(this);
        accessText.setText("Accès notifications : " + (access ? "ACTIF" : "À autoriser"));
        overlayText.setText("Overlay : " + (overlay ? "ACTIF" : "À autoriser"));
    }
}
