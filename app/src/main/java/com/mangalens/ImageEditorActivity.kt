// app/src/main/java/com/mangalens/ImageEditorActivity.kt
package com.mangalens

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Layout
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * Editor de imagem — reescrito sem o cast inválido (LinearLayout as TextView)
 * que causava ClassCastException e crash imediato.
 *
 * Ferramentas:
 *  🖌  Pincel — desenho livre (cor + tamanho)
 *  T   Texto  — blocos estilizados (cor, bold, itálico, alinhamento, fundo)
 *  ✂️  Cortar — crop de bordas com alças arrastáveis
 *  🔍  Zoom   — pinch-to-zoom + botão reset
 *  🎨  Cor    — conta-gotas da imagem
 *  ↩   Undo
 *  💾  Salvar
 */
class ImageEditorActivity : AppCompatActivity() {

    private lateinit var editorView:    ImageEditorView
    private lateinit var optionsScroll: HorizontalScrollView
    private lateinit var optionsPanel:  LinearLayout
    private lateinit var filePath:      String
    private lateinit var originalBitmap: Bitmap

    private var activeColor = Color.WHITE

    // ─────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        filePath = intent.getStringExtra("file_path") ?: run { finish(); return }
        val file = File(filePath)
        if (!file.exists()) { finish(); return }

        originalBitmap = BitmapFactory.decodeFile(filePath) ?: run { finish(); return }

