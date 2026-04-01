// app/src/main/java/com/mangalens/ColorPickerView.kt
package com.mangalens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Conta-gotas com lupa ampliada.
 *
 * CORREÇÃO DA SHADER MATRIX:
 *
 * O bitmap é exibido com:
 *   matrix.setScale(scale, scale)
 *   matrix.postTranslate(dx, dy)
 * Portanto pixel (bx, by) do bitmap → posição na tela (bx·scale + dx, by·scale + dy).
 *
 * Pixel sob o dedo:
 *   bx_finger = (touchX − dx) / scale
 *
 * Para a lupa (BitmapShader com setScale(scale·zoom, ...) e postTranslate(tx, ...)):
 *   loupeCX = bx_finger · (scale · zoom) + tx
 *   tx = loupeCX − bx_finger · scale · zoom
 *      = loupeCX − ((touchX − dx) / scale) · scale · zoom
 *      = loupeCX − (touchX − dx) · zoom
 *      = loupeCX − touchX · zoom + dx · zoom         ← CORRETO
 *
 * Código antigo tinha:
 *   loupeCX − touchX · scale · zoom + dx · zoom      ← ERRADO (scale a mais)
 *
 * Com a correção, o centro da mira exibe exatamente a cor capturada.
 */
@SuppressLint("ClickableViewAccessibility")
class ColorPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var bitmap: Bitmap? = null
        set(value) { field = value; invalidate() }

    var onColorPicked: ((Int) -> Unit)? = null

    private var touchX    = -1f
    private var touchY    = -1f
    private var pickedColor = Color.WHITE
    private var isDragging  = false

    // ─── Paints ─────────────────────────────────

    private val loupePaint    = Paint(Paint.ANTI_ALIAS_FLAG)
    private val loupeBorder   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.WHITE }
    private val loupeShadow   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 6f; color = Color.argb(100, 0, 0, 0) }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val colorPreviewPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 28f; textAlign = Paint.Align.CENTER
        setShadowLayer(3f, 0f, 1f, Color.BLACK) }

    // Matrizes para mapear coordenadas view ↔ bitmap
    private val dispMatrix    = Matrix()
    private val inverseMatrix = Matrix()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return

        // ── 1. Calcula escala para caber na view ─────────────────────────
        val scale = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val dx    = (width  - bmp.width  * scale) / 2f
        val dy    = (height - bmp.height * scale) / 2f

        dispMatrix.setScale(scale, scale)
        dispMatrix.postTranslate(dx, dy)
        dispMatrix.invert(inverseMatrix)

        canvas.drawBitmap(bmp, dispMatrix, null)

        if (!isDragging || touchX < 0) return

        // ── 2. Cor no ponto exato do dedo ───────────────────────────────
        val pts = floatArrayOf(touchX, touchY)
        inverseMatrix.mapPoints(pts)
        val bx = pts[0].toInt().coerceIn(0, bmp.width  - 1)
        val by = pts[1].toInt().coerceIn(0, bmp.height - 1)
        pickedColor = bmp.getPixel(bx, by)

        // ── 3. Lupa ──────────────────────────────────────────────────────
        val loupeZoom = 5f
        val loupeR    = 62f * resources.displayMetrics.density

        // Centro da lupa: acima do dedo para não ser tampado pelo polegar
        val loupeCX = touchX.coerceIn(loupeR, width  - loupeR)
        val loupeCY = (touchY - loupeR * 1.8f).coerceIn(loupeR, height - loupeR)

        // ── SHADER MATRIX (CORREÇÃO PRINCIPAL) ───────────────────────────
        //
        // A fórmula correta para que o pixel sob o dedo apareça no centro
        // da lupa (loupeCX, loupeCY):
        //
        //   sx = sy = scale * loupeZoom
        //
        //   tx = loupeCX − touchX * loupeZoom + dx * loupeZoom
        //       (= loupeCX − (touchX − dx) * loupeZoom)
        //
        // Verificação:
        //   bmp_pixel_at_finger = (touchX − dx) / scale
        //   canvas_x = bmp_x * sx + tx
        //             = ((touchX − dx) / scale) * (scale * loupeZoom)
        //               + loupeCX − touchX * loupeZoom + dx * loupeZoom
        //             = (touchX − dx) * loupeZoom
        //               + loupeCX − touchX * loupeZoom + dx * loupeZoom
        //             = loupeCX  ✓
        //
        val shaderMatrix = Matrix().apply {
            setScale(scale * loupeZoom, scale * loupeZoom)
            postTranslate(
                loupeCX - touchX * loupeZoom + dx * loupeZoom,
                loupeCY - touchY * loupeZoom + dy * loupeZoom
            )
        }

        val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(shaderMatrix)
        }
        loupePaint.shader = shader

        // Sombra, conteúdo, borda
        canvas.drawCircle(loupeCX, loupeCY, loupeR + 5f, loupeShadow)
        canvas.drawCircle(loupeCX, loupeCY, loupeR,      loupePaint)
        canvas.drawCircle(loupeCX, loupeCY, loupeR,      loupeBorder)

        // Mira no centro da lupa (aponta exatamente para o pixel capturado)
        canvas.drawLine(loupeCX - 18f, loupeCY, loupeCX + 18f, loupeCY, crosshairPaint)
        canvas.drawLine(loupeCX, loupeCY - 18f, loupeCX, loupeCY + 18f, crosshairPaint)

        // ── 4. Preview e código hex ──────────────────────────────────────
        colorPreviewPaint.color = pickedColor
        val hex = "#%06X".format(0xFFFFFF and pickedColor)
        canvas.drawText(hex, loupeCX, loupeCY + loupeR + 44f, textPaint)

        // Pastilha colorida ao lado do hex
        canvas.drawCircle(
            loupeCX - textPaint.measureText(hex) / 2f - 20f,
            loupeCY + loupeR + 44f - textPaint.textSize / 2f,
            10f, colorPreviewPaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        touchX = event.x
        touchY = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> { isDragging = true;  invalidate() }
            MotionEvent.ACTION_UP   -> {
                isDragging = false
                onColorPicked?.invoke(pickedColor)
                invalidate()
            }
        }
        return true
    }
}