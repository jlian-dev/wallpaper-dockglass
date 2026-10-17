package com.dockliquida.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(DockOverlayPlugin.class);   // registra o plugin ANTES do super.onCreate
        super.onCreate(savedInstanceState);
    }
}
