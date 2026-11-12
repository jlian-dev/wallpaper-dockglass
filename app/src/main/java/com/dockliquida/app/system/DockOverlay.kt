package com.dockliquida.app.system

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * DockOverlay.kt
 * --------------------------------------------------------------------------
 * "Alinhar sobre a tela inicial": desenha o CONTORNO da dock (arrastável) e uma barra de ajuste POR CIMA
 * da tela inicial real, para você casar a dock com os ícones de verdade (ideia do BlurDock).
 * Precisa da permissão "Exibir sobre outros apps" (SYSTEM_ALERT_WINDOW).
 *
 * É uma tradução do DockOverlayPlugin.java. A diferença: em vez de devolver o resultado por um plugin
 * JavaScript, publicamos num StateFlow que a tela do editor observa.
 */

/** Medidas finais, em pixels da IMAGEM DE SAÍDA (não da tela). */
data class OverlayResult(val x: Int, val y: Int, val w: Int, val h: Int, val r: Int)

object DockOverlay {

    private val _result = MutableStateFlow<OverlayResult?>(null)
    /** Quando o usuário toca em "Concluir", o valor aparece aqui. O editor consome e chama consumeResult(). */
    val result: StateFlow<OverlayResult?> = _result.asStateFlow()
    fun consumeResult() { _result.value = null }

