package com.dockliquida.app.system

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import com.dockliquida.app.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import com.dockliquida.app.ui.toAmoled

/** Medidas finais em pixels da imagem de saída. */
data class OverlayResult(val x: Int, val y: Int, val w: Int, val h: Int, val r: Int)

/** Alinhamento nativo sobre a tela inicial; não exige uma Activity Compose sobre o launcher. */
object DockOverlay {
    private val _result = MutableStateFlow<OverlayResult?>(null)
    val result: StateFlow<OverlayResult?> = _result.asStateFlow()
    fun consumeResult() { _result.value = null }
    fun hasPermission(ctx: Context) = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    fun requestPermission(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private data class Palette(val surface: Int, val container: Int, val primary: Int, val onPrimary: Int, val text: Int, val muted: Int)
    private var colors = Palette(0xFF292929.toInt(), 0xFF383838.toInt(), 0xFFE0E0E0.toInt(), 0xFF202020.toInt(), Color.WHITE, 0xFFCCCCCC.toInt())
    private var wm: WindowManager? = null
    private var dockView: DockView? = null
    private var panel: LinearLayout? = null
    private var dockLp: WindowManager.LayoutParams? = null
    private var info: TextView? = null
    private var valueLabel: TextView? = null
    private var slider: SeekBar? = null
    private val selectors = arrayOfNulls<ImageButton>(5)
    private val stepButtons = mutableListOf<Pair<Button, Int>>()
    private var dockAdded = false
    private var panelAdded = false
    private var realW = 0f; private var realH = 0f
    private var scaleX = 1f; private var scaleY = 1f
    private var imageW = 1; private var imageH = 1
    private var cx = 0f; private var top = 0f
    private var wpx = 0f; private var hpx = 0f; private var rpx = 0f
    private var downX = 0f; private var downY = 0f
    private var startCx = 0f; private var startTop = 0f
    private var sel = 0
    private val names = arrayOf("Posição vertical", "Posição horizontal", "Largura", "Altura", "Curvatura")

    fun start(ctx: Context, x: Int, y: Int, w: Int, h: Int, r: Int, outW: Int, outH: Int) {
        val app = ctx.applicationContext
        show(app, x, y, w, h, r, outW, outH)
        app.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun palette(ctx: Context): Palette {
        val prefs = Prefs.get(ctx)
        val dark = when (prefs.theme.value) {
            "claro" -> false
            "escuro" -> true
            else -> ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        val amoled = dark && prefs.amoled.value                    // modo AMOLED: o painel acompanha o tema do app
        if (Build.VERSION.SDK_INT >= 31 && prefs.dynamicColors.value) {
            val raw = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            val scheme = if (amoled) raw.toAmoled() else raw
            return Palette(scheme.surfaceContainerHigh.toArgb(), scheme.surfaceContainerHighest.toArgb(),
                scheme.primary.toArgb(), scheme.onPrimary.toArgb(), scheme.onSurface.toArgb(), scheme.onSurfaceVariant.toArgb())
        }
        return if (dark) Palette(if (amoled) 0xFF141414.toInt() else 0xFF292929.toInt(), if (amoled) 0xFF1E1E1E.toInt() else 0xFF383838.toInt(), 0xFFE0E0E0.toInt(), 0xFF202020.toInt(), 0xFFF5F5F5.toInt(), 0xFFD0D0D0.toInt())
        else Palette(0xFFF5F5F5.toInt(), 0xFFE5E5E5.toInt(), 0xFF484848.toInt(), Color.WHITE, 0xFF171717.toInt(), 0xFF444444.toInt())
    }

    @Suppress("DEPRECATION")
    private fun show(ctx: Context, x: Int, y: Int, w: Int, h: Int, r: Int, outW: Int, outH: Int) {
        removeViews()
        consumeResult()
        colors = palette(ctx)
        sel = 0
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = manager
        val (sw, sh) = Actions.deviceSize(ctx)
        realW = sw.toFloat(); realH = sh.toFloat()
        imageW = outW.coerceAtLeast(1); imageH = outH.coerceAtLeast(1)
        scaleX = realW / imageW; scaleY = realH / imageH
        cx = realW / 2f + x * scaleX; top = y * scaleY
        wpx = w * scaleX; hpx = h * scaleY; rpx = r * scaleX
        constrainDock()

        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val dv = DockView(ctx, colors.primary)
        dockView = dv
        val lp = WindowManager.LayoutParams(wpx.roundToInt(), hpx.roundToInt(), type, flags, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        dockLp = lp
        dv.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; startCx = cx; startTop = top
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    cx = startCx + ev.rawX - downX; top = startTop + ev.rawY - downY
                    applyDock()
                    true
                }
                MotionEvent.ACTION_UP -> { view.performClick(); true }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        applyDock()

        val p = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12f), dp(ctx, 12f), dp(ctx, 12f), dp(ctx, 12f))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(alpha(colors.surface, 242), alpha(colors.surface, 226))).apply {
                cornerRadius = dp(ctx, 30f).toFloat()
                setStroke(dp(ctx, 1f), alpha(DockOverlay.colors.text, 32))
            }
            elevation = dp(ctx, 12f).toFloat()
        }
        panel = p

