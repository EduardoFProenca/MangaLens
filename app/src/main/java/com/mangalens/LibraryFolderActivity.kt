// app/src/main/java/com/mangalens/LibraryFolderActivity.kt
package com.mangalens

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Grade de capturas com:
 *  - Ordenação por data ou manual (drag & drop)
 *  - Swipe horizontal para navegar entre imagens
 *  - Pinch-zoom + pan no visualizador de imagem
 *  - Abertura do editor ao toque longo
 *
 * MUDANÇAS v2.1:
 *  - Adicionado ScaleGestureDetector para pinch-zoom na imagem
 *  - Pan (arrastar) disponível quando zoom > 1
 *  - Removido fechamento por toque simples (onSingleTapUp não fecha mais)
 *  - Swipe horizontal ainda navega entre imagens
 *  - Swipe para baixo ainda fecha
 */
class LibraryFolderActivity : AppCompatActivity() {

    private lateinit var folder: File
    private lateinit var gridView: GridView
    private lateinit var tvEmpty: TextView
    private lateinit var btnSortDate: TextView
    private lateinit var btnSortManual: TextView

    private val captures = mutableListOf<File>()
    private var sortMode = GalleryManager.SortMode.BY_DATE

    // Drag & drop
    private var dragFromPos = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val folderPath = intent.getStringExtra("folder_path") ?: run { finish(); return }
        folder = File(folderPath)
        if (!folder.exists()) { finish(); return }

