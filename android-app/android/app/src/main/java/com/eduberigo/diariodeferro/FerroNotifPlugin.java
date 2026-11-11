package com.eduberigo.diariodeferro;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import org.json.JSONObject;

/**
 * Notificação do treino em andamento: layout próprio, cronômetro ao vivo
 * (contagem regressiva ou progressiva, roda sem o app) e botões com ícone
 * que agem sem abrir o app.
 */
@CapacitorPlugin(name = "FerroNotif")
public class FerroNotifPlugin extends Plugin {

    static final String CHANNEL = "ferro-ativo";
    static final int NOTIF_ID = 7001;
    static final String ACTION = "com.eduberigo.diariodeferro.NOTIF";
    static FerroNotifPlugin instance;
    static volatile JSONObject last;          // último conteúdo da notificação do treino (para a linha do tempo avançar sozinha)
    static volatile boolean fg = true;        // app na frente: o aviso em destaque (com vibração) só aparece em segundo plano
    static volatile long lastTouch = 0L;      // último toque do usuário (app ou notificação): para os lembretes
    private static final String K_LAST = "lastNotif";

    @Override
    protected void handleOnResume() { fg = true; lastTouch = System.currentTimeMillis(); }

    @Override
    protected void handleOnPause() { fg = false; }

    @Override
    public void load() {
        instance = this;
        createChannel(getContext());
        FerroSound.loadConfig(getContext());
        FerroSound.ttsInit(getContext());
    }

    /** Som e linha do tempo rodam na thread principal; uma falha ali não pode derrubar o app. */
    static void onMain(Runnable r) {
        FerroSound.MAIN.post(() -> {
            try {
                r.run();
            } catch (Throwable ignored) {}
        });
    }

    /** Avisa a página que um passo da linha do tempo aconteceu (ela avança a fase e redesenha). */
    static void tick() {
        FerroNotifPlugin p = instance;
        if (p == null || p.getBridge() == null) return;
        try {
            p.fire("tick");
        } catch (Exception ignored) {}
    }