        // 1. Cinco ícones; nomes completos ficam disponíveis ao leitor de tela e no toque longo.
        val properties = row(ctx)
        for (i in names.indices) {
            val button = ImageButton(ctx).apply {
                setPadding(dp(ctx, 12f), dp(ctx, 12f), dp(ctx, 12f), dp(ctx, 12f))
                setImageDrawable(PropertyIcon(i))
                contentDescription = names[i]
                if (Build.VERSION.SDK_INT >= 26) tooltipText = names[i]
                setOnClickListener { sel = i; updateSelection(ctx); updateInfo() }
            }
            selectors[i] = button
            properties.addView(button, LinearLayout.LayoutParams(0, dp(ctx, 48f), 1f).apply {
                if (i > 0) marginStart = dp(ctx, 6f)
            })
        }
        p.addView(properties)

        // 2. Ações principais logo abaixo dos seletores.
        val actions = row(ctx)
        actions.addView(button(ctx, "CANCELAR", false).apply { setOnClickListener { finish(ctx, false) } }, weighted(ctx, 48))
        actions.addView(button(ctx, "CONCLUIR", true).apply { setOnClickListener { finish(ctx, true) } }, weighted(ctx, 48, 8))
        p.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(ctx, 10f) })

        // 3. Uma única propriedade e seu valor: sem a linha de cinco medidas acumuladas.
        val labels = row(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        info = TextView(ctx).apply { textSize = 13f; setTextColor(colors.muted); maxLines = 2 }
        valueLabel = TextView(ctx).apply {
            textSize = 14f; setTextColor(colors.text)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        labels.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        labels.addView(valueLabel, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(ctx, 8f) })
        p.addView(labels, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(ctx, 12f); marginStart = dp(ctx, 4f); marginEnd = dp(ctx, 4f)
        })

        // 4. Precisão em pixels da imagem: -10 / -1 / slider / +1 / +10.
        val adjustments = row(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        fun addStep(delta: Int) {
            val label = if (delta > 0) "+$delta" else delta.toString()
            val b = button(ctx, label, false).apply {
                textSize = 12f; letterSpacing = 0f
                contentDescription = if (delta > 0) "Aumentar $delta pixels" else "Diminuir ${-delta} pixels"
                setOnClickListener { setSelectedValue(selectedValue() + delta) }
            }
            stepButtons.add(b to delta)
            adjustments.addView(b, LinearLayout.LayoutParams(dp(ctx, 40f), dp(ctx, 48f)))
        }
        addStep(-10)
        addStep(-1)
        slider = SeekBar(ctx).apply {
            splitTrack = false
            thumbTintList = ColorStateList.valueOf(colors.primary)
            progressTintList = ColorStateList.valueOf(colors.primary)
            progressBackgroundTintList = ColorStateList.valueOf(alpha(colors.muted, 65))
            setPadding(dp(ctx, 12f), 0, dp(ctx, 12f), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) setSelectedValue(selectedRange().first + progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }
        adjustments.addView(slider, LinearLayout.LayoutParams(0, dp(ctx, 48f), 1f))
        addStep(1)
        addStep(10)
        p.addView(adjustments, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(ctx, 4f) })

        val plp = WindowManager.LayoutParams(
            min((realW - dp(ctx, 24f)).roundToInt(), dp(ctx, 420f)), -2, type,
            flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT
        )
        plp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        val statusInset = if (Build.VERSION.SDK_INT >= 30) {
            manager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top
        } else dp(ctx, 24f)
        plp.y = statusInset + dp(ctx, 12f)
        // O desfoque real depende de o aparelho permitir blur entre janelas. O fundo fosco continua sem ele.
        if (Build.VERSION.SDK_INT >= 31 && manager.isCrossWindowBlurEnabled) {
            plp.flags = plp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            plp.setBlurBehindRadius(dp(ctx, 18f))
        }
        try {
            manager.addView(dv, lp); dockAdded = true
            manager.addView(p, plp); panelAdded = true
            updateSelection(ctx)
            updateInfo()
        } catch (e: RuntimeException) {
            removeViews()
            throw e
        }
    }

    private fun selectedRange(): IntRange = when (sel) {
        0 -> 0..max(0, imageH - outH())
        1 -> { val half = max(0f, (imageW - outW()) / 2f); (-half).roundToInt()..half.roundToInt() }
        2 -> min(20, imageW)..imageW
        3 -> min(20, imageH)..imageH
        else -> 0..max(0, min(outW(), outH()) / 2)
    }

    private fun selectedValue() = when (sel) {
        0 -> outY(); 1 -> outX(); 2 -> outW(); 3 -> outH(); else -> outR()
    }

    private fun setSelectedValue(value: Int) {
        val v = value.coerceIn(selectedRange()).toFloat()
        when (sel) {
            0 -> top = v * scaleY
            1 -> cx = realW / 2f + v * scaleX
            2 -> wpx = v * scaleX
            3 -> hpx = v * scaleY
            else -> rpx = v * scaleX
        }
        applyDock()
    }

    private fun constrainDock() {
        wpx = wpx.coerceIn(min(20f * scaleX, realW), realW)
        hpx = hpx.coerceIn(min(20f * scaleY, realH), realH)
        cx = cx.coerceIn(wpx / 2f, realW - wpx / 2f)
        top = top.coerceIn(0f, max(0f, realH - hpx))
        rpx = rpx.coerceIn(0f, min(wpx, hpx) / 2f)
    }

    private fun applyDock() {
        constrainDock()
        val lp = dockLp ?: return
        lp.width = max(1, wpx.roundToInt()); lp.height = max(1, hpx.roundToInt())
        lp.x = (cx - wpx / 2f).roundToInt(); lp.y = top.roundToInt()
        dockView?.apply { radius = rpx; invalidate() }
        if (dockAdded) wm?.updateViewLayout(dockView, lp)
        updateInfo()
    }

    private fun outX() = ((cx - realW / 2f) / scaleX).roundToInt()
    private fun outY() = (top / scaleY).roundToInt()
    private fun outW() = (wpx / scaleX).roundToInt()
    private fun outH() = (hpx / scaleY).roundToInt()
    private fun outR() = (rpx / scaleX).roundToInt()

    private fun updateSelection(ctx: Context) {
        for (i in selectors.indices) selectors[i]?.apply {
            isSelected = i == sel
            val icon = drawable as? PropertyIcon
            icon?.setColor(if (isSelected) colors.onPrimary else colors.muted)
            background = ripple(ctx, if (isSelected) colors.primary else colors.container, 20f)
        }
    }

    private fun updateInfo() {
        val range = selectedRange()
        val value = selectedValue().coerceIn(range)
        info?.text = names[sel]
        valueLabel?.text = "$value px"
        slider?.apply {
            contentDescription = names[sel]
            max = range.last - range.first
            progress = value - range.first
            isEnabled = max > 0
            if (Build.VERSION.SDK_INT >= 30) stateDescription = "$value pixels"
        }
        for ((button, delta) in stepButtons) {
            button.isEnabled = if (delta < 0) value > range.first else value < range.last
            button.alpha = if (button.isEnabled) 1f else 0.38f
        }
    }

    private fun finish(ctx: Context, ok: Boolean) {
        if (ok) _result.value = OverlayResult(outX(), outY(), outW(), outH(), outR())
        removeViews()
        ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
            ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    private fun removeViews() {
        try { if (dockAdded) wm?.removeView(dockView) } catch (_: Exception) {}
        try { if (panelAdded) wm?.removeView(panel) } catch (_: Exception) {}
        dockAdded = false; panelAdded = false
        dockView = null; panel = null; dockLp = null; wm = null
        info = null; valueLabel = null; slider = null
        selectors.fill(null)
        stepButtons.clear()
    }

    private fun dp(ctx: Context, value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, ctx.resources.displayMetrics).roundToInt()
    private fun alpha(color: Int, value: Int) = (color and 0x00FFFFFF) or (value.coerceIn(0, 255) shl 24)
    private fun row(ctx: Context) = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private fun weighted(ctx: Context, height: Int, start: Int = 0) = LinearLayout.LayoutParams(0, dp(ctx, height.toFloat()), 1f).apply { marginStart = dp(ctx, start.toFloat()) }
    private fun shape(ctx: Context, color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = dp(ctx, radius).toFloat() }
    private fun ripple(ctx: Context, color: Int, radius: Float): Drawable = RippleDrawable(
        ColorStateList.valueOf(alpha(colors.primary, 45)), shape(ctx, color, radius), shape(ctx, Color.WHITE, radius)
    )
    private fun button(ctx: Context, label: String, primary: Boolean) = Button(ctx).apply {
        text = label; isAllCaps = false; textSize = 13f; letterSpacing = 0.02f
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(0, 0, 0, 0)
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(if (primary) colors.onPrimary else colors.text)
        backgroundTintList = null
        background = ripple(ctx, if (primary) colors.primary else colors.container, 24f)
        stateListAnimator = null
        maxLines = 1
    }

    /** Ícones vetoriais desenhados em 24 unidades: deslocamento e dimensões têm símbolos distintos. */
    private class PropertyIcon(private val property: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.8f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        override fun draw(canvas: Canvas) {
            val size = min(bounds.width(), bounds.height()).toFloat()
            canvas.save()
            canvas.translate(bounds.left + (bounds.width() - size) / 2f, bounds.top + (bounds.height() - size) / 2f)
            canvas.scale(size / 24f, size / 24f)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) = canvas.drawLine(x1, y1, x2, y2, paint)
            when (property) {
                0 -> {
                    line(12f, 3f, 12f, 21f)
                    line(8f, 7f, 12f, 3f); line(12f, 3f, 16f, 7f)
                    line(8f, 17f, 12f, 21f); line(12f, 21f, 16f, 17f)
                }
                1 -> {
                    line(3f, 12f, 21f, 12f)
                    line(7f, 8f, 3f, 12f); line(3f, 12f, 7f, 16f)
                    line(17f, 8f, 21f, 12f); line(21f, 12f, 17f, 16f)
                }
                2 -> {
                    line(3f, 5f, 3f, 19f); line(21f, 5f, 21f, 19f)
                    line(6f, 12f, 18f, 12f)
                    line(9f, 9f, 6f, 12f); line(6f, 12f, 9f, 15f)
                    line(15f, 9f, 18f, 12f); line(18f, 12f, 15f, 15f)
                }
                3 -> {
                    line(5f, 3f, 19f, 3f); line(5f, 21f, 19f, 21f)
                    line(12f, 6f, 12f, 18f)
                    line(9f, 9f, 12f, 6f); line(12f, 6f, 15f, 9f)
                    line(9f, 15f, 12f, 18f); line(12f, 18f, 15f, 15f)
                }
                else -> {
                    val path = Path().apply { moveTo(4f, 20f); lineTo(4f, 12f); quadTo(4f, 4f, 12f, 4f); lineTo(20f, 4f) }
                    canvas.drawPath(path, paint)
                    line(9f, 15f, 17f, 7f)
                    line(13f, 7f, 17f, 7f); line(17f, 7f, 17f, 11f)
                }
            }
            canvas.restore()
        }
        fun setColor(color: Int) { paint.color = color; invalidateSelf() }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth() = 24
        override fun getIntrinsicHeight() = 24
    }

    private class DockView(ctx: Context, tintColor: Int) : View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = DockOverlay.alpha(tintColor, 28) }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = DockOverlay.dp(ctx, 2f).toFloat(); this.color = tintColor
        }
        var radius = 40f
        override fun onDraw(canvas: Canvas) {
            val inset = stroke.strokeWidth / 2f
            val rect = RectF(inset, inset, width - inset, height - inset)
            canvas.drawRoundRect(rect, radius, radius, fill)
            canvas.drawRoundRect(rect, radius, radius, stroke)
        }
    }
}
