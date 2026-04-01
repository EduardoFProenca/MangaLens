// app/src/main/java/com/mangalens/GalleryManager.kt
package com.mangalens

import android.content.Context
import android.graphics.*
import android.util.Log
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object GalleryManager {

    private const val TAG         = "MangaLens_Gallery"
    private const val GALLERY_DIR = "gallery"
    private const val NOMEDIA     = ".nomedia"
    private const val ORDER_FILE  = ".order.json"   // arquivo de ordem manual por pasta

    // ─────────────────────────────────────────────
    // DIRETÓRIO RAIZ
    // ─────────────────────────────────────────────

    fun galleryRoot(context: Context): File {
        val root = File(context.filesDir, GALLERY_DIR)
        if (!root.exists()) {
            root.mkdirs()
            File(root, NOMEDIA).createNewFile()
        }
        return root
    }

    // ─────────────────────────────────────────────
    // PASTAS
    // ─────────────────────────────────────────────

    fun listFolders(context: Context): List<File> =
        galleryRoot(context)
            .listFiles { f -> f.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    fun createFolder(context: Context, name: String): File? {
        val sanitized = name.trim().replace(Regex("[/\\\\:*?\"<>|]"), "_")
        if (sanitized.isBlank()) return null
        val folder = File(galleryRoot(context), sanitized)
        if (folder.exists()) return null
        folder.mkdirs()
        File(folder, NOMEDIA).createNewFile()
        Log.d(TAG, "Pasta criada: ${folder.path}")
        return folder
    }

    fun renameFolder(folder: File, newName: String): Boolean {
        val sanitized = newName.trim().replace(Regex("[/\\\\:*?\"<>|]"), "_")
        if (sanitized.isBlank()) return false
        val dest = File(folder.parentFile!!, sanitized)
        if (dest.exists()) return false
        return folder.renameTo(dest).also { ok ->
            if (ok) Log.d(TAG, "Pasta renomeada: ${folder.name} → $sanitized")
        }
    }

    fun deleteFolder(folder: File): Boolean =
        folder.deleteRecursively().also { ok ->
            Log.d(TAG, "Pasta deletada (ok=$ok): ${folder.name}")
        }

    // ─────────────────────────────────────────────
    // IMAGENS — ORDENAÇÃO
    // ─────────────────────────────────────────────

    enum class SortMode { BY_DATE, MANUAL }

    /**
     * Lista capturas de uma pasta respeitando o modo de ordenação.
     *
     * BY_DATE  → mais nova primeiro (lastModified desc)
     * MANUAL   → segue o arquivo .order.json; arquivos não listados vão ao final
     */
    fun listCaptures(folder: File, sort: SortMode = SortMode.BY_DATE): List<File> {
        val all = folder.listFiles { f -> f.isFile && f.extension == "png" }
            ?.toList() ?: return emptyList()

        return when (sort) {
            SortMode.BY_DATE -> all.sortedByDescending { it.lastModified() }
            SortMode.MANUAL  -> {
                val order = loadOrder(folder)
                if (order.isEmpty()) {
                    all.sortedByDescending { it.lastModified() }
                } else {
                    val byName = all.associateBy { it.name }
                    val sorted = order.mapNotNull { byName[it] }
                    val rest   = all.filter { it.name !in order }
                        .sortedByDescending { it.lastModified() }
                    sorted + rest
                }
            }
        }
    }

    // ─────────────────────────────────────────────
    // ORDEM MANUAL — PERSISTÊNCIA
    // ─────────────────────────────────────────────

    /** Lê a ordem salva (lista de nomes de arquivo). */
    fun loadOrder(folder: File): List<String> {
        val f = File(folder, ORDER_FILE)
        if (!f.exists()) return emptyList()
        return try {
            val json = JSONArray(f.readText())
            (0 until json.length()).map { json.getString(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao ler ordem: ${e.message}")
            emptyList()
        }
    }

    /** Persiste a ordem manual (lista de nomes de arquivo). */
    fun saveOrder(folder: File, orderedNames: List<String>) {
        try {
            val json = JSONArray(orderedNames)
            File(folder, ORDER_FILE).writeText(json.toString())
            Log.d(TAG, "Ordem salva: ${orderedNames.size} itens")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao salvar ordem: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────
    // IMAGENS — CRUD
    // ─────────────────────────────────────────────

    fun saveCapture(folder: File, bitmap: Bitmap): File? {
        return try {
            val ts   = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
            val file = File(folder, "$ts.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
            Log.d(TAG, "Captura salva: ${file.path}")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao salvar: ${e.message}"); null
        }
    }

    fun deleteCapture(file: File): Boolean =
        file.delete().also { Log.d(TAG, "Deletada (ok=$it): ${file.name}") }

    // ─────────────────────────────────────────────
    // MESCLA / RENDER
    // ─────────────────────────────────────────────

    fun mergeBitmaps(background: Bitmap, overlay: Bitmap): Bitmap {
        val result = background.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        if (overlay.width == background.width && overlay.height == background.height) {
            canvas.drawBitmap(overlay, 0f, 0f, null)
        } else {
            val dst = RectF(0f, 0f, background.width.toFloat(), background.height.toFloat())
            canvas.drawBitmap(overlay, null, dst, null)
        }
        return result
    }

    fun renderTranslationOverBitmap(
        background: Bitmap,
        results: List<TextResult>,
        screenWidth: Int,
        screenHeight: Int,
        cropRect: android.graphics.Rect?,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): Bitmap {
        val result  = background.copy(Bitmap.Config.ARGB_8888, true)
        val canvas  = Canvas(result)
        val density = 2f

        val offsetX = cropRect?.left?.toFloat() ?: 0f
        val offsetY = cropRect?.top?.toFloat()  ?: 0f
        val scaleX  = (cropRect?.width()?.toFloat()  ?: screenWidth.toFloat())  /
                (if (bitmapWidth  > 0) bitmapWidth  else screenWidth).toFloat()
        val scaleY  = (cropRect?.height()?.toFloat() ?: screenHeight.toFloat()) /
                (if (bitmapHeight > 0) bitmapHeight else screenHeight).toFloat()

        val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 255, 255, 248); style = Paint.Style.FILL }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 80, 80, 200); style = Paint.Style.STROKE; strokeWidth = 2f }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color    = Color.argb(255, 15, 15, 70); textSize = 13f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }

        val padH = 8f * density; val padV = 5f * density; val cornerR = 7f * density

        results.forEach { r ->
            val box  = r.boundingBox ?: return@forEach
            val text = r.translatedText.ifBlank { r.originalText }
            val sL   = box.left   * scaleX + offsetX
            val sT   = box.top    * scaleY + offsetY
            val sR   = box.right  * scaleX + offsetX
            val boxW = (sR - sL).coerceAtLeast(60f * density)
            val lines = wrapText(text, textPaint, boxW - padH * 2)
            val lineH = textPaint.fontSpacing
            val boxH  = lines.size * lineH + padV * 2
            val bTop  = (sT - boxH - 2f * density).coerceAtLeast(0f)
            val bLeft = sL.coerceIn(0f, (result.width - boxW).coerceAtLeast(0f))
            val rect  = RectF(bLeft, bTop, bLeft + boxW, bTop + boxH)
            canvas.drawRoundRect(rect, cornerR, cornerR, bubblePaint)
            canvas.drawRoundRect(rect, cornerR, cornerR, strokePaint)
            lines.forEachIndexed { i, line ->
                canvas.drawText(line, bLeft + padH, bTop + padV + lineH * i + textPaint.textSize, textPaint)
            }
        }
        return result
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>(); var current = ""
        text.split(" ").forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) current = candidate
            else { if (current.isNotEmpty()) lines.add(current); current = word }
        }
        if (current.isNotEmpty()) lines.add(current)
        return lines.ifEmpty { listOf(text) }
    }

    // ─────────────────────────────────────────────
    // INFORMAÇÕES
    // ─────────────────────────────────────────────

    fun captureCount(folder: File): Int =
        folder.listFiles { f -> f.isFile && f.extension == "png" }?.size ?: 0

    fun folderSizeBytes(folder: File): Long =
        folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun formatSize(bytes: Long): String = when {
        bytes < 1024        -> "$bytes B"
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
        else                -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
    }
}