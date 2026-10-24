package com.dockliquida.app;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;
import java.util.Arrays;

/**
 * Troca automática de wallpaper. O WorkManager (agendador do sistema) executa isto de tempos em tempos,
 * mesmo com o app fechado e depois de reiniciar o celular. As imagens já vêm prontas do app
 * (files/rotation/00.jpg, 01.jpg…), com a Dock desenhada: aqui só se escolhe a próxima e se aplica.
 */
public class WallpaperRotationWorker extends Worker {

    public static final String PREFS = "dockrotation";

    public WallpaperRotationWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        aplicarProxima(getApplicationContext());
        return Result.success();            // se falhar, simplesmente espera o próximo ciclo
    }

    /** Aplica a próxima imagem da fila (em ordem circular). Devolve false se não houver imagem ou se falhar. */
    public static boolean aplicarProxima(Context ctx) {
        File[] arquivos = new File(ctx.getFilesDir(), "rotation").listFiles((d, nome) -> nome.endsWith(".jpg"));
        if (arquivos == null || arquivos.length == 0) return false;
        Arrays.sort(arquivos);                                          // 00.jpg, 01.jpg, 02.jpg…
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int i = sp.getInt("next", 0) % arquivos.length;
        Bitmap bmp = BitmapFactory.decodeFile(arquivos[i].getAbsolutePath());
        if (bmp == null) return false;
        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            String alvo = sp.getString("target", "home");
            if (Build.VERSION.SDK_INT >= 24) {
                int sys = WallpaperManager.FLAG_SYSTEM, lock = WallpaperManager.FLAG_LOCK;
                int quais = "lock".equals(alvo) ? lock : ("both".equals(alvo) ? (sys | lock) : sys);
                wm.setBitmap(bmp, null, true, quais);
            } else {
                wm.setBitmap(bmp);
            }
            sp.edit().putInt("next", (i + 1) % arquivos.length).putLong("last", System.currentTimeMillis()).apply();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            bmp.recycle();
        }
    }
}
