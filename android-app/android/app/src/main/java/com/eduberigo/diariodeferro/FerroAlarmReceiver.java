package com.eduberigo.diariodeferro;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Avisos do treino na hora certa, mesmo com a página parada em segundo plano:
 * fim do descanso, fim do preparo e o apito repetido até o usuário responder.
 * Cada etapa toca o som (se o app não está na frente), avança a notificação e avisa a página.
 */
public class FerroAlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Context app = ctx.getApplicationContext();
        String title = intent.getStringExtra("title"), body = intent.getStringExtra("body"), sound = intent.getStringExtra("sound");
        boolean silent = false;
        String stepsS = intent.getStringExtra("steps");
        if (stepsS != null) {
            try {
                JSONArray steps = new JSONArray(stepsS);
                JSONObject cur = steps.getJSONObject(0);
                boolean rem = cur.optBoolean("rem", false);
                if (rem && FerroNotifPlugin.lastTouch > cur.optLong("from", 0L)) return;   // já respondeu: para os lembretes
                title = cur.optString("title", "Diário de Ferro");
                body = cur.optString("body", "");
                sound = cur.optString("sound", "beep3");
                silent = cur.optBoolean("silent", false);
                if (cur.has("notif")) FerroNotifPlugin.applyPatch(app, cur.getJSONObject("notif"));
                if (steps.length() > 1) {
                    JSONArray restA = new JSONArray();
                    for (int k = 1; k < steps.length(); k++) restA.put(steps.get(k));
                    long at = restA.getJSONObject(0).optLong("at", 0L);
                    AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
                    if (am != null && at > 0) FerroNotifPlugin.schedule(am, Math.max(at, System.currentTimeMillis() + 300), FerroNotifPlugin.stepsIntent(app, restA.toString()));
                }
            } catch (Exception ignored) {}
        }
        // acorda a página para avançar a fase (se ela estiver viva, ela mesma atualiza tudo)
        FerroNotifPlugin p = FerroNotifPlugin.instance;
        if (p != null && p.getBridge() != null) {
            try { p.fire("tick"); } catch (Exception ignored) {}
        }
        if (FerroNotifPlugin.fg || silent) return;   // app na frente: o som sai pela página
        final PendingResult pr = goAsync();
        FerroNotifPlugin.fireAlert(app, title, body, sound, pr::finish);
    }
}
