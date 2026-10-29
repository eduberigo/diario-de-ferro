package com.eduberigo.diariodeferro;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

/**
 * Serviço em primeiro plano enquanto há um treino em andamento.
 * Sem ele, o Android congela o app em segundo plano: a notificação continua na tela,
 * mas os toques em Pular, Pausar, Feito etc. não chegam ao app e o cronômetro não avança.
 * Usa a mesma notificação do treino (mesmo id), então não aparece uma segunda notificação.
 */
public class FerroWorkoutService extends Service {

    static volatile Notification current;
    static volatile boolean running = false;

    static void start(Context ctx, Notification n) {
        current = n;
        if (running) return;
        try {
            ContextCompat.startForegroundService(ctx, new Intent(ctx, FerroWorkoutService.class));
        } catch (Exception e) {
            // Android 12+ não deixa iniciar com o app em segundo plano; a notificação comum continua valendo
        }
    }

    static void stop(Context ctx) {
        current = null;
        try {
            ctx.stopService(new Intent(ctx, FerroWorkoutService.class));
        } catch (Exception ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = current;
        if (n == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(FerroNotifPlugin.NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(FerroNotifPlugin.NOTIF_ID, n);
            }
        } catch (Exception e) {
            running = false;
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // app fechado pela lista de recentes: some com a notificação, que ficaria com botões mortos
        NotificationManagerCompat.from(this).cancel(FerroNotifPlugin.NOTIF_ID);
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        running = false;
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
