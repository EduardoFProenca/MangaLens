// app/src/main/java/com/mangalens/SaveOverlay.kt
package com.mangalens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Overlay flutuante de salvamento.
 *
 * Exibido após uma captura bem-sucedida, permite:
 *  1. Escolher em qual pasta salvar (chips horizontais com pastas existentes)
 *  2. Criar uma nova pasta e salvar diretamente
 *  3. Cancelar sem salvar
 */
object SaveOverlay {

    private var view: View? = null

    fun show(
        context: Context,
        windowManager: WindowManager,
        bitmap: Bitmap,
        onSaved: (folderName: String) -> Unit = {},
        onDismiss: () -> Unit = {}
    ) {
        dismiss(windowManager)

        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        // Usa java.io.File explicitamente — sem ambiguidade
        val folders: List<File> = GalleryManager.listFolders(context)

        // ── Raiz ──────────────────────────────────
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.argb(252, 18, 18, 38))
                cornerRadius = dp(16).toFloat()
            }
        }

        // ── Título ────────────────────────────────
        root.addView(TextView(context).apply {
            text     = "💾 Salvar captura"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity  = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        })

        // ── Seletor de pasta (só se existirem pastas) ──
        if (folders.isEmpty()) {
            root.addView(TextView(context).apply {
                text     = "Nenhuma pasta ainda. Crie uma abaixo ↓"
                textSize = 13f
                setTextColor(Color.parseColor("#AAAAAA"))
                gravity  = Gravity.CENTER
                setPadding(0, 0, 0, dp(8))
            })
        } else {
            root.addView(TextView(context).apply {
                text     = "Salvar em:"
                textSize = 12f
                setTextColor(Color.parseColor("#AAAAAA"))
                setPadding(0, 0, 0, dp(6))
            })

            // Scroll horizontal dos chips de pasta
            val scroll = HorizontalScrollView(context).apply {
                // Corrigido: API correta para esconder scrollbar
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
            }

            val chipRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            // Variável mutável com tipo explícito java.io.File
            var selectedFolder: File? = null
            val chipViews = mutableListOf<TextView>()

            folders.forEach { folder ->
                val chip = TextView(context).apply {
                    // folder.name agora resolve corretamente (java.io.File.name)
                    text     = "📁 ${folder.name}"
                    textSize = 12f
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#33FFFFFF"))
                        cornerRadius = dp(20).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(8) }

                    setOnClickListener {
                        selectedFolder = folder
                        // Desmarca todos os chips
                        chipViews.forEach { cv ->
                            (cv.background as? GradientDrawable)
                                ?.setColor(Color.parseColor("#33FFFFFF"))
                        }
                        // Marca o chip tocado
                        (background as? GradientDrawable)
                            ?.setColor(Color.parseColor("#BB86FC"))
                    }
                }
                chipViews.add(chip)
                chipRow.addView(chip)
            }

            scroll.addView(chipRow)
            root.addView(
                scroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            )

            // ── Botão: salvar na pasta selecionada ──
            root.addView(TextView(context).apply {
                text     = "💾  Salvar na pasta selecionada"
                textSize = 14f
                gravity  = Gravity.CENTER
                setTextColor(Color.parseColor("#1A1A2E"))
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#BB86FC"))
                    cornerRadius = dp(8).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }

                setOnClickListener {
                    val target = selectedFolder ?: run {
                        Toast.makeText(
                            context,
                            "Selecione uma pasta primeiro",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setOnClickListener
                    }
                    val saved = GalleryManager.saveCapture(target, bitmap)
                    if (saved != null) {
                        Toast.makeText(
                            context,
                            "✅ Salvo em \"${target.name}\"",
                            Toast.LENGTH_SHORT
                        ).show()
                        onSaved(target.name)
                    } else {
                        Toast.makeText(context, "❌ Erro ao salvar", Toast.LENGTH_SHORT).show()
                    }
                    dismiss(windowManager)
                    onDismiss()
                }
            })
        }

        // ── Botão: criar nova pasta e salvar ──────
        root.addView(TextView(context).apply {
            text     = "＋  Criar pasta e salvar"
            textSize = 13f
            gravity  = Gravity.CENTER
            setTextColor(Color.parseColor("#BB86FC"))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#22BB86FC"))
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }

            setOnClickListener {
                dismiss(windowManager)
                showCreateAndSaveDialog(context, windowManager, bitmap, onSaved, onDismiss)
            }
        })

        // ── Botão: cancelar ───────────────────────
        root.addView(TextView(context).apply {
            text     = "Cancelar"
            textSize = 12f
            gravity  = Gravity.CENTER
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, dp(10), 0, dp(4))
            setOnClickListener { dismiss(windowManager); onDismiss() }
        })

        // ── Parâmetros da janela ──────────────────
        val params = WindowManager.LayoutParams(
            (300 * density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        windowManager.addView(root, params)
        view = root
    }

    // ─────────────────────────────────────────────
    // CRIAR PASTA + SALVAR VIA OverlayEditor
    // ─────────────────────────────────────────────

    private fun showCreateAndSaveDialog(
        context: Context,
        windowManager: WindowManager,
        bitmap: Bitmap,
        onSaved: (String) -> Unit,
        onDismiss: () -> Unit
    ) {
        OverlayEditor.show(
            context       = context,
            windowManager = windowManager,
            title         = "📁 Nome da nova pasta",
            initialText   = "",
            hint          = "Ex: Capítulo 1, Lutas Favoritas...",
            onConfirm     = { name ->
                // Tenta criar; se já existir, usa a existente
                val folder: File = GalleryManager.createFolder(context, name)
                    ?: GalleryManager.listFolders(context).find { it.name == name }
                    ?: run {
                        Toast.makeText(
                            context,
                            "❌ Não foi possível criar a pasta",
                            Toast.LENGTH_SHORT
                        ).show()
                        onDismiss()
                        return@show
                    }

                val saved = GalleryManager.saveCapture(folder, bitmap)
                if (saved != null) {
                    Toast.makeText(
                        context,
                        "✅ Salvo na nova pasta \"${folder.name}\"",
                        Toast.LENGTH_SHORT
                    ).show()
                    onSaved(folder.name)
                } else {
                    Toast.makeText(context, "❌ Erro ao salvar", Toast.LENGTH_SHORT).show()
                }
                onDismiss()
            },
            onCancel = { onDismiss() }
        )
    }

    // ─────────────────────────────────────────────
    // DISMISS
    // ─────────────────────────────────────────────

    fun dismiss(windowManager: WindowManager) {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
    }
}