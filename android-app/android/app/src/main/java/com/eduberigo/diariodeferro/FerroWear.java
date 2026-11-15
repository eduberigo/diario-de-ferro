package com.eduberigo.diariodeferro;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.wear.remote.interactions.RemoteActivityHelper;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * Ponte com o app do relógio (Wear OS), pela Data Layer do Google Play services.
 * - estado do treino (o mesmo conteúdo da notificação) → item de dados "/ferro/state";
 * - avisos de fim de fase → mensagem "/ferro/alert" (o relógio vibra);
 * - do relógio chegam: botões (/ferro/act), batimentos (/ferro/hr) e pedido de estado (/ferro/hello) — ver FerroWearListener.
 * Relógio e celular só conversam se tiverem o mesmo pacote e a mesma assinatura. Sem relógio, tudo aqui vira nada.
 */
final class FerroWear {

    private FerroWear() {}

    static final String STATE = "/ferro/state";
    static volatile long lastSeen = 0L;     // última mensagem recebida do relógio
    private static int hrSum = 0, hrN = 0, hrMax = 0, hrLast = 0;
    private static long hrAt = 0L;

    /** Relógio respondeu nos últimos 10 min: o aviso em destaque do celular não precisa ir para o relógio também. */
    static boolean active() {
        return System.currentTimeMillis() - lastSeen < 10 * 60000L;
    }

    /** Manda o estado atual (null = treino encerrado). */
    static void push(Context c, JSONObject d) {
        final Context ctx = c.getApplicationContext();
        try {
            PutDataMapRequest m = PutDataMapRequest.create(STATE);
            m.getDataMap().putString("json", d == null ? "" : d.toString());
            m.getDataMap().putLong("ts", System.currentTimeMillis());
            PutDataRequest r = m.asPutDataRequest().setUrgent();
            Wearable.getDataClient(ctx).putDataItem(r);
        } catch (Throwable ignored) {}
    }

    /** Fim de fase: o relógio vibra e mostra o título. */
    static void alert(Context c, String title, boolean vib) {
        send(c, "/ferro/alert", (vib ? "1|" : "0|") + (title == null ? "" : title));
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

    /** Abre o app no relógio (começo do treino). */
    static void open(Context c) {
        final Context ctx = c.getApplicationContext();
        try {
            Wearable.getNodeClient(ctx).getConnectedNodes().addOnSuccessListener(nodes -> {
                RemoteActivityHelper h = new RemoteActivityHelper(ctx, Executors.newSingleThreadExecutor());
                for (Node n : nodes) {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_BROWSABLE).setData(Uri.parse("ferro://treino"));
                        h.startRemoteActivity(i, n.getId());
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
    }

    // ---------- batimentos que chegam do relógio ----------
    static synchronized void onHr(Context ctx, int bpm) {
        if (bpm < 30 || bpm > 230) return;
        hrSum += bpm;
        hrN++;
        if (bpm > hrMax) hrMax = bpm;
        hrLast = bpm;
        hrAt = System.currentTimeMillis();
        FerroNotifPlugin p = FerroNotifPlugin.instance;
        if (p != null) {
            try {
                p.hrEvent(bpm);
            } catch (Throwable ignored) {}
        }
    }

    static synchronized JSONObject hrStats(boolean reset) {
        JSONObject o = new JSONObject();
        try {
            o.put("n", hrN);
            o.put("avg", hrN > 0 ? Math.round((float) hrSum / hrN) : 0);
            o.put("max", hrMax);
            o.put("last", System.currentTimeMillis() - hrAt < 30000L ? hrLast : 0);
            o.put("watch", active());
        } catch (Exception ignored) {}
        if (reset) {
            hrSum = 0;
            hrN = 0;
            hrMax = 0;
            hrLast = 0;
        }
        return o;
    }
}
