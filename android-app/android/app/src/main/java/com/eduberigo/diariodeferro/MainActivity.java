package com.eduberigo.diariodeferro;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(FerroNotifPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
