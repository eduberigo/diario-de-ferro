package com.eduberigo.diariodeferro.wear;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Estado do treino recebido do celular (o mesmo conteúdo da notificação) e envio de mensagens ao celular. */
final class WatchState {

    private WatchState() {}

    static final Handler MAIN = new Handler(Looper.getMainLooper());
    static volatile JSONObject state;      // null = sem treino
    static volatile long stateTs = 0L;
    static volatile int hr = 0;            // último batimento medido aqui
    static volatile long hrAt = 0L;
    static volatile Runnable listener;     // a tela, quando aberta

    static boolean active() {
        return state != null;
    }

    static void update(Context ctx, String json, long ts) {
        if (ts != 0 && ts < stateTs) return;   // chegou fora de ordem
        stateTs = ts;
        JSONObject o = null;
        try {
            if (json != null && !json.isEmpty()) o = new JSONObject(json);
        } catch (Exception ignored) {}
        state = o;
        if (o == null) WatchService.stop(ctx);
        notifyUi();
    }

    static void notifyUi() {
        final Runnable l = listener;
        if (l != null) MAIN.post(l);
    }

    static void send(Context c, final String path, final String text) {
        final Context ctx = c.getApplicationContext();
        try {
            Wearable.getNodeClient(ctx).getConnectedNodes().addOnSuccessListener(nodes -> {
                for (Node n : nodes) {
                    try {
                        Wearable.getMessageClient(ctx).sendMessage(n.getId(), path, text.getBytes(StandardCharsets.UTF_8));
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("deprecation")
    static void vibrate(Context ctx, long[] pattern) {
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1));
            else v.vibrate(pattern, -1);
        } catch (Throwable ignored) {}
    }
}
