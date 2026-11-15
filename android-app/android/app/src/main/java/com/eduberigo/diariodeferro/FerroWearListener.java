package com.eduberigo.diariodeferro;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import java.nio.charset.StandardCharsets;

/** Recebe o que vem do relógio: botões do treino, ajuste da série, batimentos e pedido do estado atual. */
public class FerroWearListener extends WearableListenerService {
    @Override
    public void onMessageReceived(MessageEvent e) {
        String path = e.getPath(), text = new String(e.getData() == null ? new byte[0] : e.getData(), StandardCharsets.UTF_8);
        FerroWear.lastSeen = System.currentTimeMillis();
        if ("/ferro/act".equals(path)) {
            FerroNotifPlugin.lastTouch = System.currentTimeMillis();   // como um toque na notificação: para os lembretes
            FerroNotifPlugin p = FerroNotifPlugin.instance;
            if (p != null && p.getBridge() != null) FerroNotifPlugin.onMain(() -> p.fire(text));
        } else if ("/ferro/hr".equals(path)) {
            try {
                final int bpm = Integer.parseInt(text.trim());
                FerroNotifPlugin.onMain(() -> FerroWear.onHr(getApplicationContext(), bpm));
            } catch (NumberFormatException ignored) {}
        } else if ("/ferro/hello".equals(path)) {
            FerroWear.push(getApplicationContext(), FerroNotifPlugin.last);
        }
    }
}
