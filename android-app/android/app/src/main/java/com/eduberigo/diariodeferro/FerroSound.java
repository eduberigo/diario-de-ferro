package com.eduberigo.diariodeferro;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.audiofx.LoudnessEnhancer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.io.File;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/**
 * Todo o som do treino no app Android sai por aqui, com o app na frente ou em segundo plano:
 * bipes, sinos, apitos, contagem regressiva e a voz. Um caminho só significa um volume só e
 * uma regra só de saída (fone ou alto-falante).
 *
 * - Sem fone: canal de alarme (não segue o modo silencioso) e, se configurado, o volume de
 *   alarme sobe durante o aviso e volta ao que era depois.
 * - Com fone ou Bluetooth: canal de mídia, para o som sair só no fone.
 * - Enquanto há som, a música dos outros apps abaixa; sons em sequência (3, 2, 1) mantêm a
 *   música baixa até o último terminar.
 *
 * Os métodos que tocam som devem ser chamados na thread principal (use MAIN.post).
 */
final class FerroSound {

    private FerroSound() {}

    static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String PREFS = "ferro_sound";
    private static final String K_SAVED = "savedAlarmVol";

    // ---------- configuração (vem da página; fica guardada para valer se o processo reiniciar) ----------
    private static boolean cfgLoaded = false;
    private static int volPct = 100;   // % do volume de alarme durante o aviso, sem fone (0 = não mexer)
    private static int boostMb = 600;  // reforço de ganho em milibéis (0 = desligado)

