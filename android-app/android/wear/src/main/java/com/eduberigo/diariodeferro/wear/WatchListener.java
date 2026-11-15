package com.eduberigo.diariodeferro.wear;

import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import java.nio.charset.StandardCharsets;

/** Recebe do celular o estado do treino e os avisos de fim de fase (vibra). */
public class WatchListener extends WearableListenerService {
    @Override
    public void onDataChanged(DataEventBuffer events) {
        for (DataEvent e : events) {
            if (e.getType() != DataEvent.TYPE_CHANGED || !"/ferro/state".equals(e.getDataItem().getUri().getPath())) continue;
            DataMap m = DataMapItem.fromDataItem(e.getDataItem()).getDataMap();
            WatchState.update(getApplicationContext(), m.getString("json", ""), m.getLong("ts", 0L));
        }
    }

    @Override
    public void onMessageReceived(MessageEvent e) {
        if (!"/ferro/alert".equals(e.getPath())) return;
        String t = new String(e.getData() == null ? new byte[0] : e.getData(), StandardCharsets.UTF_8);
        if (t.startsWith("1|")) WatchState.vibrate(getApplicationContext(), new long[] {0, 350, 150, 350, 150, 350});
    }
}
