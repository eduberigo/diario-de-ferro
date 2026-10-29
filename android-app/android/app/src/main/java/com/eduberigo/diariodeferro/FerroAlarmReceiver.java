package com.eduberigo.diariodeferro;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Dispara o aviso de fim do descanso/preparo na hora certa, mesmo com o app fechado, e avisa o app para avançar a fase. */
public class FerroAlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        // acorda o app para avançar a fase na hora certa (o relógio da página pode atrasar em segundo plano)
        FerroNotifPlugin p = FerroNotifPlugin.instance;
        if (p != null && p.getBridge() != null) {
            try { p.fire("tick"); } catch (Exception ignored) {}
        }
        final PendingResult pr = goAsync();
        FerroNotifPlugin.fireAlert(
            ctx.getApplicationContext(),
            intent.getStringExtra("title"),
            intent.getStringExtra("body"),
            intent.getStringExtra("sound"),
            pr::finish
        );
    }
}
