package com.dockliquida.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

/**
 * Sobreposição para alinhar a dock sobre a tela inicial real (como o BlurDock).
 * Cria DUAS janelas por cima de tudo: o contorno da dock (arrastável) e uma barra de ajuste.
 * Precisa da permissão "Exibir sobre outros apps" (SYSTEM_ALERT_WINDOW).
 */
@CapacitorPlugin(name = "DockOverlay")
public class DockOverlayPlugin extends Plugin {

    private static String lastResult = null;       // último resultado, lido pelo app ao voltar

    private WindowManager wm;
    private DockView dockView;
    private LinearLayout panel;
    private WindowManager.LayoutParams dockLp;
    private TextView info;
    private final Button[] selBtns = new Button[5];
    private boolean dockAdded = false, panelAdded = false;

    // Medidas em pixels REAIS da tela (o app usa pixels do PNG; scaleX/scaleY convertem)
    private float realW, realH, scaleX = 1f, scaleY = 1f;
    private float cx, top, wpx, hpx, rpx;           // cx = centro horizontal da dock
    private float downX, downY, startCx, startTop;
    private int sel = 0;                             // 0=Y 1=X 2=Largura 3=Altura 4=Curvatura

    @PluginMethod
    public void hasPermission(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(getContext()));   // antes do Android 6 não existe essa permissão
        call.resolve(r);
    }

    @PluginMethod
    public void requestPermission(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getContext().getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }

    @PluginMethod
    public void getResult(PluginCall call) {
        JSObject r = new JSObject();
        if (lastResult != null) { r.put("result", lastResult); lastResult = null; }
        call.resolve(r);
    }

    @PluginMethod
    public void start(final PluginCall call) {
        final double ox = call.getDouble("x", 0.0), oy = call.getDouble("y", 0.0);
        final double ow = call.getDouble("w", 800.0), oh = call.getDouble("h", 200.0);
        final double orad = call.getDouble("r", 60.0);
        final double outW = call.getDouble("outW", 1080.0), outH = call.getDouble("outH", 2400.0);
        getActivity().runOnUiThread(() -> {
            try {
                show(ox, oy, ow, oh, orad, outW, outH);
                Intent h = new Intent(Intent.ACTION_MAIN);   // leva o usuário para a tela inicial
                h.addCategory(Intent.CATEGORY_HOME);
                h.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(h);
                call.resolve();
            } catch (Exception ex) {
                call.reject(String.valueOf(ex.getMessage()));
            }
        });
    }

    /** Quanto do WebView fica por baixo das barras do sistema (em px CSS): o CSS usa isso como área segura. */
    @PluginMethod
    public void getInsets(final PluginCall call) {
        getActivity().runOnUiThread(() -> {
            JSObject r = new JSObject();
            try {
                View root = getActivity().getWindow().getDecorView();
                View wv = getBridge().getWebView();
                WindowInsetsCompat wi = ViewCompat.getRootWindowInsets(root);
                float d = getContext().getResources().getDisplayMetrics().density;
                if (wi != null) {
                    Insets sb = wi.getInsets(WindowInsetsCompat.Type.systemBars());
                    int[] loc = new int[2];
                    wv.getLocationInWindow(loc);
                    int sobreTopo = Math.max(0, sb.top - loc[1]);                                        // parte do WebView sob a barra de status
                    int sobreBase = Math.max(0, (loc[1] + wv.getHeight()) - (root.getHeight() - sb.bottom)); // parte sob a barra de navegação
                    r.put("top", sobreTopo / d);
                    r.put("bottom", sobreBase / d);
                }
            } catch (Exception ignored) { }
            call.resolve(r);
        });
    }

    // ---------- Troca automática de wallpaper: imagens prontas em files/rotation + agendamento pelo WorkManager ----------
    private File pastaRotacao() {
        File d = new File(getContext().getFilesDir(), "rotation");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Guarda uma imagem pronta (JPEG em base64) na fila da troca automática. */
    @PluginMethod
    public void saveRotationImage(final PluginCall call) {
        final String data = call.getString("data");
        final int index = call.getInt("index", 0);
        if (data == null) { call.reject("sem imagem"); return; }
        new Thread(() -> {
            try {
                byte[] bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT);
                try (FileOutputStream out = new FileOutputStream(new File(pastaRotacao(), String.format(Locale.US, "%02d.jpg", index)))) {
                    out.write(bytes);
                }
                call.resolve();
            } catch (Exception ex) {
                call.reject(String.valueOf(ex.getMessage()));
            }
        }).start();
    }

    /** Apaga as imagens da fila e volta ao começo. */
    @PluginMethod
    public void clearRotation(PluginCall call) {
        File[] arquivos = pastaRotacao().listFiles();
        if (arquivos != null) for (File f : arquivos) f.delete();
        getContext().getSharedPreferences(WallpaperRotationWorker.PREFS, Context.MODE_PRIVATE).edit().putInt("next", 0).apply();
        call.resolve();
    }

    /** Liga (ou atualiza) a troca a cada "hours" horas, aplicando em "target": home, lock ou both. */
    @PluginMethod
    public void scheduleRotation(PluginCall call) {
        int hours = call.getInt("hours", 24);
        String target = call.getString("target", "home");
        Context ctx = getContext().getApplicationContext();
        ctx.getSharedPreferences(WallpaperRotationWorker.PREFS, Context.MODE_PRIVATE).edit().putString("target", target).apply();
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(WallpaperRotationWorker.class, hours, TimeUnit.HOURS).build();
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("dock_rotation", ExistingPeriodicWorkPolicy.UPDATE, req);
        call.resolve();
    }

    @PluginMethod
    public void cancelRotation(PluginCall call) {
        WorkManager.getInstance(getContext().getApplicationContext()).cancelUniqueWork("dock_rotation");
        call.resolve();
    }

    /** Aplica agora a próxima imagem da fila. */
    @PluginMethod
    public void rotateNow(final PluginCall call) {
        final String target = call.getString("target", "home");
        final Context ctx = getContext().getApplicationContext();
        new Thread(() -> {
            ctx.getSharedPreferences(WallpaperRotationWorker.PREFS, Context.MODE_PRIVATE).edit().putString("target", target).apply();
            JSObject r = new JSObject();
            r.put("applied", WallpaperRotationWorker.aplicarProxima(ctx));
            call.resolve(r);
        }).start();
    }

    /** Paleta dinâmica do sistema (Material You, Android 12+): devolve a1, a2, n1, n2 com os tons em hexadecimal. */
    @PluginMethod
    public void getSystemColors(PluginCall call) {
        JSObject out = new JSObject();
        if (Build.VERSION.SDK_INT >= 31) {
            String[] nomes = {"accent1", "accent2", "neutral1", "neutral2"};
            String[] chaves = {"a1", "a2", "n1", "n2"};
            int[] tons = {0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000};
            for (int p = 0; p < nomes.length; p++) {
                JSObject o = new JSObject();
                for (int t : tons) {
                    try {
                        int id = android.R.color.class.getField("system_" + nomes[p] + "_" + t).getInt(null);
                        int c = getContext().getResources().getColor(id, null);
                        o.put(String.valueOf(t), String.format("#%06X", 0xFFFFFF & c));
                    } catch (Exception ignored) { }
                }
                try { out.put(chaves[p], o); } catch (Exception ignored) { }
            }
        }
        call.resolve(out);
    }

    /** Aplica o PNG (base64) como papel de parede: target = "home", "lock" ou "both". */
    @PluginMethod
    public void setWallpaper(final PluginCall call) {
        final String data = call.getString("data");
        final String target = call.getString("target", "home");
        if (data == null) { call.reject("sem imagem"); return; }
        new Thread(() -> {                                   // fora da thread principal: decodificar é pesado
            try {
                byte[] bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT);
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bmp == null) { call.reject("imagem inválida"); return; }
                android.app.WallpaperManager wallMgr = android.app.WallpaperManager.getInstance(getContext());
                if (Build.VERSION.SDK_INT >= 24) {
                    int sys = android.app.WallpaperManager.FLAG_SYSTEM, lock = android.app.WallpaperManager.FLAG_LOCK;
                    int which = "lock".equals(target) ? lock : ("both".equals(target) ? (sys | lock) : sys);
                    wallMgr.setBitmap(bmp, null, true, which);
                } else {
                    wallMgr.setBitmap(bmp);
                }
                call.resolve();
            } catch (Exception ex) {
                call.reject(String.valueOf(ex.getMessage()));
            }
        }).start();
    }

    private void show(double ox, double oy, double ow, double oh, double orad, double outW, double outH) {
        Context ctx = getContext().getApplicationContext();
        removeViews();
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        realW = dm.widthPixels; realH = dm.heightPixels;
        scaleX = realW / (float) outW; scaleY = realH / (float) outH;   // pixels do PNG -> pixels da tela
        cx = realW / 2f + (float) ox * scaleX;
        top = (float) oy * scaleY;
        wpx = (float) ow * scaleX; hpx = (float) oh * scaleY; rpx = (float) orad * scaleX;

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

        // 1) Contorno da dock: só a área dele recebe toque; o resto da tela continua normal
        dockView = new DockView(ctx);
        dockLp = new WindowManager.LayoutParams(Math.round(wpx), Math.round(hpx), type, flags, PixelFormat.TRANSLUCENT);
        dockLp.gravity = Gravity.TOP | Gravity.START;
        dockView.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getRawX(); downY = ev.getRawY(); startCx = cx; startTop = top;
                    return true;
                case MotionEvent.ACTION_MOVE:                  // arrastar move a dock
                    cx = startCx + (ev.getRawX() - downX);
                    top = startTop + (ev.getRawY() - downY);
                    applyDock();
                    return true;
                default:
                    return false;
            }
        });
        applyDock();
        wm.addView(dockView, dockLp);
        dockAdded = true;

        // 2) Barra de ajuste (no topo): escolhe o item e ajusta com -10 / -1 / +1 / +10
        panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 10);
        panel.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEE181B21);
        bg.setCornerRadius(dp(ctx, 16));
        panel.setBackground(bg);

        info = new TextView(ctx);
        info.setTextColor(Color.WHITE);
        info.setTextSize(13);
        panel.addView(info);

        LinearLayout row1 = newRow(ctx);
        String[] nomes = {"Y", "X", "Larg", "Alt", "Curv"};
        for (int i = 0; i < nomes.length; i++) {
            final int idx = i;
            Button b = newButton(ctx, nomes[i]);
            b.setOnClickListener(v -> { sel = idx; updateInfo(); });
            selBtns[i] = b;
            row1.addView(b, weight());
        }
        panel.addView(row1);

        LinearLayout row2 = newRow(ctx);
        final int[] passos = {-10, -1, 1, 10};
        String[] rotulos = {"-10", "-1", "+1", "+10"};
        for (int i = 0; i < passos.length; i++) {
            final int d = passos[i];
            Button b = newButton(ctx, rotulos[i]);
            b.setOnClickListener(v -> adjust(d));
            row2.addView(b, weight());
        }
        panel.addView(row2);

        LinearLayout row3 = newRow(ctx);
        Button cancel = newButton(ctx, "Cancelar");
        cancel.setOnClickListener(v -> finish(false));
        Button ok = newButton(ctx, "Concluir");
        ok.setOnClickListener(v -> finish(true));
        row3.addView(cancel, weight());
        row3.addView(ok, weight());
        panel.addView(row3);

        WindowManager.LayoutParams panelLp = new WindowManager.LayoutParams(
                Math.round(realW - dp(ctx, 24)), WindowManager.LayoutParams.WRAP_CONTENT,
                type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        panelLp.y = dp(ctx, 48);                               // abaixo da barra de status
        wm.addView(panel, panelLp);
        panelAdded = true;
        updateInfo();
    }

    /** Aplica as medidas atuais à janela do contorno. */
    private void applyDock() {
        dockLp.width = Math.max(20, Math.round(wpx));
        dockLp.height = Math.max(20, Math.round(hpx));
        dockLp.x = Math.round(cx - wpx / 2f);
        dockLp.y = Math.round(top);
        dockView.radius = rpx;
        dockView.invalidate();
        if (dockAdded) wm.updateViewLayout(dockView, dockLp);
        updateInfo();
    }

    /** Soma d pixels ao item selecionado. */
    private void adjust(int d) {
        float f = d;
        switch (sel) {
            case 0: top += f; break;
            case 1: cx += f; break;
            case 2: wpx = Math.max(20f, wpx + f); break;
            case 3: hpx = Math.max(20f, hpx + f); break;
            default: rpx = Math.max(0f, rpx + f); break;
        }
        rpx = Math.min(rpx, Math.min(wpx, hpx) / 2f);
        applyDock();
    }

    private void updateInfo() {
        if (info == null) return;
        String[] nomes = {"Y", "X", "Larg", "Alt", "Curv"};
        info.setText(String.format(Locale.US, "Ajustando: %s   Y %d  X %d  L %d  A %d  R %d",
                nomes[sel], outY(), outX(), outW(), outH(), outR()));
        for (int i = 0; i < selBtns.length; i++) {
            if (selBtns[i] != null) selBtns[i].setAlpha(i == sel ? 1f : 0.5f);
        }
    }

    // Conversões de volta para os pixels do PNG (o que o app usa)
    private int outX() { return Math.round((cx - realW / 2f) / scaleX); }
    private int outY() { return Math.round(top / scaleY); }
    private int outW() { return Math.round(wpx / scaleX); }
    private int outH() { return Math.round(hpx / scaleY); }
    private int outR() { return Math.round(rpx / scaleX); }

    /** Fecha a sobreposição; se ok, guarda o resultado e volta para o app. */
    private void finish(boolean ok) {
        if (ok) {
            JSObject r = new JSObject();
            r.put("x", outX()); r.put("y", outY()); r.put("w", outW()); r.put("h", outH()); r.put("r", outR());
            lastResult = r.toString();
            notifyListeners("done", r);
        }
        removeViews();
        Context ctx = getContext();
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            ctx.startActivity(i);
        }
    }

    private void removeViews() {
        try { if (dockAdded) wm.removeView(dockView); } catch (Exception ignored) { }
        try { if (panelAdded) wm.removeView(panel); } catch (Exception ignored) { }
        dockAdded = false; panelAdded = false;
    }

    // ---- ajudantes de interface (feita em código, sem arquivos XML) ----
    private int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    private LinearLayout newRow(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private Button newButton(Context c, String t) {
        Button b = new Button(c);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        return b;
    }

    /** Contorno da dock: preenchimento bem leve + borda azul, para ver os ícones por baixo. */
    static class DockView extends View {
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        float radius = 40f;

        DockView(Context c) {
            super(c);
            fill.setColor(0x227CC4FF);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(4f);
            stroke.setColor(0xFF7CC4FF);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            RectF r = new RectF(2f, 2f, getWidth() - 2f, getHeight() - 2f);
            canvas.drawRoundRect(r, radius, radius, fill);
            canvas.drawRoundRect(r, radius, radius, stroke);
        }
    }
}
