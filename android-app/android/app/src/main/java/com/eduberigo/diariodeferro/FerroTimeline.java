package com.eduberigo.diariodeferro;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Linha do tempo dos avisos de uma fase do treino: fim do descanso, contagem 3-2-1,
 * início da série e os lembretes. A página manda a lista uma vez, no começo da fase,
 * e daqui em diante o Android executa cada passo na hora, com o app na frente ou não.
 *
 * Como os passos são executados:
 * - por temporizadores dentro do próprio processo, com a CPU mantida acordada (wakelock)
 *   até o último passo: é preciso ao milissegundo e não tem intervalo mínimo;
 * - o AlarmManager fica só de reserva, para acordar o aparelho/processo no próximo passo
 *   importante. Ele não serve para passos próximos: o Android empurra para 5 s qualquer
 *   alarme marcado para menos de 5 s à frente (era isso que atrasava o "3, 2, 1").
 *
 * Tudo aqui roda na thread principal.
 */
final class FerroTimeline {

    private FerroTimeline() {}

    private static final Handler H = FerroSound.MAIN;
    private static final Object TOKEN = new Object();
    private static final int REQ = 200;

    private static JSONArray steps;
    private static boolean[] done;
    private static String key = "";
    private static int gen = 0;
    private static int backupFor = -2;
    private static PowerManager.WakeLock wl;
    private static final java.util.ArrayList<String> RECENT = new java.util.ArrayList<>();   // avisos já dados: nunca repete o mesmo

    /** A página mandou a sequência da fase atual. Se for a mesma que já está em curso, não mexe. */
    static void set(Context c, String k, String json) {
        final Context ctx = c.getApplicationContext();
        if (k != null && !k.isEmpty() && k.equals(key) && steps != null) return;
        // a troca de sequência não pode engolir um aviso que vence neste instante (ex.: a página
        // percebeu o fim do descanso alguns milissegundos antes do temporizador daqui)
        if (steps != null) runDue(ctx, 0L);
        adopt(ctx, k, json, true);
    }

    /** O alarme de reserva disparou. Devolve por quanto tempo (ms) manter o processo acordado. */
    static long onAlarm(Context c, String k, String json) {
        final Context ctx = c.getApplicationContext();
        if (steps != null) {
            if (k == null || !k.equals(key)) return 500L;   // alarme de uma sequência que já foi trocada
            runDue(ctx, 60L);                                // os temporizadores não rodaram (CPU dormiu): põe em dia
        } else if (gen == 0) {
            adopt(ctx, k, json, false);                      // o processo foi reiniciado: assume a sequência que veio no alarme
        } else {
            return 500L;                                     // sequência já cancelada pela página
        }
        if (steps == null || done == null) return 500L;
        // "soon": último passo dos próximos 7 s (o alarme segura o processo até lá); "all": último de todos (CPU acordada até lá)
        long now = System.currentTimeMillis(), soon = now, all = now;
        for (int i = 0; i < done.length; i++) {
            if (done[i]) continue;
            JSONObject s = steps.optJSONObject(i);
            long at = s == null ? 0L : s.optLong("at", 0L);
            if (at > all) all = at;
            if (at <= now + 7000L && at > soon) soon = at;
        }
        wake(ctx, all - now + 5000L);
        backup(ctx);
        return Math.max(1500L, Math.min(8500L, soon - now + 2000L));
    }

    /** Cancela tudo (fase sem tempo, pausa, fim do treino). */
    static void clear(Context c) {
        final Context ctx = c.getApplicationContext();
        H.removeCallbacksAndMessages(TOKEN);
        gen++;
        steps = null;
        done = null;
        key = "";
        backupFor = -2;
        cancelBackup(ctx);
        releaseWake();
    }

    static String currentKey() {
        return steps == null ? "" : key;
    }

    static int pending() {
        if (steps == null || done == null) return 0;
        int n = 0;
        for (boolean d : done) if (!d) n++;
        return n;
    }

    // ---------------------------------------------------------------------------------------------

