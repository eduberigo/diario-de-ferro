package com.eduberigo.diariodeferro.wear;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Tela do treino no relógio: fase e cronômetro, exercício e série, batimentos, os mesmos botões da notificação do celular
 * e o ajuste de repetições e carga da série atual.
 */
public class WatchActivity extends Activity {

    private static final int BG = 0xFF000000, TXT = 0xFFFFFFFF, MUTED = 0xFFB4BCC6, CARD = 0xFF1E2228;
    private ScrollView scroll;
    private LinearLayout main, acts, adj;
    private TextView label, title, line1, line2, hrTv, idle, repsTv, kgTv, stepTv;
    private Chronometer chrono;
    private Button adjBtn;
    private boolean adjOpen = false;
    private double adjKg = 0, step = 2.5;
    private int adjReps = 0;
    private String adjKey = "";
    private final Runnable refresh = this::render;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setGravity(Gravity.CENTER_HORIZONTAL);
        int padH = dp(26), padV = dp(30);
        main.setPadding(padH, padV, padH, dp(44));
        scroll.addView(main, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        idle = text(15, MUTED, false);
        idle.setText("Nenhum treino em andamento.\nComece o treino no celular.");
        main.addView(idle);

        label = text(12, TXT, true);
        label.setLetterSpacing(0.08f);
        main.addView(label);
        chrono = new Chronometer(this);
        chrono.setTextSize(TypedValue.COMPLEX_UNIT_SP, 42);
        chrono.setTypeface(Typeface.DEFAULT_BOLD);
        chrono.setGravity(Gravity.CENTER);
        chrono.setTextColor(TXT);
        main.addView(chrono, wrap());
        title = text(16, TXT, true);
        title.setMaxLines(2);
        main.addView(title);
        line1 = text(13, MUTED, false);
        main.addView(line1);
        hrTv = text(14, 0xFFFF6B6B, true);
        main.addView(hrTv);

        acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.VERTICAL);
        acts.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        main.addView(acts, lp);

        adjBtn = button("Ajustar série", CARD, TXT, 14);
        adjBtn.setOnClickListener(v -> openAdj());
        main.addView(adjBtn, full(6));

        adj = new LinearLayout(this);
        adj.setOrientation(LinearLayout.VERTICAL);
        adj.setGravity(Gravity.CENTER_HORIZONTAL);
        buildAdj();
        main.addView(adj, full(6));

        line2 = text(11, MUTED, false);
        main.addView(line2, full(10));

