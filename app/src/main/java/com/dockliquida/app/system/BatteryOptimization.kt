package com.dockliquida.app.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/*
 * BatteryOptimization.kt
 * --------------------------------------------------------------------------
 * O Android economiza bateria adiando tarefas de apps que ficam fechados (modo Soneca/Doze e "buckets" de uso).
 * A troca automática é uma dessas tarefas, então ela pode atrasar horas. Um app pode ser colocado na lista de
 * exceções ("sem otimização de bateria"); só o USUÁRIO pode aceitar isso, num diálogo do próprio Android.
 */
object BatteryOptimization {
    /** true se o app já está livre da economia de bateria. */
    fun isIgnoring(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /** Diálogo do Android: "Permitir que o app seja executado sempre em segundo plano?" (Permitir / Negar). */
    fun requestIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))

    /** Lista de apps da economia de bateria: é por onde se DESLIGA a exceção (o app não consegue desfazê-la sozinho). */
    fun listIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}