    static void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Treino em andamento", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Fase, série, carga e cronômetro do treino, com botões");
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setShowBadge(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(ALERT_CH) == null) {
            NotificationChannel ch = new NotificationChannel(ALERT_CH, "Aviso de fim do cronômetro", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Aparece quando o descanso ou o preparo termina com o app fechado");
            ch.setSound(null, null); // o som é tocado pelo app, abaixando a música só durante o aviso
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[] {0, 260, 140, 260});
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(ALERT_CH_Q) == null) {
            NotificationChannel ch = new NotificationChannel(ALERT_CH_Q, "Aviso de fim do cronômetro (sem vibrar)", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("O mesmo aviso, para quando a vibração está desligada nos Ajustes do app");
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }
        try {
            nm.deleteNotificationChannel("ferro-treino");
        } catch (Exception ignored) {}
    }

    // ---------- aviso em destaque quando uma fase termina com o app em segundo plano (sem som: o som é do FerroSound) ----------
    static final String ALERT_CH = "ferro-aviso";
    static final String ALERT_CH_Q = "ferro-aviso-mudo";
    static final int ALERT_ID = 7002;

    /** Linha do tempo da fase: a página manda todos os passos de uma vez (ver FerroTimeline). */
    @PluginMethod
    public void alarm(PluginCall call) {
        final Context ctx = getContext().getApplicationContext();
        final String steps = call.getString("steps", null);
        final String key = call.getString("key", "");
        if (steps != null) onMain(() -> FerroTimeline.set(ctx, key, steps));
        call.resolve();
    }

    static void schedule(AlarmManager am, long at, PendingIntent pi) {
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    @PluginMethod
    public void cancelAlarm(PluginCall call) {
        final Context ctx = getContext().getApplicationContext();
        onMain(() -> FerroTimeline.clear(ctx));
        call.resolve();
    }

    @PluginMethod
    public void clearAlert(PluginCall call) {
        NotificationManagerCompat.from(getContext()).cancel(ALERT_ID);
        call.resolve();
    }

    /** O usuário tocou em algo no app: os lembretes repetidos param. */
    @PluginMethod
    public void touch(PluginCall call) {
        lastTouch = System.currentTimeMillis();
        call.resolve();
    }

    /** Toca um som agora (ex.: som de início ao tocar em "Começar", testes dos Ajustes). */
    @PluginMethod
    public void play(PluginCall call) {
        final Context ctx = getContext().getApplicationContext();
        final String sound = call.getString("sound", "snd:bipe");
        final long hold = call.getData().optLong("hold", 0L);
        onMain(() -> FerroSound.play(ctx, sound, hold));
        call.resolve();
    }

    /** Fala um texto com a voz do Android; "alt" é o som que toca se a voz não estiver disponível. */
    @PluginMethod
    public void speak(PluginCall call) {
        final Context ctx = getContext().getApplicationContext();
        final String text = call.getString("text", "");
        final String alt = call.getString("alt", "");
        onMain(() -> FerroSound.say(ctx, text, alt));
        call.resolve();
    }

    /** Volume dos avisos: vol = % do volume de alarme durante o aviso (0 = não mexer); boost = reforço em milibéis. */
    @PluginMethod
    public void config(PluginCall call) {
        FerroSound.config(getContext(), call.getData().optInt("vol", 100), call.getData().optInt("boost", 600));
        call.resolve();
    }

    /** Diagnóstico de som para a tela de Ajustes. */
    @PluginMethod
    public void status(PluginCall call) {
        Context ctx = getContext();
        JSObject r = new JSObject();
        try {
            JSONObject o = FerroSound.status(ctx);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                r.put(k, o.get(k));
            }
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            r.put("exact", am != null && (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()));
            r.put("notif", NotificationManagerCompat.from(ctx).areNotificationsEnabled());
            r.put("service", FerroWorkoutService.running);
            r.put("fg", fg);
            r.put("pending", FerroTimeline.pending());
            android.os.PowerManager pm = (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            r.put("battery", pm != null && Build.VERSION.SDK_INT >= 23 && pm.isIgnoringBatteryOptimizations(ctx.getPackageName()));
            r.put("sdk", Build.VERSION.SDK_INT);
        } catch (Throwable ignored) {}
        call.resolve(r);
    }

    /** Aviso em destaque (fica ~10 s na tela), com ou sem vibração. Não toca som. */
    static void showAlert(Context ctx, String title, String body, boolean vib) {
        createChannel(ctx);
        Intent open = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, vib ? ALERT_CH : ALERT_CH_Q)
            .setSmallIcon(R.drawable.ic_stat_ferro)
            .setColor(0xFF4D96E8)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setTimeoutAfter(10000);
        if (vib) b.setVibrate(new long[] {0, 260, 140, 260});
        if (open != null) b.setContentIntent(PendingIntent.getActivity(ctx, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        try {
            NotificationManagerCompat.from(ctx).notify(ALERT_ID, b.build());
        } catch (SecurityException ignored) {}
    }

    void fire(String action) {
        JSObject d = new JSObject();
        d.put("action", action);
        notifyListeners("action", d, true);
    }

    static int parseColor(String c, int def) {
        try {
            return Color.parseColor(c);
        } catch (Exception e) {
            return def;
        }
    }

    static int iconRes(String name) {
        switch (name == null ? "" : name) {
            case "back": return R.drawable.ni_back;
            case "pause": return R.drawable.ni_pause;
            case "play": return R.drawable.ni_play;
            case "check": return R.drawable.ni_check;
            case "plus": return R.drawable.ni_plus;
            default: return R.drawable.ni_skip;
        }
    }

    static PendingIntent actionIntent(Context ctx, String id, int k) {
        Intent i = new Intent(ctx, FerroNotifReceiver.class);
        i.setAction(ACTION + "." + id);
        i.putExtra("a", id);
        return PendingIntent.getBroadcast(ctx, 100 + k, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void setChrono(RemoteViews v, String mode, long endAt, long startAt, boolean paused, long shownMs, int color) {
        if ("none".equals(mode)) {
            v.setViewVisibility(R.id.n_chrono, View.GONE);
            return;
        }
        v.setViewVisibility(R.id.n_chrono, View.VISIBLE);
        boolean down = "down".equals(mode);
        long now = System.currentTimeMillis();
        long er = SystemClock.elapsedRealtime();
        long base;
        if (paused) base = down ? er + shownMs : er - shownMs;
        else base = down ? er + (endAt - now) : er - (now - startAt);
        v.setChronometer(R.id.n_chrono, base, null, !paused);
        if (Build.VERSION.SDK_INT >= 24) v.setChronometerCountDown(R.id.n_chrono, down);
        v.setTextColor(R.id.n_chrono, color);
    }

    @PluginMethod
    public void show(PluginCall call) {
        Context ctx = getContext();
        try {
            JSONObject d = new JSONObject(call.getData().toString());
            Notification n = build(ctx, d);
            last = d;
            try {
                FerroSound.prefs(ctx).edit().putString(K_LAST, d.toString()).apply();
            } catch (Exception ignored) {}
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n);
            FerroWorkoutService.start(ctx, n);
            call.resolve();
        } catch (SecurityException e) {
            call.reject("Sem permissão para notificações");
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    /** Linha do tempo sem a página: aplica a próxima fase (rótulo, cor, cronômetro, botões) sobre o último conteúdo. */
    static void applyPatch(Context ctx, JSONObject patch) {
        JSONObject base = last;
        if (base == null) {
            try {
                String saved = FerroSound.prefs(ctx).getString(K_LAST, null);
                if (saved != null) base = new JSONObject(saved);
            } catch (Exception ignored) {}
        }
        if (base == null || patch == null) return;
        try {
            JSONObject d = new JSONObject(base.toString());
            java.util.Iterator<String> it = patch.keys();
            while (it.hasNext()) {
                String k = it.next();
                d.put(k, patch.get(k));
            }
            Notification n = build(ctx, d);
            last = d;
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n);
            FerroWorkoutService.start(ctx, n);   // atualiza a do serviço; se o serviço caiu, tenta reerguer
        } catch (Exception ignored) {}
    }

    static Notification build(Context ctx, JSONObject d) {
        createChannel(ctx);
        int col = parseColor(d.optString("color", "#F0654D"), 0xFFF0654D);
        String label = d.optString("label", "");
        String title = d.optString("title", "");
        String line1 = d.optString("line1", "");
        String line2 = d.optString("line2", "");
        String mode = d.optString("chrono", "none");
        long endAt = d.optLong("endAt", 0L);
        long startAt = d.optLong("startAt", 0L);
        long shownMs = d.optLong("shownMs", 0L);
        boolean paused = d.optBoolean("paused", false);
        org.json.JSONArray actions = d.optJSONArray("actions");
        if (actions == null) actions = new org.json.JSONArray();
        String pkg = ctx.getPackageName();
        RemoteViews small = new RemoteViews(pkg, R.layout.notif_small);
        small.setTextViewText(R.id.n_label, label);
        small.setTextColor(R.id.n_label, col);
        small.setTextViewText(R.id.n_title, title);
        setChrono(small, mode, endAt, startAt, paused, shownMs, col);

        RemoteViews big = new RemoteViews(pkg, R.layout.notif_big);
        big.setTextViewText(R.id.n_label, label);
        big.setTextColor(R.id.n_label, col);
        big.setTextViewText(R.id.n_title, title);
        big.setTextViewText(R.id.n_line1, line1);
        big.setTextViewText(R.id.n_line2, line2);
        big.setViewVisibility(R.id.n_line2, line2.isEmpty() ? View.GONE : View.VISIBLE);
        setChrono(big, mode, endAt, startAt, paused, shownMs, col);

        int[] btn = {R.id.b1, R.id.b2, R.id.b3};
        int[] ico = {R.id.b1i, R.id.b2i, R.id.b3i};
        int[] txt = {R.id.b1t, R.id.b2t, R.id.b3t};
        for (int k = 0; k < 3; k++) {
            JSONObject a = null;
            try {
                if (k < actions.length()) a = actions.getJSONObject(k);
            } catch (Exception ignored) {}
            if (a == null) {
                big.setViewVisibility(btn[k], View.GONE);
                continue;
            }
            big.setViewVisibility(btn[k], View.VISIBLE);
            big.setImageViewResource(ico[k], iconRes(a.optString("icon")));
            big.setTextViewText(txt[k], a.optString("label"));
            big.setInt(btn[k], "setBackgroundResource", a.optBoolean("primary") ? R.drawable.nbtn_primary : R.drawable.nbtn);
            big.setOnClickPendingIntent(btn[k], actionIntent(ctx, a.optString("id"), k));
        }

        Intent open = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        PendingIntent contentPi = open == null ? null : PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ferro)
            .setColor(col)
            .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(small)
            .setCustomBigContentView(big)
            .setContentTitle(label + " · " + title)
            .setContentText(line1)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        if (contentPi != null) b.setContentIntent(contentPi);
        return b.build();
    }


    @PluginMethod
    public void hide(PluginCall call) {
        final Context ctx = getContext().getApplicationContext();
        onMain(() -> FerroTimeline.clear(ctx));
        last = null;
        try {
            FerroSound.prefs(ctx).edit().remove(K_LAST).apply();
        } catch (Exception ignored) {}
        FerroWorkoutService.stop(ctx);
        NotificationManagerCompat.from(ctx).cancel(NOTIF_ID);
        NotificationManagerCompat.from(ctx).cancel(ALERT_ID);
        call.resolve();
    }
}