        setContentView(buildRootLayout())
    }

    // ─────────────────────────────────────────────
    // LAYOUT — sem cast inválido
    // ─────────────────────────────────────────────

    private fun buildRootLayout(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        // Toolbar superior
        root.addView(buildTopBar(), lp(MATCH, WRAP))

        // Canvas de edição (peso 1 = ocupa o espaço restante)
        editorView = ImageEditorView(this).apply {
            setBackground(originalBitmap)
            onTextPositionRequested = { x, y -> showTextDialog(x, y) }
        }
        root.addView(editorView, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // Painel de opções contextual (scroll horizontal)
        optionsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = HorizontalScrollView.OVER_SCROLL_NEVER
            setBackgroundColor(Color.parseColor("#0D0D1E"))
            visibility = View.GONE
        }
        optionsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        optionsScroll.addView(optionsPanel, lp(WRAP, WRAP))
        root.addView(optionsScroll, lp(MATCH, WRAP))

        // Toolbar inferior — construído com LinearLayout puro, sem cast
        root.addView(buildBottomBar(), lp(MATCH, WRAP))

        return root
    }

    // ─────────────────────────────────────────────
    // TOOLBAR SUPERIOR
    // ─────────────────────────────────────────────

    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#16213E"))
            setPadding(dp(4), dp(10), dp(8), dp(10))
        }
        bar.addView(iconButton("‹", Color.parseColor("#BB86FC"), size = 26f) { finish() })
        bar.addView(TextView(this).apply {
            text     = "✏️ Editor"
            textSize = 17f
            setTextColor(Color.WHITE)
            setPadding(dp(8), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        // Botão reset zoom
        bar.addView(labeledIconButton("🔎", "Reset zoom") {
            editorView.resetZoom()
            Toast.makeText(this, "Zoom resetado", Toast.LENGTH_SHORT).show()
        })
        return bar
    }

    // ─────────────────────────────────────────────
    // TOOLBAR INFERIOR — LinearLayout puro, zero casts
    // ─────────────────────────────────────────────

    private fun buildBottomBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#16213E"))
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }

        data class Btn(val icon: String, val label: String, val action: () -> Unit)

        val buttons = listOf(
            Btn("🖌", "Pincel")   { activateBrush() },
            Btn("T",  "Texto")    { activateText() },
            Btn("✂️", "Cortar")   { activateCrop() },
            Btn("🎨", "Cor")      { activateEyedropper() },
            Btn("↩",  "Desfazer") { editorView.undo() },
            Btn("💾", "Salvar")   { saveImage() }
        )

        buttons.forEach { btn ->
            // Cada botão é uma LinearLayout vertical (ícone + label)
            // SEM NENHUM CAST — só LinearLayout e TextView
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity     = Gravity.CENTER
                setPadding(dp(2), dp(4), dp(2), dp(4))
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                setOnClickListener { btn.action() }
            }
            col.addView(TextView(this).apply {
                text     = btn.icon
                textSize = 21f
                gravity  = Gravity.CENTER
                setTextColor(Color.WHITE)
            })
            col.addView(TextView(this).apply {
                text     = btn.label
                textSize = 9f
                gravity  = Gravity.CENTER
                setTextColor(Color.parseColor("#AAAAAA"))
            })
            bar.addView(col)
        }

        return bar
    }

    // ─────────────────────────────────────────────
    // ATIVAÇÃO DE FERRAMENTAS
    // ─────────────────────────────────────────────

    private fun activateBrush() {
        editorView.activeTool = ImageEditorView.Tool.BRUSH
        editorView.brushColor = activeColor
        showBrushPanel()
        toast("🖌 Pincel ativo")
    }

    private fun activateText() {
        editorView.activeTool = ImageEditorView.Tool.TEXT
        editorView.textColor  = activeColor
        showTextPanel()
        toast("T Toque na imagem para posicionar o texto")
    }

    private fun activateCrop() {
        editorView.activeTool = ImageEditorView.Tool.CROP
        editorView.initCrop()
        showCropPanel()
        toast("✂️ Arraste as alças para definir o corte")
    }

    private fun activateEyedropper() {
        editorView.activeTool = ImageEditorView.Tool.NONE
        hideOptionsPanel()
        showEyedropperDialog()
    }

    // ─────────────────────────────────────────────
    // PAINEL DE PINCEL
    // ─────────────────────────────────────────────

    private fun showBrushPanel() {
        optionsPanel.removeAllViews()

        optionsPanel.addView(panelLabel("Tamanho:"))

        listOf(4f to "P", 10f to "M", 22f to "G", 38f to "XG").forEach { (sz, lbl) ->
            optionsPanel.addView(
                toggleChip(lbl, editorView.brushSize == sz) {
                    editorView.brushSize = sz
                    showBrushPanel()
                }
            )
        }

        optionsPanel.addView(spacer())
        optionsPanel.addView(colorSwatch(activeColor) { showEyedropperDialog() })

        optionsScroll.visibility = View.VISIBLE
    }

    // ─────────────────────────────────────────────
    // PAINEL DE TEXTO
    // ─────────────────────────────────────────────

    private fun showTextPanel() {
        optionsPanel.removeAllViews()

        optionsPanel.addView(toggleChip("B", editorView.textBold) {
            editorView.textBold = !editorView.textBold; showTextPanel()
        })
        optionsPanel.addView(toggleChip("I", editorView.textItalic) {
            editorView.textItalic = !editorView.textItalic; showTextPanel()
        })

        listOf(
            "⬅" to Layout.Alignment.ALIGN_NORMAL,
            "≡" to Layout.Alignment.ALIGN_CENTER,
            "➡" to Layout.Alignment.ALIGN_OPPOSITE
        ).forEach { (icon, align) ->
            optionsPanel.addView(toggleChip(icon, editorView.textAlignment == align) {
                editorView.textAlignment = align; showTextPanel()
            })
        }

        optionsPanel.addView(toggleChip("▣ fundo", editorView.textHasBackground) {
            editorView.textHasBackground = !editorView.textHasBackground; showTextPanel()
        })

        optionsPanel.addView(spacer())
        optionsPanel.addView(colorSwatch(activeColor) { showEyedropperDialog() })

        optionsScroll.visibility = View.VISIBLE
    }

    // ─────────────────────────────────────────────
    // PAINEL DE CROP
    // ─────────────────────────────────────────────

    private fun showCropPanel() {
        optionsPanel.removeAllViews()

        optionsPanel.addView(panelLabel("Arraste as alças nos cantos"))
        optionsPanel.addView(spacer())

        // Botão confirmar corte
        optionsPanel.addView(actionChip("✅ Aplicar corte", Color.parseColor("#2E7D32")) {
            val ok = editorView.applyCrop()
            if (ok) {
                toast("✅ Imagem cortada!")
                hideOptionsPanel()
            } else {
                toast("Selecione a área primeiro")
            }
        })

        // Botão cancelar
        optionsPanel.addView(actionChip("❌ Cancelar", Color.parseColor("#B71C1C")) {
            editorView.activeTool = ImageEditorView.Tool.NONE
            editorView.clearAll()
            hideOptionsPanel()
        })

        optionsScroll.visibility = View.VISIBLE
    }

    private fun hideOptionsPanel() {
        optionsScroll.visibility = View.GONE
        optionsPanel.removeAllViews()
    }

    // ─────────────────────────────────────────────
    // CONTA-GOTAS
    // ─────────────────────────────────────────────

    private fun showEyedropperDialog() {
        val prevTool = editorView.activeTool
        editorView.activeTool = ImageEditorView.Tool.NONE

        val dialog = AlertDialog.Builder(
            this, android.R.style.Theme_Black_NoTitleBar_Fullscreen
        ).create()

        val picker = ColorPickerView(this).apply {
            bitmap = originalBitmap
            onColorPicked = { color ->
                activeColor             = color
                editorView.brushColor   = color
                editorView.textColor    = color
                dialog.dismiss()
                // Restaura ferramenta anterior e atualiza painel
                editorView.activeTool = prevTool
                when (prevTool) {
                    ImageEditorView.Tool.BRUSH -> showBrushPanel()
                    ImageEditorView.Tool.TEXT  -> showTextPanel()
                    else -> hideOptionsPanel()
                }
                toast("🎨 Cor: #${String.format("%06X", 0xFFFFFF and color)}")
            }
        }

        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(picker, lp(MATCH, MATCH))
            addView(
                iconButton("✕", Color.WHITE, size = 18f) { dialog.dismiss() },
                FrameLayout.LayoutParams(WRAP, WRAP).apply {
                    gravity = Gravity.TOP or Gravity.END
                    setMargins(0, dp(28), dp(12), 0)
                }
            )
        }

        dialog.setView(frame)
        dialog.show()
    }

    // ─────────────────────────────────────────────
    // DIALOG DE TEXTO
    // ─────────────────────────────────────────────

    private fun showTextDialog(x: Float, y: Float) {
        val editText = EditText(this).apply {
            hint     = "Digite o texto..."
            textSize = 15f
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle("✏️ Inserir texto")
            .setView(editText)
            .setPositiveButton("Adicionar") { _, _ ->
                val txt = editText.text.toString().trim()
                if (txt.isNotEmpty()) {
                    editorView.textColor = activeColor
                    editorView.addTextBlock(txt, x, y)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ─────────────────────────────────────────────
    // SALVAR
    // ─────────────────────────────────────────────

    private fun saveImage() {
        try {
            val exported = editorView.exportBitmap()
            java.io.FileOutputStream(filePath).use { out ->
                exported.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
            toast("✅ Imagem salva!")
            setResult(RESULT_OK)
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "❌ Erro: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ─────────────────────────────────────────────
    // WIDGETS AUXILIARES — todos retornam tipo exato, zero casts
    // ─────────────────────────────────────────────

    /** Botão só com ícone, sem label */
    private fun iconButton(icon: String, color: Int = Color.WHITE,
                           size: Float = 18f, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text     = icon
            textSize = size
            setTextColor(color)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { onClick() }
        }

    /** Coluna ícone + label (para top bar) */
    private fun labeledIconButton(icon: String, label: String,
                                  onClick: () -> Unit): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setOnClickListener { onClick() }
        }
        col.addView(TextView(this).apply {
            text     = icon; textSize = 16f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        })
        col.addView(TextView(this).apply {
            text     = label; textSize = 8f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#AAAAAA"))
        })
        return col
    }

    /** Chip de toggle (ativo/inativo) */
    private fun toggleChip(label: String, active: Boolean,
                           onClick: () -> Unit): TextView =
        TextView(this).apply {
            text     = label
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(if (active) Color.parseColor("#BB86FC")
                else        Color.parseColor("#33FFFFFF"))
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) }
            setOnClickListener { onClick() }
        }

    /** Chip de ação (cor customizada) */
    private fun actionChip(label: String, bgColor: Int,
                           onClick: () -> Unit): TextView =
        TextView(this).apply {
            text     = label
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) }
            setOnClickListener { onClick() }
        }

    /** Swatch colorido que abre o conta-gotas */
    private fun colorSwatch(color: Int, onClick: () -> Unit): View =
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(4) }
            background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(2), Color.WHITE)
            }
            setOnClickListener { onClick() }
        }

    /** Label de texto simples para painéis */
    private fun panelLabel(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize  = 11f
            setTextColor(Color.parseColor("#BBBBBB"))
            setPadding(0, 0, dp(10), 0)
        }

    /** Espaçador flexível */
    private fun spacer(): View =
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP  = ViewGroup.LayoutParams.WRAP_CONTENT
        private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)
    }
}