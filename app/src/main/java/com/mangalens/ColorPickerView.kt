// app/src/main/java/com/mangalens/ColorPickerView.kt
package com.mangalens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Conta-gotas: exibe um bitmap e permite tocar em qualquer pixel para
 * capturar sua cor. Uma lupa ampliada segue o dedo para precisão.
 */
@SuppressLint("ClickableViewAccessibility")
class ColorPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var bitmap: Bitmap? = null
        set(value) { field = value; invalidate() }

    var onColorPicked: ((Int) -> Unit)? = null

    private var touchX = -1f
    private var touchY = -1f
    private var pickedColor = Color.WHITE
    private var isDragging = false

    // Paint para o círculo lupa
    private val loupePaint   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val loupeBorder  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 3f
        color       = Color.WHITE
    }
    private val loupeShadow  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 5f
        color       = Color.argb(120, 0, 0, 0)
    }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.WHITE
        style       = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val colorPreviewPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.WHITE
        textSize  = 28f
        textAlign = Paint.Align.CENTER
        setShadowLayer(3f, 0f, 1f, Color.BLACK)
    }

    // Escala bitmap → view
    private val matrix = Matrix()
    private val inverseMatrix = Matrix()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return

        // Escala proporcional para caber na view
        val scaleX = width.toFloat() / bmp.width
        val scaleY = height.toFloat() / bmp.height
        val scale  = minOf(scaleX, scaleY)
        val dx     = (width  - bmp.width  * scale) / 2f
        val dy     = (height - bmp.height * scale) / 2f

        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
        matrix.invert(inverseMatrix)

        canvas.drawBitmap(bmp, matrix, null)

        if (!isDragging || touchX < 0) return

        // Coordenadas no bitmap
        val pts = floatArrayOf(touchX, touchY)
        inverseMatrix.mapPoints(pts)
        val bx = pts[0].toInt().coerceIn(0, bmp.width  - 1)
        val by = pts[1].toInt().coerceIn(0, bmp.height - 1)
        pickedColor = bmp.getPixel(bx, by)

        // Desenha lupa de 80dp acima do dedo
        val loupeR   = 60f * resources.displayMetrics.density
        val loupeCX  = touchX.coerceIn(loupeR, width  - loupeR)
        val loupeCY  = (touchY - loupeR * 1.8f).coerceIn(loupeR, height - loupeR)

        // Conteúdo ampliado (5×) dentro da lupa usando BitmapShader
        val loupeZoom = 5f
        val shaderMatrix = Matrix().apply {
            setScale(scale * loupeZoom, scale * loupeZoom)
            postTranslate(
                loupeCX - touchX * scale * loupeZoom + dx * loupeZoom,
                loupeCY - touchY * scale * loupeZoom + dy * loupeZoom
            )
        }
        val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(shaderMatrix)
        }
        loupePaint.shader = shader

        canvas.drawCircle(loupeCX, loupeCY, loupeR + 4f, loupeShadow)
        canvas.drawCircle(loupeCX, loupeCY, loupeR, loupePaint)
        canvas.drawCircle(loupeCX, loupeCY, loupeR, loupeBorder)

        // Mira no centro
        canvas.drawLine(loupeCX - 15f, loupeCY, loupeCX + 15f, loupeCY, crosshairPaint)
        canvas.drawLine(loupeCX, loupeCY - 15f, loupeCX, loupeCY + 15f, crosshairPaint)

        // Preview da cor e código hex
        colorPreviewPaint.color = pickedColor
        val hexColor = String.format("#%06X", 0xFFFFFF and pickedColor)
        canvas.drawText(hexColor, loupeCX, loupeCY + loupeR + 40f, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        touchX = event.x
        touchY = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                isDragging = true
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                isDragging = false
                onColorPicked?.invoke(pickedColor)
                invalidate()
            }
        }
        return true
    }
}