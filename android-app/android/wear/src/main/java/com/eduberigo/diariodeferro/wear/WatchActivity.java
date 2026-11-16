package com.eduberigo.diariodeferro.wear;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.EditText;
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
 * Tela do treino no relógio. Em destaque: exercício, onde o treino está (aquecimento 2 de 3, série 1 de 4, cardio),
 * repetições e carga, batimentos e o cronômetro da fase. Os botões são os mesmos do celular, com Voltar em todas as fases.
 * Ajuste da série em kg ou lb, por passos ou digitando.
 */
public class WatchActivity extends Activity {

    private static final int BG = 0xFF000000, TXT = 0xFFFFFFFF, MUTED = 0xFFB4BCC6, CARD = 0xFF23272E, HR = 0xFFFF5A5F;
    private static final double LB = 2.20462;   // o mesmo fator do app do celular
    private ScrollView scroll;
    private LinearLayout acts, adj;
    private TextView label, name, pos, load, hrTv, idle, line2, status, stepTv, unitTv, screenTv;
    private EditText repsEd, wEd;
    private Chronometer chrono;
    private Button adjBtn;
    private boolean adjOpen = false, keepOn = true, editing = false;
    private double adjW = 0, step = 5;   // adjW na unidade escolhida
    private String unit = "lb", adjKey = "";
    private long sentAt = 0L, sentTs = 0L;
    private SharedPreferences prefs;
    private final Runnable refresh = this::render;
    private final Runnable noAnswer = () -> {
        if (sentAt > 0 && WatchState.stateTs <= sentTs) say("O celular não respondeu. Abra o app no celular.", true);
        sentAt = 0;
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH)) {   // instalado no celular por engano
            TextView t = new TextView(this);
            t.setBackgroundColor(BG);
            t.setTextColor(TXT);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(28), dp(28), dp(28), dp(28));
            t.setText("Este é o app do RELÓGIO, instalado no celular por engano.\n\nInstale por cima o arquivo diario-de-ferro.apk (o do celular). Não desinstale: seus treinos continuam guardados e voltam.");
            setContentView(t);
            return;
        }
        prefs = getSharedPreferences("relogio", MODE_PRIVATE);
        unit = prefs.getString("unit", "lb");
        keepOn = prefs.getBoolean("keepOn", true);
        step = "lb".equals(unit) ? 5 : 2.5;

        scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setGravity(Gravity.CENTER_HORIZONTAL);
        main.setPadding(dp(24), dp(26), dp(24), dp(48));
        scroll.addView(main, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        idle = text(15, MUTED, false);
        idle.setText("Nenhum treino em andamento.\nComece o treino no celular.");
        main.addView(idle);

        label = text(12, TXT, true);
        label.setLetterSpacing(0.08f);
        main.addView(label);
        chrono = new Chronometer(this);
        chrono.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        chrono.setTypeface(Typeface.DEFAULT_BOLD);
        chrono.setGravity(Gravity.CENTER);
        chrono.setTextColor(TXT);
        main.addView(chrono, wrap());
        name = text(19, TXT, true);
        name.setMaxLines(2);
        main.addView(name, full(2));
        pos = text(15, TXT, true);
        main.addView(pos, full(2));
        load = text(24, TXT, true);
        main.addView(load, full(2));
        hrTv = text(30, HR, true);
        main.addView(hrTv, full(2));
        status = text(12, 0xFFFFC857, false);
        main.addView(status, full(2));

        acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.VERTICAL);
        acts.setGravity(Gravity.CENTER_HORIZONTAL);
        main.addView(acts, full(8));

        adjBtn = button("Ajustar reps e carga", CARD, TXT, 14);
        adjBtn.setOnClickListener(v -> openAdj());
        main.addView(adjBtn, full(6));

        adj = new LinearLayout(this);
        adj.setOrientation(LinearLayout.VERTICAL);
        adj.setGravity(Gravity.CENTER_HORIZONTAL);
        buildAdj();
        main.addView(adj, full(6));

        line2 = text(11, MUTED, false);
        main.addView(line2, full(10));
        screenTv = button("", CARD, MUTED, 12);
        screenTv.setOnClickListener(v -> {
            keepOn = !keepOn;
            prefs.edit().putBoolean("keepOn", keepOn).apply();
            render();
        });
        main.addView(screenTv, full(8));

        setContentView(scroll);
        askPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (scroll == null) return;
        WatchState.listener = refresh;
        loadState();
        WatchState.send(this, "/ferro/hello", "", ok -> { if (!ok) say("Sem conexão com o celular. Bluetooth ligado?", true); });
        render();
        if (!editing) scroll.requestFocus();   // bezel/coroa rolam a tela
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
        // Wear OS 6 troca BODY_SENSORS por READ_HEART_RATE; pede as duas e o sistema mostra a que vale no relógio
        String[] p = Build.VERSION.SDK_INT >= 36 ? new String[] {Manifest.permission.BODY_SENSORS, "android.permission.health.READ_HEART_RATE"} : new String[] {Manifest.permission.BODY_SENSORS};
        try {
            requestPermissions(p, 1);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        render();
    }

    private void say(String t, boolean warn) {
        status.setText(t);
        status.setTextColor(warn ? 0xFFFFC857 : MUTED);
        status.setVisibility(t.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void render() {
        JSONObject s = WatchState.state;
        if (s != null && System.currentTimeMillis() - WatchState.stateTs > 5L * 3600000L) s = null;   // estado velho demais
        boolean on = s != null;
        if (on && keepOn) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (sentAt > 0 && WatchState.stateTs > sentTs) {   // o celular respondeu
            sentAt = 0;
            say("", false);
        }
        idle.setVisibility(on ? View.GONE : View.VISIBLE);
        for (View v : new View[] {label, chrono, name, pos, load, hrTv, acts, line2, screenTv}) v.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) {
            adjBtn.setVisibility(View.GONE);
            adj.setVisibility(View.GONE);
            adjOpen = false;
            return;
        }
        if (!WatchService.running) WatchService.start(this);   // a tela está aberta: pode começar a medir
        int color = parse(s.optString("color", "#F0654D"));
        JSONObject w = s.optJSONObject("w");
        label.setText(s.optString("label", ""));
        label.setTextColor(color);
        setChrono(s);
        name.setText(w != null ? w.optString("name", s.optString("title", "")) : s.optString("title", ""));
        if (w != null) {
            pos.setText(w.optString("pos", ""));
            pos.setTextColor(w.optBoolean("warm", false) ? 0xFFF5A524 : color);
            if (w.optBoolean("time", false)) {
                int secs = w.optInt("secs", 0);
                load.setText(w.optBoolean("cardio", false) ? "Meta " + hms(secs) : hms(secs));
            } else {
                double kg = w.optDouble("kg", 0);
                String main = "lb".equals(unit) ? fmt(kg * LB) + " lb" : fmt(kg) + " kg";
                load.setText(w.optInt("reps", 0) + " reps · " + main);
            }
        } else {
            pos.setText("");
            load.setText(s.optString("line1", ""));
        }
        line2.setText((w != null ? "Exercício " + w.optString("exN", "") + " · " : "") + s.optString("line2", "").replace("  ·  ", " · ").replaceFirst("^Exercício \\d+ de \\d+( · )?", ""));
        screenTv.setText(keepOn ? "Tela sempre ligada: sim" : "Tela sempre ligada: não");

        int bpm = WatchState.hr;
        boolean fresh = bpm > 0 && System.currentTimeMillis() - WatchState.hrAt < 15000L;
        hrTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fresh ? 30 : 14);
        hrTv.setText(fresh ? "♥ " + bpm : WatchService.canMeasure(this) ? "♥ medindo…" : "♥ sem permissão de batimentos");

        acts.removeAllViews();
        JSONArray a = s.optJSONArray("wact");
        if (a == null) a = s.optJSONArray("actions");
        LinearLayout row = null;
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                final String id = o.optString("id", "");
                boolean primary = o.optBoolean("primary", false);
                Button btn = button(o.optString("label", ""), primary ? color : CARD, primary ? 0xFF111111 : TXT, primary ? 18 : 13);
                btn.setOnClickListener(v -> act(id));
                if (primary) {
                    LinearLayout.LayoutParams lp = full(0);
                    lp.height = dp(56);
                    acts.addView(btn, 0, lp);
                } else {
                    if (row == null || row.getChildCount() >= 3) {
                        row = new LinearLayout(this);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        acts.addView(row, full(6));
                    }
                    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1f);
                    p.setMargins(dp(2), 0, dp(2), 0);
                    row.addView(btn, p);
                }
            }
        }

        boolean canAdj = w != null && !w.optBoolean("time", false);
        String key = w == null ? "" : w.optInt("ex", -1) + "/" + w.optInt("set", -1);
        if (!key.equals(adjKey)) adjOpen = false;   // mudou de série: fecha o ajuste
        adjBtn.setVisibility(canAdj && !adjOpen ? View.VISIBLE : View.GONE);
        adj.setVisibility(canAdj && adjOpen ? View.VISIBLE : View.GONE);
    }

    /** Toque num botão: manda ao celular e mostra se chegou. */
    private void act(String id) {
        WatchState.vibrate(this, new long[] {0, 40});
        sentAt = System.currentTimeMillis();
        sentTs = WatchState.stateTs;
        say("Enviando…", false);
        WatchState.send(this, "/ferro/act", id, ok -> {
            if (!ok) {
                sentAt = 0;
                say("Sem conexão com o celular. Bluetooth ligado?", true);
            }
        });
        WatchState.MAIN.removeCallbacks(noAnswer);
        WatchState.MAIN.postDelayed(noAnswer, 4000L);
    }

    private void setChrono(JSONObject s) {
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

    // ---------- ajuste da série: repetições e carga (kg ou lb), por passos ou digitando ----------
    private void buildAdj() {
        TextView h = text(12, MUTED, true);
        h.setText("AJUSTAR SÉRIE");
        adj.addView(h);
        repsEd = edit(false);
        adj.addView(stepper(repsEd, "reps", v -> setReps(reps() - 1), v -> setReps(reps() + 1)), full(4));
        wEd = edit(true);
        unitTv = text(13, MUTED, true);
        adj.addView(stepper(wEd, null, v -> setW(w() - step), v -> setW(w() + step)), full(4));
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        Button u = button("", CARD, TXT, 13);
        u.setOnClickListener(v -> {   // troca a unidade mantendo a carga
            double kg = toKg(w());
            unit = "lb".equals(unit) ? "kg" : "lb";
            prefs.edit().putString("unit", unit).apply();
            step = "lb".equals(unit) ? 5 : 2.5;
            adjW = round("lb".equals(unit) ? kg * LB : kg);
            showAdj();
        });
        stepTv = button("", CARD, MUTED, 12);
        stepTv.setOnClickListener(v -> {
            step = "lb".equals(unit) ? (step == 5 ? 2.5 : step == 2.5 ? 10 : 5) : (step == 2.5 ? 5 : step == 5 ? 1 : 2.5);
            showAdj();
        });
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, dp(44), 1f);
        p1.setMargins(dp(2), 0, dp(2), 0);
        r.addView(u, p1);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, dp(44), 1.4f);
        p2.setMargins(dp(2), 0, dp(2), 0);
        r.addView(stepTv, p2);
        adj.addView(r, full(4));
        unitBtn = u;
        adj.addView(unitTv, full(2));
        Button save = button("Salvar", 0xFF46BF78, 0xFF111111, 17);
        save.setOnClickListener(v -> {
            readEdits();
            JSONObject w = WatchState.state == null ? null : WatchState.state.optJSONObject("w");
            if (w != null) {
                String kg = String.format(Locale.US, "%.4f", toKg(adjW));
                act("adj|" + Math.max(0, reps()) + "|" + kg + "|" + w.optInt("ex") + "|" + w.optInt("set"));
            }
            adjOpen = false;
            editing = false;
            render();
        });
        LinearLayout.LayoutParams sp = full(8);
        sp.height = dp(52);
        adj.addView(save, sp);
        Button cancel = button("Cancelar", CARD, TXT, 14);
        cancel.setOnClickListener(v -> { adjOpen = false; editing = false; render(); });
        adj.addView(cancel, full(4));
    }

    private Button unitBtn;
    private int adjReps = 0;

    private int reps() {
        return adjReps;
    }

    private double w() {
        return adjW;
    }

    private void setReps(int r) {
        readEdits();
        adjReps = Math.max(0, Math.min(200, r));
        showAdj();
    }

    private void setW(double v) {
        readEdits();
        adjW = Math.max(0, Math.min(2000, round(v)));
        showAdj();
    }

    private double toKg(double v) {
        return "lb".equals(unit) ? v / LB : v;
    }

    /** O que foi digitado nos campos vale antes de qualquer + ou −. */
    private void readEdits() {
        try {
            String t = repsEd.getText().toString().trim();
            if (!t.isEmpty()) adjReps = Math.max(0, Integer.parseInt(t));
        } catch (Exception ignored) {}
        try {
            String t = wEd.getText().toString().trim().replace(',', '.');
            if (!t.isEmpty()) adjW = Math.max(0, Double.parseDouble(t));
        } catch (Exception ignored) {}
    }

    private void openAdj() {
        JSONObject w = WatchState.state == null ? null : WatchState.state.optJSONObject("w");
        if (w == null) return;
        adjReps = w.optInt("reps", 0);
        double kg = w.optDouble("kg", 0);
        adjW = round("lb".equals(unit) ? kg * LB : kg);
        adjKey = w.optInt("ex", -1) + "/" + w.optInt("set", -1);
        adjOpen = true;
        showAdj();
        render();
        scroll.post(() -> scroll.smoothScrollTo(0, adj.getTop()));
    }

    private void showAdj() {
        repsEd.setText(String.valueOf(adjReps));
        wEd.setText(fmt(adjW).replace(',', '.'));
        unitBtn.setText("lb".equals(unit) ? "em lb ⇄ kg" : "em kg ⇄ lb");
        stepTv.setText("passo " + fmt(step) + " " + unit);
        double other = "lb".equals(unit) ? adjW / LB : adjW * LB;
        unitTv.setText("reps · carga em " + unit + "  (= " + fmt(other) + ("lb".equals(unit) ? " kg)" : " lb)"));
    }

    private EditText edit(boolean decimal) {
        EditText e = new EditText(this);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        e.setTypeface(Typeface.DEFAULT_BOLD);
        e.setTextColor(TXT);
        e.setGravity(Gravity.CENTER);
        e.setSelectAllOnFocus(true);
        e.setSingleLine(true);
        e.setInputType(decimal ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL : InputType.TYPE_CLASS_NUMBER);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        e.setBackground(null);
        e.setOnFocusChangeListener((v, has) -> { editing = has; if (!has) readEdits(); });
        e.setOnEditorActionListener((v, id, ev) -> { readEdits(); showAdj(); v.clearFocus(); return false; });
        return e;
    }

    private LinearLayout stepper(View value, String unitLabel, View.OnClickListener minus, View.OnClickListener plus) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        Button m = button("−", CARD, TXT, 24);
        m.setOnClickListener(minus);
        Button p = button("+", CARD, TXT, 24);
        p.setOnClickListener(plus);
        r.addView(m, new LinearLayout.LayoutParams(dp(48), dp(48)));
        r.addView(value, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(p, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return r;
    }

    // ---------- utilidades ----------
    private static String hms(int sec) {
        sec = Math.max(0, sec);
        int h = sec / 3600, m = sec % 3600 / 60, x = sec % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, x) : String.format(Locale.US, "%d:%02d", m, x);
    }

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
        b.setPadding(dp(4), dp(2), dp(4), dp(2));
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(26));
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
