// app/src/main/java/com/mangalens/LibraryFolderActivity.kt
package com.mangalens

import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class LibraryFolderActivity : AppCompatActivity() {

    private lateinit var folder: File
    private lateinit var gridView: GridView
    private lateinit var tvEmpty: TextView
    private val captures = mutableListOf<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val folderPath = intent.getStringExtra("folder_path") ?: run { finish(); return }
        folder = File(folderPath)
        if (!folder.exists()) { finish(); return }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A2E"))
        }

        // ── Toolbar ──────────────────────────────
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.parseColor("#16213E"))
        }
        val btnBack = TextView(this).apply {
            text     = "‹"
            textSize = 26f
            setTextColor(Color.parseColor("#BB86FC"))
            setPadding(dp(12), dp(4), dp(16), dp(4))
            setOnClickListener { finish() }
        }
        val tvTitle = TextView(this).apply {
            text     = "📁 ${folder.name}"
            textSize = 17f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        toolbar.addView(btnBack)
        toolbar.addView(tvTitle)
        root.addView(toolbar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))

        // ── Grade ─────────────────────────────────
        tvEmpty = TextView(this).apply {
            text      = "Nenhuma captura nesta pasta ainda.\n\nUse o botão 💾 no menu do overlay para salvar."
            textSize  = 14f
            gravity   = Gravity.CENTER
            setTextColor(Color.parseColor("#888888"))
            setPadding(dp(32), dp(64), dp(32), dp(32))
            visibility = View.GONE
        }

        gridView = GridView(this).apply {
            numColumns        = 2
            columnWidth       = GridView.AUTO_FIT
            stretchMode       = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing   = dp(8)
            horizontalSpacing = dp(8)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            clipToPadding = false
        }

        val frame = FrameLayout(this)
        frame.addView(tvEmpty)
        frame.addView(gridView)
        root.addView(frame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        refreshGrid()
    }

    override fun onResume() { super.onResume(); refreshGrid() }

    // ─────────────────────────────────────────────
    // DADOS
    // ─────────────────────────────────────────────

    private fun refreshGrid() {
        captures.clear()
        captures.addAll(GalleryManager.listCaptures(folder))

        tvEmpty.visibility  = if (captures.isEmpty()) View.VISIBLE else View.GONE
        gridView.visibility = if (captures.isEmpty()) View.GONE    else View.VISIBLE

        gridView.adapter = CaptureAdapter(this, captures)
        gridView.setOnItemClickListener  { _, _, pos, _ -> showFullScreen(captures[pos]) }
        gridView.setOnItemLongClickListener { _, _, pos, _ ->
            showCaptureOptions(captures[pos]); true
        }
    }

    // ─────────────────────────────────────────────
    // VISUALIZAÇÃO FULL SCREEN
    // ─────────────────────────────────────────────

    private fun showFullScreen(file: File) {
        val dialog = AlertDialog.Builder(
            this, android.R.style.Theme_Black_NoTitleBar_Fullscreen
        ).create()

        val layout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        val imageView = ImageView(this).apply {
            val bmp = BitmapFactory.decodeFile(file.absolutePath)
            setImageBitmap(bmp)
            scaleType    = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT)
            setOnClickListener { dialog.dismiss() }
        }

        val tvClose = TextView(this).apply {
            text     = "✕  Fechar"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.argb(180, 0, 0, 0))
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, dp(24), dp(8), 0)
            }
            setOnClickListener { dialog.dismiss() }
        }

        val tvFilename = TextView(this).apply {
            text     = formatTimestamp(file.nameWithoutExtension)
            textSize = 11f
            setTextColor(Color.parseColor("#BBBBBB"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.argb(180, 0, 0, 0))
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                setMargins(dp(8), 0, 0, dp(24))
            }
        }

        layout.addView(imageView)
        layout.addView(tvClose)
        layout.addView(tvFilename)

        dialog.setView(layout)
        dialog.show()
    }

    // ─────────────────────────────────────────────
    // OPÇÕES DE CAPTURA
    // ─────────────────────────────────────────────

    private fun showCaptureOptions(file: File) {
        val options = arrayOf("🔍  Ver em tela cheia", "🗑️  Deletar imagem")
        AlertDialog.Builder(this)
            .setTitle(formatTimestamp(file.nameWithoutExtension))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showFullScreen(file)
                    1 -> confirmDeleteCapture(file)
                }
            }
            .show()
    }

    private fun confirmDeleteCapture(file: File) {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Deletar imagem")
            .setMessage(
                "Deletar \"${formatTimestamp(file.nameWithoutExtension)}\"?" +
                        "\n\nEssa ação não pode ser desfeita."
            )
            .setPositiveButton("Deletar") { _, _ ->
                val ok = GalleryManager.deleteCapture(file)
                Toast.makeText(
                    this,
                    if (ok) "🗑️ Imagem deletada" else "❌ Erro ao deletar",
                    Toast.LENGTH_SHORT
                ).show()
                refreshGrid()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ─────────────────────────────────────────────
    // UTILITÁRIOS
    // ─────────────────────────────────────────────

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun formatTimestamp(name: String): String {
        return try {
            val sdf  = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
            val date = sdf.parse(name)!!
            SimpleDateFormat("dd/MM/yyyy  HH:mm:ss", Locale.getDefault()).format(date)
        } catch (e: Exception) { name }
    }

    // ─────────────────────────────────────────────
    // ADAPTER
    // ─────────────────────────────────────────────

    inner class CaptureAdapter(
        // CORREÇÃO: parâmetro 'ctx' recebe o Context e é usado em todos os lugares
        // onde antes estava 'context' (que não existe como propriedade neste escopo).
        private val ctx: Context,
        private val data: List<File>
    ) : BaseAdapter() {

        override fun getCount()          = data.size
        override fun getItem(pos: Int)   = data[pos]
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
            val file     = data[pos]
            // CORREÇÃO: usa ctx.resources em vez de resources (que buscaria
            // o resources da Activity via 'context' inexistente no escopo do adapter)
            val cellSize = (ctx.resources.displayMetrics.widthPixels - dp(8 * 3)) / 2

            val card = FrameLayout(ctx).apply {
                layoutParams  = AbsListView.LayoutParams(cellSize, cellSize)
                background    = GradientDrawable().apply {
                    setColor(Color.parseColor("#16213E"))
                    cornerRadius = dp(8).toFloat()
                }
                clipToOutline = true
            }

            val imageView = ImageView(ctx).apply {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                val bmp  = BitmapFactory.decodeFile(file.absolutePath, opts)
                if (bmp != null) setImageBitmap(bmp)
                else setImageResource(android.R.drawable.ic_menu_gallery)
                scaleType    = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT)
            }

            val tvDate = TextView(ctx).apply {
                text     = formatTimestamp(file.nameWithoutExtension)
                textSize = 9.5f
                setTextColor(Color.WHITE)
                setPadding(dp(6), dp(4), dp(6), dp(4))
                background = GradientDrawable().apply {
                    setColor(Color.argb(160, 0, 0, 0))
                }
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.BOTTOM }
            }

            card.addView(imageView)
            card.addView(tvDate)
            return card
        }
    }
}