        buildUi()
        refreshGrid()
    }

    override fun onResume() { super.onResume(); refreshGrid() }

    // ─────────────────────────────────────────────
    // UI
    // ─────────────────────────────────────────────

    private fun buildUi() {
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
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        toolbar.addView(btnBack)
        toolbar.addView(tvTitle)
        root.addView(toolbar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ── Barra de ordenação ────────────────────
        val sortBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.parseColor("#0D0D1E"))
        }
        sortBar.addView(TextView(this).apply {
            text     = "Ordenar:"
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, 0, dp(10), 0)
        })

        btnSortDate = TextView(this).apply {
            text     = "📅 Por data"
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#BB86FC"))
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
            setOnClickListener { setSortMode(GalleryManager.SortMode.BY_DATE) }
        }

        btnSortManual = TextView(this).apply {
            text     = "✋ Manual"
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#33FFFFFF"))
                cornerRadius = dp(6).toFloat()
            }
            setOnClickListener { setSortMode(GalleryManager.SortMode.MANUAL) }
        }

        sortBar.addView(btnSortDate)
        sortBar.addView(btnSortManual)
        root.addView(sortBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ── Grade ─────────────────────────────────
        tvEmpty = TextView(this).apply {
            text      = "Nenhuma captura ainda.\n\nUse 💾 no overlay para salvar."
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
            clipToPadding     = false
        }

        val frame = FrameLayout(this)
        frame.addView(tvEmpty)
        frame.addView(gridView)
        root.addView(frame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    // ─────────────────────────────────────────────
    // ORDENAÇÃO
    // ─────────────────────────────────────────────

    private fun setSortMode(mode: GalleryManager.SortMode) {
        sortMode = mode
        val activeColor   = Color.parseColor("#BB86FC")
        val inactiveColor = Color.parseColor("#33FFFFFF")
        (btnSortDate.background as? GradientDrawable)?.setColor(
            if (mode == GalleryManager.SortMode.BY_DATE) activeColor else inactiveColor)
        (btnSortManual.background as? GradientDrawable)?.setColor(
            if (mode == GalleryManager.SortMode.MANUAL) activeColor else inactiveColor)

        if (mode == GalleryManager.SortMode.MANUAL) {
            Toast.makeText(this, "✋ Toque longo e arraste para reordenar", Toast.LENGTH_SHORT).show()
        }
        refreshGrid()
    }

    // ─────────────────────────────────────────────
    // DADOS
    // ─────────────────────────────────────────────

    private fun refreshGrid() {
        captures.clear()
        captures.addAll(GalleryManager.listCaptures(folder, sortMode))

        tvEmpty.visibility  = if (captures.isEmpty()) View.VISIBLE else View.GONE
        gridView.visibility = if (captures.isEmpty()) View.GONE    else View.VISIBLE

        val adapter = CaptureAdapter(this, captures)
        gridView.adapter = adapter

        // Toque simples → visualizador com swipe + zoom
        gridView.setOnItemClickListener { _, _, pos, _ ->
            showSwipeViewer(pos)
        }

        // Toque longo → opções (editar, deletar) ou iniciar drag no modo manual
        gridView.setOnItemLongClickListener { _, view, pos, _ ->
            if (sortMode == GalleryManager.SortMode.MANUAL) {
                startDragFromGrid(view, pos)
            } else {
                showCaptureOptions(captures[pos])
            }
            true
        }

        // Drag & drop dentro da grid (modo manual)
        setupDragDrop()
    }

    // ─────────────────────────────────────────────
    // VISUALIZADOR COM SWIPE + PINCH ZOOM + PAN
    // ─────────────────────────────────────────────

    private fun showSwipeViewer(startPosition: Int) {
        val dialog = AlertDialog.Builder(
            this, android.R.style.Theme_Black_NoTitleBar_Fullscreen
        ).create()

        var currentPos = startPosition

        // ── Estado de zoom/pan ────────────────────
        var scaleFactor  = 1f
        val minScale     = 1f
        val maxScale     = 8f
        var panX         = 0f
        var panY         = 0f
        var lastPanX     = 0f
        var lastPanY     = 0f
        var isPanning    = false
        var activePointerId = MotionEvent.INVALID_POINTER_ID

        val layout = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // Matrix aplicada ao ImageView para zoom/pan
        val matrix = Matrix()

        val imageView = ImageView(this).apply {
            scaleType    = ImageView.ScaleType.MATRIX
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT)
        }

        val tvCounter = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply { setColor(Color.argb(160, 0, 0, 0)) }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(dp(8), dp(24), 0, 0)
            }
        }

        // Dica de uso no topo
        val tvHint = TextView(this).apply {
            text     = "Pinch para zoom • Swipe para navegar • ▼ para fechar"
            textSize = 10f
            setTextColor(Color.argb(180, 255, 255, 255))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply { setColor(Color.argb(120, 0, 0, 0)) }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                setMargins(0, dp(24), 0, 0)
            }
        }

        val tvClose = TextView(this).apply {
            text     = "✕"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply { setColor(Color.argb(160, 0, 0, 0)) }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, dp(24), dp(8), 0)
            }
            setOnClickListener { dialog.dismiss() }
        }

        val tvEdit = TextView(this).apply {
            text     = "✏️ Editar"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.argb(200, 98, 0, 238))
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(0, 0, dp(12), dp(32))
            }
            setOnClickListener {
                dialog.dismiss()
                openEditor(captures[currentPos])
            }
        }

        // ── Função de reset do zoom ao trocar imagem ──
        fun resetZoom() {
            scaleFactor = 1f; panX = 0f; panY = 0f
            matrix.reset()
            imageView.imageMatrix = matrix
        }

        fun loadImage() {
            val bmp = BitmapFactory.decodeFile(captures[currentPos].absolutePath)
            imageView.setImageBitmap(bmp)
            tvCounter.text = "${currentPos + 1} / ${captures.size}"

            // Centraliza a imagem no ImageView usando FIT_CENTER manual
            imageView.post {
                if (bmp == null) return@post
                val vw = imageView.width.toFloat()
                val vh = imageView.height.toFloat()
                val scale = minOf(vw / bmp.width, vh / bmp.height)
                val dx = (vw - bmp.width * scale) / 2f
                val dy = (vh - bmp.height * scale) / 2f
                matrix.reset()
                matrix.setScale(scale, scale)
                matrix.postTranslate(dx, dy)
                imageView.imageMatrix = matrix
                // Guarda estado base para pan/zoom
                scaleFactor = scale
                panX = dx; panY = dy
            }
        }

        // ── ScaleGestureDetector para pinch-zoom ──
        val scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val bmp = (imageView.drawable
                        ?.let { it as? android.graphics.drawable.BitmapDrawable }
                        ?.bitmap) ?: return true

                    val prevScale = scaleFactor
                    scaleFactor *= detector.scaleFactor
                    scaleFactor = scaleFactor.coerceIn(
                        minOf(imageView.width.toFloat() / bmp.width,
                            imageView.height.toFloat() / bmp.height)
                            .coerceAtMost(minScale),
                        maxScale
                    )
                    val ratio = scaleFactor / prevScale
                    // Zoom centrado no ponto focal dos dedos
                    panX = detector.focusX - (detector.focusX - panX) * ratio
                    panY = detector.focusY - (detector.focusY - panY) * ratio

                    matrix.reset()
                    matrix.setScale(scaleFactor, scaleFactor)
                    matrix.postTranslate(panX, panY)
                    imageView.imageMatrix = matrix
                    return true
                }
            })

        // ── GestureDetector para swipe e pan ──────
        val gestureDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {

                override fun onFling(
                    e1: MotionEvent?, e2: MotionEvent,
                    velocityX: Float, velocityY: Float
                ): Boolean {
                    // Só navega entre fotos se o zoom for mínimo (sem zoom ativo)
                    val bmp = (imageView.drawable
                        ?.let { it as? android.graphics.drawable.BitmapDrawable }
                        ?.bitmap)
                    val baseScale = if (bmp != null)
                        minOf(imageView.width.toFloat() / bmp.width,
                            imageView.height.toFloat() / bmp.height)
                    else 1f
                    val isZoomed = scaleFactor > baseScale * 1.05f

                    val dx = e2.x - (e1?.x ?: e2.x)
                    val dy = e2.y - (e1?.y ?: e2.y)
                    val absX = Math.abs(dx); val absY = Math.abs(dy)

                    if (!isZoomed && absX > absY && absX > 100) {
                        // Swipe horizontal → navegar
                        if (dx < 0 && currentPos < captures.lastIndex) {
                            currentPos++; resetZoom(); loadImage()
                        } else if (dx > 0 && currentPos > 0) {
                            currentPos--; resetZoom(); loadImage()
                        }
                        return true
                    }
                    if (!isZoomed && absY > absX && dy > 150) {
                        // Swipe para baixo → fechar
                        dialog.dismiss(); return true
                    }
                    return false
                }

                // MUDANÇA: toque simples NÃO fecha mais o visualizador
                // Antes tinha: override fun onSingleTapUp → dialog.dismiss()
                // Agora: nenhuma ação no toque simples (apenas zoom/swipe fecham)
            })

        // ── Touch unificado: zoom + pan + swipe ───
        imageView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastPanX = event.x; lastPanY = event.y
                    activePointerId = event.getPointerId(0)
                    isPanning = true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // Segundo dedo: cancela pan, deixa só o pinch
                    isPanning = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress && isPanning && event.pointerCount == 1) {
                        val bmp = (imageView.drawable
                            ?.let { it as? android.graphics.drawable.BitmapDrawable }
                            ?.bitmap)
                        val baseScale = if (bmp != null)
                            minOf(imageView.width.toFloat() / bmp.width,
                                imageView.height.toFloat() / bmp.height)
                        else 1f
                        val isZoomed = scaleFactor > baseScale * 1.05f

                        if (isZoomed) {
                            // Pan livre dentro do zoom
                            val idx = event.findPointerIndex(activePointerId)
                            if (idx >= 0) {
                                val dx = event.getX(idx) - lastPanX
                                val dy = event.getY(idx) - lastPanY
                                panX += dx; panY += dy
                                // Limita pan para não expor área vazia além da imagem
                                if (bmp != null) {
                                    val imgW = bmp.width * scaleFactor
                                    val imgH = bmp.height * scaleFactor
                                    val vw   = imageView.width.toFloat()
                                    val vh   = imageView.height.toFloat()
                                    panX = panX.coerceIn(
                                        minOf(0f, vw - imgW), maxOf(0f, vw - imgW))
                                    panY = panY.coerceIn(
                                        minOf(0f, vh - imgH), maxOf(0f, vh - imgH))
                                }
                                lastPanX = event.getX(idx)
                                lastPanY = event.getY(idx)
                                matrix.reset()
                                matrix.setScale(scaleFactor, scaleFactor)
                                matrix.postTranslate(panX, panY)
                                imageView.imageMatrix = matrix
                            }
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isPanning = false
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val idx = event.actionIndex
                    val pid = event.getPointerId(idx)
                    if (pid == activePointerId) {
                        val newIdx = if (idx == 0) 1 else 0
                        lastPanX = event.getX(newIdx)
                        lastPanY = event.getY(newIdx)
                        activePointerId = event.getPointerId(newIdx)
                    }
                    isPanning = true
                }
            }

            // Encaminha para gestureDetector apenas com 1 dedo
            if (event.pointerCount == 1) gestureDetector.onTouchEvent(event)
            true
        }

        layout.addView(imageView)
        layout.addView(tvCounter)
        layout.addView(tvHint)
        layout.addView(tvClose)
        layout.addView(tvEdit)

        dialog.setView(layout)
        dialog.show()
        loadImage()
    }

    // ─────────────────────────────────────────────
    // DRAG & DROP
    // ─────────────────────────────────────────────

    private fun startDragFromGrid(view: View, pos: Int) {
        dragFromPos = pos
        val shadow = View.DragShadowBuilder(view)
        view.startDragAndDrop(null, shadow, pos, 0)
        Toast.makeText(this, "Arraste para nova posição", Toast.LENGTH_SHORT).show()
    }

    private fun setupDragDrop() {
        if (sortMode != GalleryManager.SortMode.MANUAL) return

        gridView.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DROP -> {
                    val x = event.x; val y = event.y
                    val toPos   = getGridPositionAt(x.toInt(), y.toInt())
                    val fromPos = dragFromPos

                    if (toPos >= 0 && fromPos >= 0 && fromPos != toPos &&
                        toPos < captures.size) {
                        val item = captures.removeAt(fromPos)
                        captures.add(toPos, item)
                        GalleryManager.saveOrder(folder, captures.map { it.name })
                        (gridView.adapter as? CaptureAdapter)?.notifyDataSetChanged()
                    }
                    dragFromPos = -1
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> { dragFromPos = -1; true }
                else -> true
            }
        }
    }

    private fun getGridPositionAt(x: Int, y: Int): Int {
        val cols   = gridView.numColumns
        val cellW  = gridView.width / cols
        val col    = (x / cellW).coerceIn(0, cols - 1)
        val cellH  = if (gridView.childCount > 0) gridView.getChildAt(0).height else 1
        val row    = ((y + gridView.scrollY) / cellH).coerceAtLeast(0)
        val pos    = row * cols + col
        return pos.coerceIn(0, captures.lastIndex)
    }

    // ─────────────────────────────────────────────
    // EDITOR
    // ─────────────────────────────────────────────

    private fun openEditor(file: File) {
        val intent = Intent(this, ImageEditorActivity::class.java)
        intent.putExtra("file_path", file.absolutePath)
        startActivityForResult(intent, REQUEST_EDITOR)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_EDITOR && resultCode == RESULT_OK) {
            refreshGrid()
        }
    }

    // ─────────────────────────────────────────────
    // OPÇÕES DE CAPTURA
    // ─────────────────────────────────────────────

    private fun showCaptureOptions(file: File) {
        val options = arrayOf(
            "✏️  Editar imagem",
            "🔍  Ver em tela cheia",
            "🗑️  Deletar"
        )
        AlertDialog.Builder(this)
            .setTitle(formatTimestamp(file.nameWithoutExtension))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openEditor(file)
                    1 -> showSwipeViewer(captures.indexOf(file))
                    2 -> confirmDeleteCapture(file)
                }
            }
            .show()
    }

    private fun confirmDeleteCapture(file: File) {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Deletar imagem")
            .setMessage("Essa ação não pode ser desfeita.")
            .setPositiveButton("Deletar") { _, _ ->
                val ok = GalleryManager.deleteCapture(file)
                Toast.makeText(this,
                    if (ok) "🗑️ Deletada" else "❌ Erro",
                    Toast.LENGTH_SHORT).show()
                refreshGrid()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ─────────────────────────────────────────────
    // UTILITÁRIOS
    // ─────────────────────────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun formatTimestamp(name: String): String = try {
        val sdf  = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
        val date = sdf.parse(name)!!
        SimpleDateFormat("dd/MM/yyyy  HH:mm:ss", Locale.getDefault()).format(date)
    } catch (e: Exception) { name }

    companion object {
        private const val REQUEST_EDITOR = 1001
    }

    // ─────────────────────────────────────────────
    // ADAPTER
    // ─────────────────────────────────────────────

    inner class CaptureAdapter(
        private val ctx: Context,
        private val data: List<File>
    ) : BaseAdapter() {

        override fun getCount()          = data.size
        override fun getItem(pos: Int)   = data[pos]
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
            val file     = data[pos]
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
                background = GradientDrawable().apply { setColor(Color.argb(160, 0, 0, 0)) }
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.BOTTOM }
            }

            if (sortMode == GalleryManager.SortMode.MANUAL) {
                val tvDrag = TextView(ctx).apply {
                    text     = "⠿"
                    textSize = 18f
                    setTextColor(Color.argb(200, 255, 255, 255))
                    setPadding(dp(6), dp(4), dp(6), dp(4))
                    background = GradientDrawable().apply { setColor(Color.argb(120, 0, 0, 0)) }
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                        gravity = Gravity.TOP or Gravity.END
                    }
                }
                card.addView(tvDrag)
            }

            card.addView(imageView)
            card.addView(tvDate)
            return card
        }
    }
}