    private static void adopt(Context ctx, String k, String json, boolean fromPage) {
        H.removeCallbacksAndMessages(TOKEN);
        JSONArray arr;
        try {
            arr = new JSONArray(json);
        } catch (Exception e) {
            clear(ctx);
            return;
        }
        steps = arr;
        done = new boolean[arr.length()];
        key = k == null ? "" : k;
        backupFor = -2;
        final int g = ++gen;
        long now = System.currentTimeMillis(), last = now;
        int pending = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s == null) {
                done[i] = true;
                continue;
            }
            long at = s.optLong("at", 0L);
            // passo que já passou: a página está viva e é quem manda no estado, então some;
            // vindo do alarme (processo reiniciado) ainda roda, mas sem som se estiver atrasado demais
            if (fromPage && now - at > s.optLong("late", 400L)) {
                done[i] = true;
                continue;
            }
            final int idx = i;
            pending++;
            if (at > last) last = at;
            H.postAtTime(() -> run(ctx, g, idx), TOKEN, SystemClock.uptimeMillis() + Math.max(0L, at - now));
        }
        if (pending == 0) {
            cancelBackup(ctx);
            releaseWake();
            return;
        }
        wake(ctx, last - now + 5000L);
        backup(ctx);
    }

    private static void runDue(Context ctx, long slack) {
        if (steps == null || done == null) return;
        final int g = gen;
        long now = System.currentTimeMillis();
        for (int i = 0; i < done.length; i++) {
            if (g != gen || done == null) return;
            if (done[i]) continue;
            JSONObject s = steps.optJSONObject(i);
            if (s != null && s.optLong("at", 0L) <= now + slack) run(ctx, g, i);
        }
    }

    private static void run(Context ctx, int g, int idx) {
        if (g != gen || steps == null || done == null || idx >= done.length || done[idx]) return;
        done[idx] = true;
        JSONObject cur = steps.optJSONObject(idx);
        if (cur != null) exec(ctx, cur);
        if (g != gen || done == null) return;   // a página trocou a sequência enquanto o passo rodava
        if (pending() == 0) cancelBackup(ctx);   // a CPU segue acordada mais 5 s (prazo do wakelock), até o último som terminar
        else backup(ctx);
    }

    private static void exec(Context ctx, JSONObject cur) {
        try {
            if (cur.optBoolean("rem", false) && FerroNotifPlugin.lastTouch > cur.optLong("from", 0L)) {
                skipReminders();   // o usuário já respondeu: os lembretes param
                return;
            }
            boolean stale = System.currentTimeMillis() - cur.optLong("at", 0L) > cur.optLong("late", 400L);
            JSONObject patch = cur.optJSONObject("notif");
            if (patch != null) FerroNotifPlugin.applyPatch(ctx, patch);
            String say = cur.optString("say", ""), snd = cur.optString("sound", ""), title = cur.optString("title", "");
            String sig = cur.optLong("at", 0L) + "|" + snd + "|" + say + "|" + title;
            if (!stale && !RECENT.contains(sig)) {
                RECENT.add(sig);
                if (RECENT.size() > 24) RECENT.remove(0);
                if (!say.isEmpty()) FerroSound.say(ctx, say, snd);
                else if (!snd.isEmpty()) FerroSound.play(ctx, snd, cur.optLong("hold", 0L));
                if (!title.isEmpty()) {
                    // em segundo plano: aviso em destaque (vibra pelo canal); na frente: só a vibração, quando pedida
                    if (!FerroNotifPlugin.fg) FerroNotifPlugin.showAlert(ctx, title, cur.optString("body", ""), cur.optBoolean("vib", true));
                    else if (cur.optBoolean("buzz", false)) FerroSound.vibrate(ctx);
                }
            }
            if (!cur.optBoolean("quiet", false)) FerroNotifPlugin.tick();
        } catch (Throwable ignored) {}
    }

    private static void skipReminders() {
        if (steps == null || done == null) return;
        for (int i = 0; i < done.length; i++) {
            JSONObject s = steps.optJSONObject(i);
            if (s != null && s.optBoolean("rem", false)) done[i] = true;
        }
    }

    // ---------- alarme de reserva: acorda o aparelho no próximo passo importante ----------
    private static void backup(Context ctx) {
        if (steps == null || done == null) return;
        int first = -1;
        for (int i = 0; i < done.length; i++) {
            if (done[i]) continue;
            JSONObject s = steps.optJSONObject(i);
            if (s != null && !s.optBoolean("quiet", false)) {
                first = i;
                break;
            }
        }
        if (first == backupFor) return;
        backupFor = first;
        if (first < 0) {
            cancelBackup(ctx);
            return;
        }
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            JSONArray rest = new JSONArray();
            for (int i = 0; i < done.length; i++) if (!done[i]) rest.put(steps.opt(i));
            long at = steps.optJSONObject(first).optLong("at", 0L);
            FerroNotifPlugin.schedule(am, Math.max(at, System.currentTimeMillis() + 100L), intent(ctx, key, rest.toString()));
        } catch (Throwable ignored) {}
    }

    private static PendingIntent intent(Context ctx, String k, String json) {
        Intent i = new Intent(ctx, FerroAlarmReceiver.class);
        i.setAction(FerroNotifPlugin.ACTION + ".ALARM");
        if (json != null) {
            i.putExtra("steps", json);
            i.putExtra("key", k);
        }
        return PendingIntent.getBroadcast(ctx, REQ, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void cancelBackup(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(intent(ctx, null, null));
        } catch (Throwable ignored) {}
    }

    // ---------- CPU acordada enquanto há passo pendente ----------
    private static void wake(Context ctx, long ms) {
        try {
            if (wl == null) {
                PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                if (pm == null) return;
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "diariodeferro:treino");
                wl.setReferenceCounted(false);
            }
            wl.acquire(Math.max(3000L, Math.min(ms, 20L * 60000L)));
        } catch (Throwable ignored) {}
    }

    private static void releaseWake() {
        try {
            if (wl != null && wl.isHeld()) wl.release();
        } catch (Throwable ignored) {}
    }
}
