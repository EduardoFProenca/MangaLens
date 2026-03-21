package com.mangalens

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Caixa de edição reutilizável para todos os pontos do app
 * onde o usuário pode corrigir texto manualmente.
 *
 * Usa TYPE_APPLICATION_OVERLAY + FLAG_WATCH_OUTSIDE_TOUCH
 * (sem FLAG_NOT_FOCUSABLE) para aceitar input do teclado.
 */
object OverlayEditor {

    private var editorView: View? = null

    /**
     * @param context       Contexto do serviço
     * @param windowManager WindowManager ativo
     * @param title         Título exibido no topo da caixa
     * @param initialText   Texto inicial já preenchido
     * @param hint          Placeholder quando vazio
     * @param gravityY      Distância do fundo da tela (default 220dp, acima do painel)
     * @param onConfirm     Callback com o texto confirmado
     * @param onCancel      Callback opcional ao cancelar
     */
    fun show(
        context: Context,
        windowManager: WindowManager,
        title: String,
        initialText: String,
        hint: String = "Digite o texto corrigido...",
        gravityY: Int? = null,   // null = centro da tela
        onConfirm: (String) -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        dismiss(windowManager)

        val density = context.resources.displayMetrics.density

        // ── Container ─────────────────────────────
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (16 * density).toInt(), (14 * density).toInt(),
                (16 * density).toInt(), (14 * density).toInt()
            )
            background = GradientDrawable().apply {
                setColor(Color.argb(250, 20, 20, 44))
                cornerRadius = 16f * density
            }
        }

        // ── Título ────────────────────────────────
        container.addView(TextView(context).apply {
            text     = title
            textSize = 13f
            setTextColor(Color.argb(200, 180, 180, 255))
            setPadding(0, 0, 0, (8 * density).toInt())
        })

        // ── Campo de texto ────────────────────────
        val editText = EditText(context).apply {
            setText(initialText)
            textSize     = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            this.hint    = hint
            background   = null
            setBackgroundColor(Color.argb(70, 100, 100, 180))
            setPadding(
                (10 * density).toInt(), (8 * density).toInt(),
                (10 * density).toInt(), (8 * density).toInt()
            )
            maxLines     = 5
            isSingleLine = false
            setSelection(initialText.length)
        }
        container.addView(editText)

        // ── Botões ────────────────────────────────
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.END
            setPadding(0, (10 * density).toInt(), 0, 0)
        }

        btnRow.addView(TextView(context).apply {
            text     = "Cancelar"
            textSize = 12f
            setTextColor(Color.argb(160, 180, 180, 180))
            setPadding(
                (12 * density).toInt(), (8 * density).toInt(),
                (12 * density).toInt(), (8 * density).toInt()
            )
            setOnClickListener {
                dismiss(windowManager)
                onCancel?.invoke()
            }
        })

        btnRow.addView(TextView(context).apply {
            text     = "✓ Confirmar"
            textSize = 12f
            setTextColor(Color.argb(255, 120, 210, 120))
            setPadding(
                (12 * density).toInt(), (8 * density).toInt(),
                (12 * density).toInt(), (8 * density).toInt()
            )
            setOnClickListener {
                val newText = editText.text.toString().trim()
                if (newText.isNotEmpty()) {
                    dismiss(windowManager)
                    onConfirm(newText)
                } else {
                    Toast.makeText(context, "O texto não pode ficar vazio", Toast.LENGTH_SHORT).show()
                }
            }
        })

        container.addView(btnRow)

        // ── Parâmetros da janela ──────────────────
        val gravity = if (gravityY != null) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        else Gravity.CENTER

        val params = WindowManager.LayoutParams(
            (310 * density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // SEM FLAG_NOT_FOCUSABLE → aceita foco e teclado
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity  = gravity
            y             = gravityY ?: 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

        windowManager.addView(container, params)
        editorView = container

        // Abre o teclado automaticamente
        Handler(Looper.getMainLooper()).postDelayed({
            editText.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }, 120)
    }

    fun dismiss(windowManager: WindowManager) {
        editorView?.let { v ->
            runCatching {
                val imm = v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)
            }
            runCatching { windowManager.removeView(v) }
        }
        editorView = null
    }

    val isShowing: Boolean get() = editorView != null
}