    fun hasPermission(ctx: Context) = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    /** Abre a tela do sistema onde o usuário liga "Exibir sobre outros apps" para este app. */
    fun requestPermission(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---- estado interno da sobreposição ativa (só existe uma por vez) ----
    private var wm: WindowManager? = null
    private var dockView: DockView? = null
    private var panel: LinearLayout? = null
    private var dockLp: WindowManager.LayoutParams? = null
    private var info: TextView? = null
    private val selBtns = arrayOfNulls<Button>(5)
    private var dockAdded = false
    private var panelAdded = false

    // Medidas em pixels REAIS da tela (o app usa pixels da imagem; scaleX/scaleY convertem)
    private var realW = 0f; private var realH = 0f; private var scaleX = 1f; private var scaleY = 1f
    private var cx = 0f; private var top = 0f; private var wpx = 0f; private var hpx = 0f; private var rpx = 0f
    private var downX = 0f; private var downY = 0f; private var startCx = 0f; private var startTop = 0f
    private var sel = 0                                              // 0=Y 1=X 2=Largura 3=Altura 4=Curvatura
    private val nomes = arrayOf("Y", "X", "Larg", "Alt", "Curv")

    /** Mostra a sobreposição e leva o usuário para a tela inicial. Chamar na thread principal. */
    fun start(ctx: Context, x: Int, y: Int, w: Int, h: Int, r: Int, outW: Int, outH: Int) {
        val app = ctx.applicationContext
        show(app, x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(), r.toDouble(), outW.toDouble(), outH.toDouble())
        app.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Suppress("DEPRECATION")
    private fun show(ctx: Context, ox: Double, oy: Double, ow: Double, oh: Double, orad: Double, outW: Double, outH: Double) {
        removeViews()
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = manager
        val (sw, sh) = Actions.deviceSize(ctx)
        realW = sw.toFloat(); realH = sh.toFloat()
        scaleX = realW / outW.toFloat(); scaleY = realH / outH.toFloat()           // pixels da imagem -> pixels da tela
        cx = realW / 2f + ox.toFloat() * scaleX
        top = oy.toFloat() * scaleY
        wpx = ow.toFloat() * scaleX; hpx = oh.toFloat() * scaleY; rpx = orad.toFloat() * scaleX

        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        // 1) Contorno da dock: só a área dele recebe toque; o resto da tela continua normal
        val dv = DockView(ctx)
        dockView = dv
        val lp = WindowManager.LayoutParams(wpx.roundToInt(), hpx.roundToInt(), type, flags, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        dockLp = lp
        dv.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; startCx = cx; startTop = top; true }
                MotionEvent.ACTION_MOVE -> {                                         // arrastar move a dock
                    cx = startCx + (ev.rawX - downX); top = startTop + (ev.rawY - downY); applyDock(); true
                }
                else -> false
            }
        }
        applyDock()
        manager.addView(dv, lp); dockAdded = true

        // 2) Barra de ajuste (no topo): escolhe o item e ajusta com -10 / -1 / +1 / +10
        val p = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(ctx, 10f); setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply { setColor(0xEE181B21.toInt()); cornerRadius = dp(ctx, 16f).toFloat() }
        }
        panel = p
        info = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 13f }
        p.addView(info)

        val row1 = row(ctx)
        for (i in nomes.indices) row1.addView(button(ctx, nomes[i]).also { b -> b.setOnClickListener { sel = i; updateInfo() }; selBtns[i] = b }, weight())
        p.addView(row1)

        val row2 = row(ctx)
        for ((d, label) in listOf(-10 to "-10", -1 to "-1", 1 to "+1", 10 to "+10"))
            row2.addView(button(ctx, label).also { b -> b.setOnClickListener { adjust(d) } }, weight())
        p.addView(row2)

        val row3 = row(ctx)
        row3.addView(button(ctx, "Cancelar").also { it.setOnClickListener { finish(ctx, false) } }, weight())
        row3.addView(button(ctx, "Concluir").also { it.setOnClickListener { finish(ctx, true) } }, weight())
        p.addView(row3)

        val plp = WindowManager.LayoutParams(
            (realW - dp(ctx, 24f)).roundToInt(), WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT
        )
        plp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        plp.y = dp(ctx, 48f)                                                          // abaixo da barra de status
        manager.addView(p, plp); panelAdded = true
        updateInfo()
    }

    /** Aplica as medidas atuais à janela do contorno. */
    private fun applyDock() {
        val lp = dockLp ?: return
        lp.width = max(20, wpx.roundToInt()); lp.height = max(20, hpx.roundToInt())
        lp.x = (cx - wpx / 2f).roundToInt(); lp.y = top.roundToInt()
        dockView?.apply { radius = rpx; invalidate() }
        if (dockAdded) wm?.updateViewLayout(dockView, lp)
        updateInfo()
    }

    /** Soma d pixels ao item selecionado. */
    private fun adjust(d: Int) {
        val f = d.toFloat()
        when (sel) {
            0 -> top += f
            1 -> cx += f
            2 -> wpx = max(20f, wpx + f)
            3 -> hpx = max(20f, hpx + f)
            else -> rpx = max(0f, rpx + f)
        }
        rpx = min(rpx, min(wpx, hpx) / 2f)
        applyDock()
    }

    // Conversões de volta para os pixels da imagem (o que o editor usa)
    private fun outX() = ((cx - realW / 2f) / scaleX).roundToInt()
    private fun outY() = (top / scaleY).roundToInt()
    private fun outW() = (wpx / scaleX).roundToInt()
    private fun outH() = (hpx / scaleY).roundToInt()
    private fun outR() = (rpx / scaleX).roundToInt()

    private fun updateInfo() {
        info?.text = String.format(Locale.US, "Ajustando: %s   Y %d  X %d  L %d  A %d  R %d", nomes[sel], outY(), outX(), outW(), outH(), outR())
        for (i in selBtns.indices) selBtns[i]?.alpha = if (i == sel) 1f else 0.5f
    }

    /** Fecha a sobreposição; se ok, publica o resultado e volta para o app. */
    private fun finish(ctx: Context, ok: Boolean) {
        if (ok) _result.value = OverlayResult(outX(), outY(), outW(), outH(), outR())
        removeViews()
        ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            ctx.startActivity(it)
        }
    }

    private fun removeViews() {
        try { if (dockAdded) wm?.removeView(dockView) } catch (_: Exception) {}
        try { if (panelAdded) wm?.removeView(panel) } catch (_: Exception) {}
        dockAdded = false; panelAdded = false
    }

    // ---- ajudantes de interface (feita em código, sem XML) ----
    private fun dp(c: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics).roundToInt()
    private fun row(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
    private fun weight() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    private fun button(c: Context, t: String) = Button(c).apply { text = t; isAllCaps = false; textSize = 13f; minWidth = 0; minimumWidth = 0 }

    /** Contorno da dock: preenchimento bem leve + borda azul, para ver os ícones por baixo. */
    private class DockView(c: Context) : View(c) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x227CC4FF }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = 0xFF7CC4FF.toInt() }
        var radius = 40f
        override fun onDraw(canvas: Canvas) {
            val r = RectF(2f, 2f, width - 2f, height - 2f)
            canvas.drawRoundRect(r, radius, radius, fill)
            canvas.drawRoundRect(r, radius, radius, stroke)
        }
    }
}
