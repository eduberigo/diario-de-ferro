package com.eduberigo.diariodeferro;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import com.getcapacitor.JSArray;
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

    @Override
    public void load() {
        instance = this;
        createChannel(getContext());
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
        try {
            nm.deleteNotificationChannel("ferro-treino");
        } catch (Exception ignored) {}
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
        createChannel(ctx);
        int col = parseColor(call.getString("color", "#F0654D"), 0xFFF0654D);
        String label = call.getString("label", "");
        String title = call.getString("title", "");
        String line1 = call.getString("line1", "");
        String line2 = call.getString("line2", "");
        String mode = call.getString("chrono", "none");
        long endAt = call.getData().optLong("endAt", 0L);
        long startAt = call.getData().optLong("startAt", 0L);
        long shownMs = call.getData().optLong("shownMs", 0L);
        boolean paused = call.getBoolean("paused", false);
        JSArray actions = call.getArray("actions", new JSArray());

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

        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, b.build());
            call.resolve();
        } catch (SecurityException e) {
            call.reject("Sem permissão para notificações");
        }
    }

    // ---------- foco de áudio: abaixa a música só durante os sons do app ----------
    private AudioManager am;
    private AudioFocusRequest afr;
    private boolean focusHeld = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable releaseFocus = this::releaseFocus;
    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {};

    @SuppressWarnings("deprecation")
    private void releaseFocus() {
        if (am == null || !focusHeld) return;
        if (Build.VERSION.SDK_INT >= 26 && afr != null) am.abandonAudioFocusRequest(afr);
        else am.abandonAudioFocus(focusListener);
        focusHeld = false;
    }

    @SuppressWarnings("deprecation")
    @PluginMethod
    public void duck(PluginCall call) {
        long ms = Math.max(300L, Math.min(30000L, call.getData().optLong("ms", 1500L)));
        handler.post(() -> {
            if (am == null) am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            if (!focusHeld) {
                int r;
                if (Build.VERSION.SDK_INT >= 26) {
                    if (afr == null) {
                        afr = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                            .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build())
                            .setOnAudioFocusChangeListener(focusListener)
                            .build();
                    }
                    r = am.requestAudioFocus(afr);
                } else {
                    r = am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
                }
                focusHeld = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            }
            handler.removeCallbacks(releaseFocus);
            handler.postDelayed(releaseFocus, ms);
        });
        call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        handler.removeCallbacks(releaseFocus);
        releaseFocus();
    }

    @PluginMethod
    public void hide(PluginCall call) {
        NotificationManagerCompat.from(getContext()).cancel(NOTIF_ID);
        call.resolve();
    }
}
