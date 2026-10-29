package com.eduberigo.diariodeferro;

import android.os.Build;
import android.os.Bundle;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(FerroNotifPlugin.class);
        registerPlugin(FerroDrivePlugin.class);
        super.onCreate(savedInstanceState);
        // a página do app continua com prioridade quando a tela do app não está visível (treino em segundo plano)
        try {
            if (Build.VERSION.SDK_INT >= 26 && getBridge() != null && getBridge().getWebView() != null)
                getBridge().getWebView().setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        } catch (Exception ignored) {}
    }
}