        setContentView(scroll);
        askPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        WatchState.listener = refresh;
        loadState();
        WatchState.send(this, "/ferro/hello", "");
        render();
        scroll.requestFocus();   // bezel/coroa rolam a tela
    }

    @Override
    protected void onPause() {
        WatchState.listener = null;
        super.onPause();
    }

    /** Pega o último estado guardado pela Data Layer (o celular grava a cada mudança). */
    private void loadState() {
        try {
            Uri u = new Uri.Builder().scheme("wear").path("/ferro/state").build();
            Wearable.getDataClient(this).getDataItems(u).addOnSuccessListener(buf -> {
                try {
                    for (DataItem it : buf) {
                        DataMap m = DataMapItem.fromDataItem(it).getDataMap();
                        WatchState.update(this, m.getString("json", ""), m.getLong("ts", 0L));
                    }
                } finally {
                    buf.release();
                }
            });
        } catch (Throwable ignored) {}
    }

    private void askPermission() {
        if (WatchService.canMeasure(this)) return;
        String p = Build.VERSION.SDK_INT >= 36 ? "android.permission.health.READ_HEART_RATE" : Manifest.permission.BODY_SENSORS;
        try {
            requestPermissions(new String[] {p}, 1);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        render();
    }

    private void render() {
        JSONObject s = WatchState.state;
        boolean on = s != null;
        idle.setVisibility(on ? View.GONE : View.VISIBLE);
        for (View v : new View[] {label, chrono, title, line1, acts, line2}) v.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) {
            hrTv.setVisibility(View.GONE);
            adjBtn.setVisibility(View.GONE);
            adj.setVisibility(View.GONE);
            adjOpen = false;
            return;
        }
        if (!WatchService.running) WatchService.start(this);   // a tela está aberta: pode começar a medir
        int color = parse(s.optString("color", "#F0654D"));
        label.setText(s.optString("label", ""));
        label.setTextColor(color);
        title.setText(s.optString("title", ""));
        line1.setText(s.optString("line1", "").replace("  ·  ", "\n"));
        line2.setText(s.optString("line2", "").replace("  ·  ", " · "));
        setChrono(s, color);

        int bpm = WatchState.hr;
        boolean fresh = bpm > 0 && System.currentTimeMillis() - WatchState.hrAt < 15000L;
        hrTv.setVisibility(View.VISIBLE);
        hrTv.setText(fresh ? "♥ " + bpm + " bpm" : WatchService.canMeasure(this) ? "♥ medindo…" : "♥ sem permissão de batimentos");

        acts.removeAllViews();
        JSONArray a = s.optJSONArray("actions");
        LinearLayout row = null;
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                final String id = o.optString("id", "");
                boolean primary = o.optBoolean("primary", false);
                Button btn = button(o.optString("label", ""), primary ? color : CARD, primary ? 0xFF111111 : TXT, primary ? 17 : 14);
                btn.setOnClickListener(v -> {
                    WatchState.vibrate(this, new long[] {0, 40});
                    WatchState.send(this, "/ferro/act", id);
                });
                if (primary) {
                    acts.addView(btn, 0, full(0));
                } else {
                    if (row == null) {
                        row = new LinearLayout(this);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        acts.addView(row, full(6));
                    }
                    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    p.setMargins(dp(2), 0, dp(2), 0);
                    row.addView(btn, p);
                }
            }
        }

        JSONObject w = s.optJSONObject("w");
        boolean canAdj = w != null && !w.optBoolean("time", false);
        String key = w == null ? "" : w.optInt("ex", -1) + "/" + w.optInt("set", -1);
        if (!key.equals(adjKey)) adjOpen = false;   // mudou de série: fecha o ajuste
        adjBtn.setVisibility(canAdj && !adjOpen ? View.VISIBLE : View.GONE);
        adj.setVisibility(canAdj && adjOpen ? View.VISIBLE : View.GONE);
    }

    private void setChrono(JSONObject s, int color) {
        String mode = s.optString("chrono", "up");
        if ("none".equals(mode)) {
            chrono.stop();
            chrono.setVisibility(View.GONE);
            return;
        }
        boolean down = "down".equals(mode), paused = s.optBoolean("paused", false);
        long now = System.currentTimeMillis(), er = SystemClock.elapsedRealtime(), base;
        if (paused) {
            long shown = s.optLong("shownMs", 0L);
            base = down ? er + shown : er - shown;
        } else {
            base = down ? er + (s.optLong("endAt", now) - now) : er - (now - s.optLong("startAt", now));
        }
        chrono.setCountDown(down);
        chrono.setBase(base);
        chrono.setTextColor(paused ? MUTED : TXT);
        if (paused) chrono.stop();
        else chrono.start();
    }

    // ---------- ajuste da série: repetições e carga ----------
    private void buildAdj() {
        TextView h = text(12, MUTED, true);
        h.setText("AJUSTAR SÉRIE");
        adj.addView(h);
        repsTv = text(22, TXT, true);
        adj.addView(stepper(repsTv, v -> { adjReps = Math.max(0, adjReps - 1); showAdj(); }, v -> { adjReps = Math.min(200, adjReps + 1); showAdj(); }), full(4));
        kgTv = text(20, TXT, true);
        adj.addView(stepper(kgTv, v -> { adjKg = Math.max(0, round(adjKg - step)); showAdj(); }, v -> { adjKg = Math.min(999, round(adjKg + step)); showAdj(); }), full(4));
        stepTv = button("", CARD, MUTED, 12);
        stepTv.setOnClickListener(v -> { step = step == 2.5 ? 5 : step == 5 ? 1 : 2.5; showAdj(); });
        adj.addView(stepTv, full(4));
        Button save = button("Salvar", 0xFF46BF78, 0xFF111111, 16);
        save.setOnClickListener(v -> {
            JSONObject w = WatchState.state == null ? null : WatchState.state.optJSONObject("w");
            if (w != null) WatchState.send(this, "/ferro/act", "adj|" + adjReps + "|" + fmt(adjKg) .replace(',', '.') + "|" + w.optInt("ex") + "|" + w.optInt("set"));
            WatchState.vibrate(this, new long[] {0, 40});
            adjOpen = false;
            render();
        });
        adj.addView(save, full(8));
        Button cancel = button("Cancelar", CARD, TXT, 14);
        cancel.setOnClickListener(v -> { adjOpen = false; render(); });
        adj.addView(cancel, full(4));
    }

    private void openAdj() {
        JSONObject w = WatchState.state == null ? null : WatchState.state.optJSONObject("w");
        if (w == null) return;
        adjReps = w.optInt("reps", 0);
        adjKg = w.optDouble("kg", 0);
        adjKey = w.optInt("ex", -1) + "/" + w.optInt("set", -1);
        adjOpen = true;
        showAdj();
        render();
        scroll.post(() -> scroll.smoothScrollTo(0, adj.getTop()));
    }

    private void showAdj() {
        repsTv.setText(adjReps + " reps");
        kgTv.setText(fmt(adjKg) + " kg\n" + fmt(round(adjKg * 2.2046226218)) + " lb");
        stepTv.setText("passo da carga: " + fmt(step) + " kg");
    }

    private LinearLayout stepper(TextView value, View.OnClickListener minus, View.OnClickListener plus) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        Button m = button("−", CARD, TXT, 22);
        m.setOnClickListener(minus);
        Button p = button("+", CARD, TXT, 22);
        p.setOnClickListener(plus);
        r.addView(m, new LinearLayout.LayoutParams(dp(46), dp(46)));
        r.addView(value, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(p, new LinearLayout.LayoutParams(dp(46), dp(46)));
        return r;
    }

    // ---------- utilidades ----------
    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static String fmt(double v) {
        if (Math.abs(v - Math.round(v)) < 0.05) return String.valueOf(Math.round(v));
        return String.format(Locale.US, "%.1f", v).replace('.', ',');
    }

    private static int parse(String c) {
        try {
            return Color.parseColor(c);
        } catch (Exception e) {
            return 0xFFF0654D;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLayoutParams(wrap());
        return t;
    }

    private Button button(String label, int bg, int fg, int sp) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        b.setTextColor(fg);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setMinHeight(dp(44));
        b.setMinimumHeight(dp(44));
        b.setPadding(dp(6), dp(4), dp(6), dp(4));
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(24));
        b.setBackground(d);
        b.setStateListAnimator(null);
        return b;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams full(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        return p;
    }
}
