// app/src/main/java/com/mangalens/LibraryActivity.kt
package com.mangalens

import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class LibraryActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var tvEmpty: TextView
    private val folders = mutableListOf<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A2E"))
        }

        // ── Toolbar ──────────────────────────────
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.parseColor("#16213E"))
        }
        val tvTitle = TextView(this).apply {
            text      = "📚 Biblioteca MangaLens"
            textSize  = 18f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnNewFolder = TextView(this).apply {
            text     = "＋ Nova pasta"
            textSize = 14f
            setTextColor(Color.parseColor("#BB86FC"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#33BB86FC"))
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener { showNewFolderDialog() }
        }
        toolbar.addView(tvTitle)
        toolbar.addView(btnNewFolder)
        root.addView(toolbar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))

        // ── Lista ─────────────────────────────────
        tvEmpty = TextView(this).apply {
            text      = "Nenhuma pasta ainda.\nToque em \"＋ Nova pasta\" para criar."
            textSize  = 15f
            gravity   = Gravity.CENTER
            setTextColor(Color.parseColor("#888888"))
            setPadding(dp(32), dp(64), dp(32), dp(32))
            visibility = View.GONE
        }

        listView = ListView(this).apply {
            divider       = null
            dividerHeight = dp(8)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            clipToPadding = false
        }

        val scrollFrame = FrameLayout(this)
        scrollFrame.addView(tvEmpty)
        scrollFrame.addView(listView)
        root.addView(scrollFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        refreshList()
    }

    override fun onResume() { super.onResume(); refreshList() }

    // ─────────────────────────────────────────────
    // DADOS
    // ─────────────────────────────────────────────

    private fun refreshList() {
        folders.clear()
        folders.addAll(GalleryManager.listFolders(this))

        tvEmpty.visibility  = if (folders.isEmpty()) View.VISIBLE else View.GONE
        listView.visibility = if (folders.isEmpty()) View.GONE    else View.VISIBLE

        listView.adapter = FolderAdapter(this, folders)
        listView.setOnItemClickListener { _, _, pos, _ ->
            val intent = Intent(this, LibraryFolderActivity::class.java)
            intent.putExtra("folder_path", folders[pos].absolutePath)
            startActivity(intent)
        }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            showFolderOptions(folders[pos]); true
        }
    }

    // ─────────────────────────────────────────────
    // DIALOGS
    // ─────────────────────────────────────────────

    private fun showNewFolderDialog() {
        val editText = EditText(this).apply {
            hint     = "Ex: Capítulo 1, Lutas Favoritas..."
            textSize = 15f
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("📁 Nova pasta")
            .setView(editText)
            .setPositiveButton("Criar") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(this, "Nome inválido", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val folder = GalleryManager.createFolder(this, name)
                if (folder == null) {
                    Toast.makeText(this,
                        "❌ Já existe uma pasta com esse nome",
                        Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this,
                        "✅ Pasta \"$name\" criada!",
                        Toast.LENGTH_SHORT).show()
                    refreshList()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showFolderOptions(folder: File) {
        val options = arrayOf("✏️  Renomear", "🗑️  Deletar pasta e conteúdo")
        AlertDialog.Builder(this)
            .setTitle("📁 ${folder.name}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameFolderDialog(folder)
                    1 -> confirmDeleteFolder(folder)
                }
            }
            .show()
    }

    private fun showRenameFolderDialog(folder: File) {
        val editText = EditText(this).apply {
            setText(folder.name)
            textSize = 15f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("✏️ Renomear pasta")
            .setView(editText)
            .setPositiveButton("Salvar") { _, _ ->
                val newName = editText.text.toString().trim()
                val ok = GalleryManager.renameFolder(folder, newName)
                Toast.makeText(this,
                    if (ok) "✅ Renomeada para \"$newName\""
                    else    "❌ Não foi possível renomear",
                    Toast.LENGTH_SHORT).show()
                refreshList()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmDeleteFolder(folder: File) {
        val count = GalleryManager.captureCount(folder)
        AlertDialog.Builder(this)
            .setTitle("⚠️ Deletar pasta")
            .setMessage(
                "Deletar \"${folder.name}\" e todas as $count imagem(ns) dentro dela?" +
                        "\n\nEssa ação não pode ser desfeita."
            )
            .setPositiveButton("Deletar") { _, _ ->
                val ok = GalleryManager.deleteFolder(folder)
                Toast.makeText(this,
                    if (ok) "🗑️ Pasta deletada" else "❌ Erro ao deletar",
                    Toast.LENGTH_SHORT).show()
                refreshList()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ─────────────────────────────────────────────
    // UTILITÁRIO
    // ─────────────────────────────────────────────

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // ─────────────────────────────────────────────
    // ADAPTER
    // ─────────────────────────────────────────────

    inner class FolderAdapter(
        // CORREÇÃO: parâmetro 'ctx' usado em todos os lugares onde antes
        // estava 'context' (inexistente como propriedade neste escopo).
        private val ctx: Context,
        private val data: List<File>
    ) : BaseAdapter() {

        override fun getCount()          = data.size
        override fun getItem(pos: Int)   = data[pos]
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
            val folder = data[pos]
            val count  = GalleryManager.captureCount(folder)
            val size   = GalleryManager.formatSize(GalleryManager.folderSizeBytes(folder))

            // CORREÇÃO: ctx no lugar de context em todos os construtores de View
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background  = GradientDrawable().apply {
                    setColor(Color.parseColor("#16213E"))
                    cornerRadius = dp(10).toFloat()
                }
            }

            val ivThumb = ImageView(ctx).apply {
                val captures = GalleryManager.listCaptures(folder)
                if (captures.isNotEmpty()) {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    val bmp  = BitmapFactory.decodeFile(
                        captures.first().absolutePath, opts)
                    if (bmp != null) setImageBitmap(bmp)
                    else setImageResource(android.R.drawable.ic_menu_gallery)
                } else {
                    setImageResource(android.R.drawable.ic_menu_gallery)
                }
                scaleType    = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                    marginEnd = dp(14)
                }
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0D3B66"))
                    cornerRadius = dp(6).toFloat()
                }
            }

            val textCol = LinearLayout(ctx).apply {
                orientation  = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvName = TextView(ctx).apply {
                text     = "📁 ${folder.name}"
                textSize = 15f
                setTextColor(Color.WHITE)
            }
            val tvInfo = TextView(ctx).apply {
                text     = "$count imagem(ns) • $size"
                textSize = 12f
                setTextColor(Color.parseColor("#AAAAAA"))
            }
            textCol.addView(tvName)
            textCol.addView(tvInfo)

            val tvArrow = TextView(ctx).apply {
                text     = "›"
                textSize = 22f
                setTextColor(Color.parseColor("#BB86FC"))
                setPadding(dp(8), 0, 0, 0)
            }

            card.addView(ivThumb)
            card.addView(textCol)
            card.addView(tvArrow)

            // Wrapper com padding vertical entre itens da lista
            val wrapper = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), 0, dp(4))
                addView(card)
            }
            return wrapper
        }
    }
}