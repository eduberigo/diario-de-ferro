package com.eduberigo.diariodeferro;

import android.app.AlarmManager;
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
import android.media.MediaPlayer;
import android.net.Uri;
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
    static volatile JSONObject last;          // último conteúdo da notificação do treino (para o alarme avançar sozinho)
    static volatile boolean fg = true;        // app na frente: quem toca os sons é a página
    static volatile long lastTouch = 0L;      // último toque do usuário (app ou notificação): para os lembretes

    @Override
    protected void handleOnResume() { fg = true; lastTouch = System.currentTimeMillis(); }

    @Override
    protected void handleOnPause() { fg = false; }

    @Override
    public void load() {
        instance = this;
        createChannel(getContext());
        ttsInit(getContext());
    }

    // ---------- voz do Android para a contagem regressiva (a página não tem voz dentro do app) ----------
    static android.speech.tts.TextToSpeech tts;
    static volatile boolean ttsOk = false;

    static void ttsInit(Context ctx) {
        if (tts != null) return;
        try {
            tts = new android.speech.tts.TextToSpeech(ctx.getApplicationContext(), st -> {
                if (st == android.speech.tts.TextToSpeech.SUCCESS && tts != null) {
                    tts.setLanguage(new java.util.Locale("pt", "BR"));
                    tts.setSpeechRate(1.15f);
                    ttsOk = true;
                }
            });
        } catch (Exception ignored) {}
    }

    static void say(Context ctx, String text) {
        ttsInit(ctx);
        if (!ttsOk || text == null || text.isEmpty()) return;
        try {
            tts.setAudioAttributes(attrsFor(ctx, true));
            android.os.Bundle b = new android.os.Bundle();
            b.putFloat(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f);
            tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, b, "ferro");
        } catch (Exception ignored) {}
    }

    @PluginMethod
    public void speak(PluginCall call) {
        say(getContext(), call.getString("text", ""));
        call.resolve();
    }

    /** fone ou Bluetooth conectado? (aí o som vai só para eles, pelo canal de mídia) */
    static boolean headset(Context ctx) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;
        try {
            for (android.media.AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (t == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    || t == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET || t == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    || t == android.media.AudioDeviceInfo.TYPE_USB_HEADSET) return true;
                if (Build.VERSION.SDK_INT >= 31 && (t == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET || t == android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** sem fone: canal de alarme (alto, segue o volume de alarme); com fone/Bluetooth: canal de mídia, só no fone
        (o Android toca alarme no alto-falante do celular também, mesmo com Bluetooth) */
    static AudioAttributes attrsFor(Context ctx, boolean speech) {
        boolean hs = headset(ctx);
        return new AudioAttributes.Builder()
            .setUsage(hs ? AudioAttributes.USAGE_MEDIA : AudioAttributes.USAGE_ALARM)
            .setContentType(speech ? AudioAttributes.CONTENT_TYPE_SPEECH : AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();
    }

    /** som pelo nome: "snd:apito" = res/raw/ferro_s_apito.wav; "bell"/"beep3" = os antigos */
    static int soundRes(Context ctx, String sound) {
        if (sound != null && sound.startsWith("snd:")) {
            int id = ctx.getResources().getIdentifier("ferro_s_" + sound.substring(4), "raw", ctx.getPackageName());
            if (id != 0) return id;
        }
        return "bell".equals(sound) ? R.raw.ferro_bell : R.raw.ferro_beep3;
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
        try {
            nm.deleteNotificationChannel("ferro-treino");
        } catch (Exception ignored) {}
    }

    // ---------- aviso de fim do cronômetro (alarme próprio, funciona com o app em segundo plano) ----------
    static final String ALERT_CH = "ferro-aviso";
    static final int ALERT_ID = 7002;

    static PendingIntent alarmIntent(Context ctx, String title, String body, String sound) {
        return alarmIntent(ctx, title, body, sound, 0L, null, null, null);
    }

    /** next*: um segundo aviso encadeado (ex.: fim do preparo logo depois do fim do descanso), agendado pelo próprio receptor */
    static PendingIntent alarmIntent(Context ctx, String title, String body, String sound, long nextAt, String nextTitle, String nextBody, String nextSound) {
        Intent i = new Intent(ctx, FerroAlarmReceiver.class);
        i.setAction(ACTION + ".ALARM");
        if (title != null) i.putExtra("title", title);
        if (body != null) i.putExtra("body", body);
        if (sound != null) i.putExtra("sound", sound);
        if (nextAt > 0) {
            i.putExtra("nextAt", nextAt);
            i.putExtra("nextTitle", nextTitle);
            i.putExtra("nextBody", nextBody);
            i.putExtra("nextSound", nextSound);
        }
        return PendingIntent.getBroadcast(ctx, 200, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @PluginMethod
    public void alarm(PluginCall call) {
        Context ctx = getContext();
        createChannel(ctx);
        long at = call.getData().optLong("at", 0L);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null || at <= System.currentTimeMillis()) {
            call.resolve();
            return;
        }
        String steps = call.getString("steps", null);
        PendingIntent pi = steps != null ? stepsIntent(ctx, steps)
            : alarmIntent(ctx, call.getString("title", "Diário de Ferro"), call.getString("body", ""), call.getString("sound", "beep3"));
        schedule(am, at, pi);
        call.resolve();
    }

    /** Sequência de avisos: cada um com hora, som, texto e, se houver, a mudança da notificação; o receptor agenda o seguinte. */
    static PendingIntent stepsIntent(Context ctx, String steps) {
        Intent i = new Intent(ctx, FerroAlarmReceiver.class);
        i.setAction(ACTION + ".ALARM");
        i.putExtra("steps", steps);
        return PendingIntent.getBroadcast(ctx, 200, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
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
        Context ctx = getContext();
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(alarmIntent(ctx, null, null, null));
        call.resolve();
    }

    @PluginMethod
    public void clearAlert(PluginCall call) {
        NotificationManagerCompat.from(getContext()).cancel(ALERT_ID);
        call.resolve();
    }

    /** Mostra o aviso (fica ~10 s na tela) e toca o som abaixando a música só durante ele. */
    static void fireAlert(Context ctx, String title, String body, String sound, Runnable done) {
        createChannel(ctx);
        Intent open = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, ALERT_CH)
            .setSmallIcon(R.drawable.ic_stat_ferro)
            .setColor(0xFF4D96E8)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setVibrate(new long[] {0, 260, 140, 260})
            .setSilent(false)
            .setAutoCancel(true)
            .setTimeoutAfter(10000);
        if (open != null) b.setContentIntent(PendingIntent.getActivity(ctx, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        try {
            NotificationManagerCompat.from(ctx).notify(ALERT_ID, b.build());
        } catch (SecurityException ignored) {}
        playWithDuck(ctx, soundRes(ctx, sound), done);
    }

    @SuppressWarnings("deprecation")
    static void playWithDuck(Context ctx, int res, Runnable done) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        AudioAttributes attrs = attrsFor(ctx, false);
        final AudioFocusRequest req = Build.VERSION.SDK_INT >= 26
            ? new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).setOnAudioFocusChangeListener(f -> {}).build()
            : null;
        final AudioManager.OnAudioFocusChangeListener legacy = f -> {};
        if (am != null) {
            if (req != null) am.requestAudioFocus(req);
            else am.requestAudioFocus(legacy, AudioManager.STREAM_NOTIFICATION, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        }
        final Handler h = new Handler(Looper.getMainLooper());
        final Runnable finish = () -> {
            if (am != null) {
                if (req != null) am.abandonAudioFocusRequest(req);
                else am.abandonAudioFocus(legacy);
            }
            if (done != null) done.run();
        };
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(attrs);
            mp.setDataSource(ctx, Uri.parse("android.resource://" + ctx.getPackageName() + "/" + res));
            mp.setOnCompletionListener(m -> {
                m.release();
                h.postDelayed(finish, 350);
            });
            mp.setOnErrorListener((m, w, x) -> {
                m.release();
                finish.run();
                return true;
            });
            mp.prepare();
            mp.setVolume(1f, 1f);
            mp.start();
        } catch (Exception e) {
            finish.run();
        }
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
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n);
            FerroWorkoutService.start(ctx, n);
            call.resolve();
        } catch (SecurityException e) {
            call.reject("Sem permissão para notificações");
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    /** Alarme sem a página: aplica a próxima fase (rótulo, cor, cronômetro, botões) sobre o último conteúdo. */
    static void applyPatch(Context ctx, JSONObject patch) {
        JSONObject base = last;
        if (base == null || patch == null) return;
        try {
            JSONObject d = new JSONObject(base.toString());
            java.util.Iterator<String> it = patch.keys();
            while (it.hasNext()) { String k = it.next(); d.put(k, patch.get(k)); }
            Notification n = build(ctx, d);
            last = d;
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n);
            FerroWorkoutService.current = n;
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
        FerroWorkoutService.stop(getContext());
        NotificationManagerCompat.from(getContext()).cancel(NOTIF_ID);
        call.resolve();
    }
}
