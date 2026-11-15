package com.eduberigo.diariodeferro.wear;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;
import androidx.wear.ongoing.OngoingActivity;

/**
 * Mede os batimentos durante o treino e manda ao celular a cada 5 s.
 * Serviço em primeiro plano (tipo "health"): continua medindo com a tela do relógio apagada.
 * Mostra um atalho no mostrador para voltar à tela do treino.
 */
public class WatchService extends Service implements SensorEventListener {

    static final String CH = "ferro-relogio";
    static final int NID = 7101;
    static volatile boolean running = false;
    private SensorManager sm;
    private PowerManager.WakeLock wl;
    private int last = 0;
    private long lastAt = 0L, startedAt = 0L;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            if (last > 0 && now - lastAt < 10000L) WatchState.send(WatchService.this, "/ferro/hr", String.valueOf(last));
            if (!WatchState.active() || now - startedAt > 4L * 3600000L) {   // treino acabou (ou 4 h de segurança)
                stopSelf();
                return;
            }
            WatchState.MAIN.postDelayed(this, 5000L);
        }
    };

    static boolean canMeasure(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED
            || (Build.VERSION.SDK_INT >= 36 && ctx.checkSelfPermission("android.permission.health.READ_HEART_RATE") == PackageManager.PERMISSION_GRANTED);
    }

    /** Só a tela chama (o Android não deixa um serviço desses começar com o app em segundo plano). */
    static void start(Context ctx) {
        if (running || !canMeasure(ctx)) return;
        try {
            ctx.startForegroundService(new Intent(ctx, WatchService.class));
        } catch (Throwable ignored) {}
    }

    static void stop(Context ctx) {
        if (!running) return;
        try {
            ctx.stopService(new Intent(ctx, WatchService.class));
        } catch (Throwable ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) return START_NOT_STICKY;
        try {
            Notification n = notif();
            if (Build.VERSION.SDK_INT >= 34) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH);
            else startForeground(NID, n);
        } catch (Throwable t) {
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        startedAt = System.currentTimeMillis();
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "diariodeferro:relogio");
            wl.setReferenceCounted(false);
            wl.acquire(4L * 3600000L);
        } catch (Throwable ignored) {}
        try {
            sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            Sensor s = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_HEART_RATE);
            if (s != null) sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL);
        } catch (Throwable ignored) {}
        WatchState.MAIN.postDelayed(tick, 5000L);
        return START_NOT_STICKY;
    }

    private Notification notif() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, "Treino em andamento", NotificationManager.IMPORTANCE_LOW);
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, WatchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_ferro)
            .setContentTitle("Treino em andamento")
            .setContentText("Medindo os batimentos")
            .setCategory("workout")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pi);
        try {
            new OngoingActivity.Builder(getApplicationContext(), NID, b)
                .setStaticIcon(R.drawable.ic_stat_ferro)
                .setTouchIntent(pi)
                .build()
                .apply(getApplicationContext());
        } catch (Throwable ignored) {}
        return b.build();
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.values == null || e.values.length == 0) return;
        if (e.accuracy == SensorManager.SENSOR_STATUS_NO_CONTACT || e.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) return;
        int bpm = Math.round(e.values[0]);
        if (bpm < 30 || bpm > 230) return;
        last = bpm;
        lastAt = System.currentTimeMillis();
        WatchState.hr = bpm;
        WatchState.hrAt = lastAt;
        WatchState.notifyUi();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public void onDestroy() {
        running = false;
        WatchState.MAIN.removeCallbacks(tick);
        try {
            if (sm != null) sm.unregisterListener(this);
        } catch (Throwable ignored) {}
        try {
            if (wl != null && wl.isHeld()) wl.release();
        } catch (Throwable ignored) {}
        WatchState.hr = 0;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
