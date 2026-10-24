package com.eduberigo.diariodeferro;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Dispara o aviso de fim do descanso/preparo na hora certa, mesmo com o app fechado. */
public class FerroAlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
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
