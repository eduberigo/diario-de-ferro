package com.eduberigo.diariodeferro;

import android.app.Activity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.Scope;
import java.util.Collections;

/**
 * Pede ao Google permissão para o app guardar os próprios arquivos no Drive do usuário
 * (escopo drive.file: o app só enxerga o que ele mesmo criou) e devolve um token de acesso.
 * O envio e a leitura dos arquivos são feitos pelo JavaScript, direto na API do Drive.
 */
@CapacitorPlugin(name = "FerroDrive")
public class FerroDrivePlugin extends Plugin {

    static final String SCOPE = "https://www.googleapis.com/auth/drive.file";
    private ActivityResultLauncher<IntentSenderRequest> launcher;
    private PluginCall pending;

    @Override
    public void load() {
        launcher = getBridge().registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), res -> {
            PluginCall c = pending;
            pending = null;
            if (c == null) return;
            if (res.getResultCode() != Activity.RESULT_OK) {
                c.reject("Autorização cancelada", "CANCELED");
                return;
            }
            try {
                AuthorizationResult ar = Identity.getAuthorizationClient(getActivity()).getAuthorizationResultFromIntent(res.getData());
                resolveToken(c, ar);
            } catch (Exception e) {
                c.reject(String.valueOf(e.getMessage()), "AUTH");
            }
        });
    }

    private void resolveToken(PluginCall c, AuthorizationResult ar) {
        String tok = ar.getAccessToken();
        if (tok == null) {
            c.reject("Sem token", "NO_TOKEN");
            return;
        }
        JSObject r = new JSObject();
        r.put("accessToken", tok);
        c.resolve(r);
    }

    @PluginMethod
    public void authorize(PluginCall call) {
        final boolean interactive = call.getBoolean("interactive", true);
        AuthorizationRequest req = AuthorizationRequest.builder()
            .setRequestedScopes(Collections.singletonList(new Scope(SCOPE)))
            .build();
        Identity.getAuthorizationClient(getActivity())
            .authorize(req)
            .addOnSuccessListener(ar -> {
                if (ar.hasResolution()) {
                    if (!interactive || ar.getPendingIntent() == null) {
                        call.reject("Precisa autorizar", "NEEDS_CONSENT");
                        return;
                    }
                    pending = call;
                    try {
                        launcher.launch(new IntentSenderRequest.Builder(ar.getPendingIntent().getIntentSender()).build());
                    } catch (Exception e) {
                        pending = null;
                        call.reject(String.valueOf(e.getMessage()), "LAUNCH");
                    }
                } else {
                    resolveToken(call, ar);
                }
            })
            .addOnFailureListener(e -> call.reject(String.valueOf(e.getMessage()), "AUTH"));
    }
}
