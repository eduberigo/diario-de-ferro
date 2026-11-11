package com.eduberigo.diariodeferro;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Alarme de reserva da linha do tempo do treino (ver FerroTimeline): acorda o aparelho na hora
 * do próximo aviso importante. Se o processo ainda está vivo, só confere se algum passo ficou
 * para trás; se foi reiniciado, assume a sequência que veio junto com o alarme.
 */
public class FerroAlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        final Context app = ctx.getApplicationContext();
        String steps = intent.getStringExtra("steps");
        if (steps == null) return;
        long keep = 500L;
        try {
            keep = FerroTimeline.onAlarm(app, intent.getStringExtra("key"), steps);
        } catch (Throwable ignored) {}
        final PendingResult pr = goAsync();
        FerroSound.MAIN.postDelayed(() -> {
            try {
                pr.finish();
            } catch (Throwable ignored) {}
        }, keep);
    }
}
