package com.dockliquida.app;

import android.os.Bundle;
import androidx.activity.OnBackPressedCallback;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(DockOverlayPlugin.class); // registra o plugin ANTES do super.onCreate
        super.onCreate(savedInstanceState);

        // Intercepta tanto o gesto Voltar quanto o botão de navegação do Android.
        // O histórico de telas e a confirmação de saída ficam no JavaScript.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (bridge == null || bridge.getWebView() == null) return;
                bridge.getWebView().evaluateJavascript(
                    "(function(){try{return window.dockOnAndroidBack ? window.dockOnAndroidBack() : 'stay';}catch(e){return 'stay';}})()",
                    result -> {
                        // Só encerra após dois gestos/toques rápidos na tela inicial.
                        if ("\"exit\"".equals(result)) {
                            runOnUiThread(() -> finish());
                        }
                    }
                );
            }
        });
    }
}
