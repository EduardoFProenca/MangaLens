package com.mangalens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.*
import android.graphics.PixelFormat
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Overlay de seleção de área.
 * Exibido a cada toque quando o modo Área está ativo e sem trava.
 */
object AreaSelectorOverlay {

    private var overlayView: View? = null

    fun show(
        context: Context,
        windowManager: WindowManager,
        onAreaSelected: (Rect) -> Unit,
        onCancel: () -> Unit = {}
    ) {
        dismiss(windowManager)

        val themedCtx = android.view.ContextThemeWrapper(context, R.style.Theme_MangaLens)
        val container = FrameLayout(themedCtx)

        val drawView = SelectionDrawView(themedCtx) { rect ->
            dismiss(windowManager)
            if (rect != null) onAreaSelected(rect) else onCancel()
        }

        val label = TextView(themedCtx).apply {
            text     = "✂️  Arraste para selecionar — solte para capturar"
            textSize = 13f
            setTextColor(Color.WHITE)
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            setPadding(24, 32, 24, 0)
        }

        container.addView(drawView)
        container.addView(label, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(container, params)
        overlayView = container
    }

    fun dismiss(windowManager: WindowManager) {
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
    }
}

// ─────────────────────────────────────────────────────
// VIEW DE SELEÇÃO
// ─────────────────────────────────────────────────────

@SuppressLint("ClickableViewAccessibility")
class SelectionDrawView(
    context: Context,
    private val onResult: (Rect?) -> Unit
) : View(context) {

    private var startX = 0f
    private var startY = 0f
    private var curX   = 0f
    private var curY   = 0f
    private var dragging = false

    private val bgPaint = Paint().apply {
        color = Color.argb(110, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val fillPaint = Paint().apply {
        color = Color.argb(35, 120, 200, 255)
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint().apply {
        color = Color.argb(230, 120, 200, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(14f, 6f), 0f)
    }
    private val cornerPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    init {
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x; startY = event.y
                    curX   = event.x; curY   = event.y
                    dragging = true;  invalidate(); true
                }
                MotionEvent.ACTION_MOVE -> {
                    curX = event.x; curY = event.y
                    invalidate(); true
                }
                MotionEvent.ACTION_UP -> {
                    dragging = false
                    val r = buildRect()
                    if (r.width() < 40 || r.height() < 40) onResult(null)
                    else onResult(r)
                    true
                }
                else -> false
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        if (!dragging && startX == 0f) return

        val rf = RectF(buildRect())
        canvas.drawRect(rf, fillPaint)
        canvas.drawRect(rf, strokePaint)

        // Alças nos cantos
        listOf(rf.left to rf.top, rf.right to rf.top,
            rf.left to rf.bottom, rf.right to rf.bottom)
            .forEach { (cx, cy) -> canvas.drawCircle(cx, cy, 8f, cornerPaint) }
    }

    private fun buildRect() = Rect(
        minOf(startX, curX).toInt(), minOf(startY, curY).toInt(),
        maxOf(startX, curX).toInt(), maxOf(startY, curY).toInt()
    )
}