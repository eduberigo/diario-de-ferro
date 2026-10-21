package com.eduberigo.diariodeferro;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Recebe os toques nos botões da notificação e repassa ao app, sem abrir a tela. */
public class FerroNotifReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getStringExtra("a");
        FerroNotifPlugin p = FerroNotifPlugin.instance;
        if (a != null && p != null && p.getBridge() != null) p.fire(a);
    }
}