    static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void loadConfig(Context ctx) {
        if (cfgLoaded) return;
        cfgLoaded = true;
        try {
            SharedPreferences p = prefs(ctx);
            volPct = p.getInt("volPct", 100);
            boostMb = p.getInt("boostMb", 600);
            // o app foi encerrado no meio de um aviso, com o volume de alarme ainda alto: devolve o que era
            int pend = p.getInt(K_SAVED, -1);
            if (pend >= 0) {
                AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) am.setStreamVolume(AudioManager.STREAM_ALARM, pend, 0);
                p.edit().remove(K_SAVED).apply();
            }
        } catch (Exception ignored) {}
    }

    static void config(Context ctx, int pct, int boost) {
        volPct = Math.max(0, Math.min(100, pct));
        boostMb = Math.max(0, Math.min(1500, boost));
        cfgLoaded = true;
        try {
            prefs(ctx).edit().putInt("volPct", volPct).putInt("boostMb", boostMb).apply();
        } catch (Exception ignored) {}
    }

    // ---------- saída: fone/Bluetooth ou alto-falante ----------
    static boolean headset(Context ctx) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;
        try {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    || t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    || t == AudioDeviceInfo.TYPE_USB_HEADSET) return true;
                if (Build.VERSION.SDK_INT >= 31 && (t == AudioDeviceInfo.TYPE_BLE_HEADSET || t == AudioDeviceInfo.TYPE_BLE_SPEAKER)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    static AudioAttributes attrs(boolean headset, boolean speech) {
        return new AudioAttributes.Builder()
            .setUsage(headset ? AudioAttributes.USAGE_MEDIA : AudioAttributes.USAGE_ALARM)
            .setContentType(speech ? AudioAttributes.CONTENT_TYPE_SPEECH : AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();
    }

    /** "snd:apito" = res/raw/ferro_s_apito.wav; nome desconhecido cai no bipe. */
    static int soundRes(Context ctx, String sound) {
        if (sound != null && sound.startsWith("snd:")) {
            int id = ctx.getResources().getIdentifier("ferro_s_" + sound.substring(4), "raw", ctx.getPackageName());
            if (id != 0) return id;
            return R.raw.ferro_s_bipe;
        }
        return "bell".equals(sound) ? R.raw.ferro_s_sino : R.raw.ferro_s_bipe3;
    }

    // ---------- "momento sonoro": música baixa + volume do aviso, até o fim do último som ----------
    private static Context app;
    private static AudioFocusRequest afr;
    private static final AudioManager.OnAudioFocusChangeListener LISTENER = f -> {};
    private static boolean focusHeld = false;
    private static int savedVol = -1;
    private static long holdUntil = 0L;
    private static PowerManager.WakeLock holdWake;   // CPU acordada até devolver a música e o volume (senão, com a tela apagada, ficariam presos)
    private static final Runnable END = FerroSound::end;

    @SuppressWarnings("deprecation")
    static void hold(Context ctx, AudioAttributes a, boolean headset, long ms) {
        app = ctx.getApplicationContext();
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            if (!focusHeld) {
                try {
                    int r;
                    if (Build.VERSION.SDK_INT >= 26) {
                        afr = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                            .setAudioAttributes(a)
                            .setOnAudioFocusChangeListener(LISTENER)
                            .build();
                        r = am.requestAudioFocus(afr);
                    } else {
                        r = am.requestAudioFocus(LISTENER, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
                    }
                    focusHeld = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                } catch (Exception ignored) {}
            }
            if (!headset && volPct > 0 && savedVol < 0) {
                try {
                    int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                    int cur = am.getStreamVolume(AudioManager.STREAM_ALARM);
                    int want = Math.max(1, Math.round(max * volPct / 100f));
                    if (want > cur) {
                        prefs(app).edit().putInt(K_SAVED, cur).apply();   // se o app morrer antes de devolver, devolve na próxima abertura
                        am.setStreamVolume(AudioManager.STREAM_ALARM, want, 0);
                        savedVol = cur;
                    }
                } catch (Exception ignored) {}
            }
        }
        try {
            if (holdWake == null) {
                PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    holdWake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "diariodeferro:som");
                    holdWake.setReferenceCounted(false);
                }
            }
            if (holdWake != null) holdWake.acquire(Math.max(200L, ms) + 2000L);
        } catch (Throwable ignored) {}
        long until = SystemClock.uptimeMillis() + Math.max(200L, ms);
        if (until > holdUntil) holdUntil = until;
        MAIN.removeCallbacks(END);
        MAIN.postAtTime(END, holdUntil);
    }

    @SuppressWarnings("deprecation")
    private static void end() {
        if (SystemClock.uptimeMillis() < holdUntil - 10L) {
            MAIN.postAtTime(END, holdUntil);
            return;
        }
        Context ctx = app;
        AudioManager am = ctx == null ? null : (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            if (savedVol >= 0) {
                try {
                    am.setStreamVolume(AudioManager.STREAM_ALARM, savedVol, 0);
                } catch (Exception ignored) {}
            }
            if (focusHeld) {
                try {
                    if (Build.VERSION.SDK_INT >= 26 && afr != null) am.abandonAudioFocusRequest(afr);
                    else am.abandonAudioFocus(LISTENER);
                } catch (Exception ignored) {}
            }
        }
        if (savedVol >= 0 && ctx != null) {
            try {
                prefs(ctx).edit().remove(K_SAVED).apply();
            } catch (Exception ignored) {}
        }
        savedVol = -1;
        focusHeld = false;
        afr = null;
    }

    // ---------- tocar um som ----------
    static void play(Context c, String sound) {
        play(c, sound, 0L);
    }

    /** holdMs: manter a música baixa por pelo menos esse tempo (contagem: evita a música subir entre um bipe e outro). */
    static void play(Context c, String sound, long holdMs) {
        final Context ctx = c.getApplicationContext();
        playUri(ctx, Uri.parse("android.resource://" + ctx.getPackageName() + "/" + soundRes(ctx, sound)), holdMs, false);
    }

    /** Toca um arquivo com o volume e o reforço configurados. Devolve false se não deu para tocar. */
    private static boolean playUri(Context c, Uri uri, long holdMs, boolean speech) {
        final Context ctx = c.getApplicationContext();
        loadConfig(ctx);
        final boolean hs = headset(ctx);
        final AudioAttributes a = attrs(hs, speech);
        final MediaPlayer mp = new MediaPlayer();
        final LoudnessEnhancer[] le = new LoudnessEnhancer[1];
        try {
            mp.setAudioAttributes(a);
            mp.setDataSource(ctx, uri);
            mp.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK);
            mp.setOnCompletionListener(m -> release(m, le[0]));
            mp.setOnErrorListener((m, w, x) -> {
                release(m, le[0]);
                return true;
            });
            mp.prepare();
            mp.setVolume(1f, 1f);
            if (boostMb > 0) {
                try {
                    le[0] = new LoudnessEnhancer(mp.getAudioSessionId());
                    le[0].setTargetGain(boostMb);
                    le[0].setEnabled(true);
                } catch (Throwable t) {
                    le[0] = null;
                }
            }
            int d = mp.getDuration();
            hold(ctx, a, hs, Math.max(holdMs, (d > 0 ? d : 2500) + 500L));
            mp.start();
            return true;
        } catch (Throwable t) {
            release(mp, le[0]);
            return false;
        }
    }

    private static void release(MediaPlayer m, LoudnessEnhancer le) {
        try {
            if (le != null) le.release();
        } catch (Throwable ignored) {}
        try {
            m.release();
        } catch (Throwable ignored) {}
    }

    // ---------- voz do Android (contagem falada) ----------
    // Os números são gravados em arquivo uma vez, quando a voz fica pronta, e tocados pelo mesmo
    // tocador dos bipes: saem com o mesmo volume e reforço, e na hora (a voz ao vivo tem atraso).
    private static TextToSpeech tts;
    private static boolean ttsStarted = false;
    private static volatile boolean ttsOk = false;
    private static volatile boolean ttsPt = false;
    private static final ConcurrentHashMap<String, File> SPOKEN = new ConcurrentHashMap<>();
    private static final AtomicInteger RENDERING = new AtomicInteger(0);
    private static final int SPOKEN_MAX = 10;

    static void ttsInit(Context c) {
        if (ttsStarted) return;
        ttsStarted = true;
        final Context ctx = c.getApplicationContext();
        try {
            tts = new TextToSpeech(ctx, st -> {
                try {
                    if (st == TextToSpeech.SUCCESS && tts != null) {
                        int r = tts.setLanguage(new Locale("pt", "BR"));
                        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) r = tts.setLanguage(new Locale("pt"));
                        ttsPt = !(r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED);
                        tts.setSpeechRate(1.15f);
                        ttsOk = true;
                        prerender(ctx);
                    }
                } catch (Throwable ignored) {}
            });
        } catch (Throwable t) {
            tts = null;
        }
    }

    private static void prerender(Context ctx) {
        try {
            final File dir = new File(ctx.getCacheDir(), "ferro_voz");
            if (!dir.exists() && !dir.mkdirs()) return;
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String id) {}

                @Override
                public void onDone(String id) {
                    rendered(dir, id, true);
                }

                @Override
                public void onError(String id) {
                    rendered(dir, id, false);
                }
            });
            for (int n = 1; n <= SPOKEN_MAX; n++) {
                String text = String.valueOf(n);
                File f = new File(dir, "n" + text + ".wav");
                if (tts.synthesizeToFile(text, new Bundle(), f, "arq:" + text) == TextToSpeech.SUCCESS) RENDERING.incrementAndGet();
            }
        } catch (Throwable ignored) {}
    }

    private static void rendered(File dir, String id, boolean ok) {
        if (id == null || !id.startsWith("arq:")) return;
        RENDERING.decrementAndGet();
        String text = id.substring(4);
        File f = new File(dir, "n" + text + ".wav");
        if (ok && f.length() > 800L) SPOKEN.put(text, f);
    }

    /** Fala o texto; se a voz do aparelho não estiver pronta, toca o som alternativo (nunca fica mudo). */
    static void say(Context c, String text, String alt) {
        final Context ctx = c.getApplicationContext();
        loadConfig(ctx);
        ttsInit(ctx);
        boolean spoke = false;
        if (text != null && !text.isEmpty()) {
            File f = SPOKEN.get(text);
            if (f != null && f.length() > 800L) spoke = playUri(ctx, Uri.fromFile(f), 1300L, true);
            if (!spoke && ttsOk && tts != null) {
                try {
                    boolean hs = headset(ctx);
                    AudioAttributes a = attrs(hs, true);
                    tts.setAudioAttributes(a);
                    hold(ctx, a, hs, 1600L);
                    Bundle b = new Bundle();
                    b.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f);
                    // enquanto os números ainda estão sendo gravados, não esvazia a fila (apagaria as gravações pendentes)
                    int mode = RENDERING.get() > 0 ? TextToSpeech.QUEUE_ADD : TextToSpeech.QUEUE_FLUSH;
                    spoke = tts.speak(text, mode, b, "ferro-" + text) == TextToSpeech.SUCCESS;
                } catch (Throwable ignored) {}
            }
        }
        if (!spoke && alt != null && !alt.isEmpty()) play(ctx, alt, 1300L);
    }

    // ---------- vibração (com o app na frente; em segundo plano quem vibra é o aviso em destaque) ----------
    @SuppressWarnings("deprecation")
    static void vibrate(Context ctx) {
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            long[] p = {0, 200, 100, 200};
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(p, -1));
            else v.vibrate(p, -1);
        } catch (Throwable ignored) {}
    }

    // ---------- diagnóstico ----------
    static JSONObject status(Context c) {
        final Context ctx = c.getApplicationContext();
        loadConfig(ctx);
        JSONObject o = new JSONObject();
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            o.put("headset", headset(ctx));
            if (am != null) {
                o.put("alarmVol", am.getStreamVolume(AudioManager.STREAM_ALARM));
                o.put("alarmMax", am.getStreamMaxVolume(AudioManager.STREAM_ALARM));
                o.put("mediaVol", am.getStreamVolume(AudioManager.STREAM_MUSIC));
                o.put("mediaMax", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                o.put("ringer", am.getRingerMode());
            }
            o.put("volPct", volPct);
            o.put("boostMb", boostMb);
            o.put("tts", ttsOk);
            o.put("ttsPt", ttsPt);
            o.put("ttsFiles", SPOKEN.size());
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && Build.VERSION.SDK_INT >= 23) o.put("dnd", nm.getCurrentInterruptionFilter());
        } catch (Throwable ignored) {}
        return o;
    }
}
