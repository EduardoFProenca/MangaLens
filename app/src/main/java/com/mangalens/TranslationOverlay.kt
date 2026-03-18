package com.mangalens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.WindowManager
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent

/**
 * Overlay de tradução direta (modo 📖).
 *
 * Métodos hide()/show() permitem que o ContinuousCapture esconda
 * temporariamente o overlay antes de capturar a tela, evitando que
 * o OCR leia o texto que o próprio app escreveu.
 */
object TranslationOverlay {

    private var overlayView: View? = null
    private var windowManagerRef: WindowManager? = null

    fun show(
        context: Context,
        windowManager: WindowManager,
        results: List<TextResult>,
        screenWidth: Int  = context.resources.displayMetrics.widthPixels,
        screenHeight: Int = context.resources.displayMetrics.heightPixels,
        cropRect: Rect?   = null
    ) {
        dismiss(windowManager)
        if (results.isEmpty()) return

        windowManagerRef = windowManager

        val drawView = TranslationDrawView(
            context, results, screenWidth, screenHeight, cropRect
        ) { dismiss(windowManager) }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        windowManager.addView(drawView, params)
        overlayView = drawView
    }

    /**
     * Torna o overlay invisível SEM removê-lo do WindowManager.
     * Usado antes de capturar a tela no modo contínuo.
     */
    fun hide() {
        overlayView?.visibility = View.INVISIBLE
    }

    /**
     * Restaura a visibilidade do overlay após a captura.
     * Chamado quando o frame é descartado (sem novo conteúdo).
     */
    fun restore() {
        overlayView?.visibility = View.VISIBLE
    }

    /** Remove completamente o overlay. */
    fun dismiss(windowManager: WindowManager) {
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView      = null
        windowManagerRef = null
    }

    /** Verifica se há um overlay visível na tela. */
    fun isVisible(): Boolean = overlayView?.visibility == View.VISIBLE
}

// ─────────────────────────────────────────────────────
// VIEW DE DESENHO
// ─────────────────────────────────────────────────────

@SuppressLint("ClickableViewAccessibility")
class TranslationDrawView(
    context: Context,
    private val results: List<TextResult>,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val cropRect: Rect?,
    private val onDismiss: () -> Unit
) : View(context) {

    private val density = context.resources.displayMetrics.density

    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 255, 245)
        style = Paint.Style.FILL
    }
    private val bubbleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.argb(190, 80, 80, 200)
        style       = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color    = Color.argb(255, 15, 15, 70)
        textSize = 14f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color    = Color.argb(160, 255, 255, 255)
        textSize = 11f * density
        setShadowLayer(4f, 0f, 2f, Color.BLACK)
    }
    private val scenePaint = Paint().apply {
        color = Color.argb(25, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val padH    = 10f * density
    private val padV    = 6f  * density
    private val cornerR = 8f  * density

    init {
        setOnTouchListener { _, _ -> onDismiss(); true }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scenePaint)

        val offsetX = cropRect?.left?.toFloat() ?: 0f
        val offsetY = cropRect?.top?.toFloat()  ?: 0f

        results.forEach { result ->
            val box         = result.boundingBox ?: return@forEach
            val translation = result.translatedText.ifBlank { result.originalText }

            val absLeft   = box.left.toFloat()   + offsetX
            val absTop    = box.top.toFloat()     + offsetY
            val absRight  = box.right.toFloat()   + offsetX
            val absBottom = box.bottom.toFloat()  + offsetY

            val maxWidth   = (absRight - absLeft).coerceAtLeast(80f * density)
            val lines      = wrapText(translation, textPaint, maxWidth - padH * 2)
            val lineHeight = textPaint.fontSpacing
            val boxW       = maxWidth
            val boxH       = lines.size * lineHeight + padV * 2

            // Posição: acima do texto original; se não couber, abaixo
            var drawLeft = absLeft.coerceAtMost(screenWidth - boxW)
            var drawTop  = absTop - boxH - 4f * density
            if (drawTop < 0f) drawTop = absBottom + 4f * density

            val boxRect = RectF(drawLeft, drawTop, drawLeft + boxW, drawTop + boxH)

            canvas.drawRoundRect(boxRect, cornerR, cornerR, bubblePaint)
            canvas.drawRoundRect(boxRect, cornerR, cornerR, bubbleStrokePaint)

            lines.forEachIndexed { i, line ->
                canvas.drawText(
                    line,
                    drawLeft + padH,
                    drawTop + padV + lineHeight * i + textPaint.textSize,
                    textPaint
                )
            }
        }

        val hint = "Toque para fechar"
        canvas.drawText(
            hint,
            (width - hintPaint.measureText(hint)) / 2f,
            height - 28f * density,
            hintPaint
        )
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words   = text.split(" ")
        val lines   = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current = candidate
            } else {
                if (current.isNotEmpty()) lines.add(current)
                current = word
            }
        }
        if (current.isNotEmpty()) lines.add(current)
        return lines.ifEmpty { listOf(text) }
    }
}