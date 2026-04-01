// app/src/main/java/com/mangalens/GalleryManager.kt
package com.mangalens

import android.content.Context
import android.graphics.*
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Gerencia a galeria privada do MangaLens.
 *
 * ESTRUTURA DE PASTAS:
 *   filesDir/
 *     gallery/
 *       .nomedia                 ← impede o Media Scanner de indexar
 *       Capítulo 1/
 *         .nomedia
 *         2024-01-15_14-30-22.png
 *         2024-01-15_14-31-05.png
 *       Lutas Favoritas/
 *         .nomedia
 *         ...
 *
 * Todas as imagens ficam em filesDir (armazenamento interno privado),
 * invisíveis para galeria do celular e outros apps de fotos.
 */
object GalleryManager {

    private const val TAG        = "MangaLens_Gallery"
    private const val GALLERY_DIR = "gallery"
    private const val NOMEDIA    = ".nomedia"

    // ─────────────────────────────────────────────
    // DIRETÓRIO RAIZ
    // ─────────────────────────────────────────────

    fun galleryRoot(context: Context): File {
        val root = File(context.filesDir, GALLERY_DIR)
        if (!root.exists()) {
            root.mkdirs()
            File(root, NOMEDIA).createNewFile()   // bloqueia media scanner
        }
        return root
    }

    // ─────────────────────────────────────────────
    // PASTAS
    // ─────────────────────────────────────────────

    /** Retorna todas as pastas existentes, ordenadas por nome. */
    fun listFolders(context: Context): List<File> =
        galleryRoot(context)
            .listFiles { f -> f.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    /**
     * Cria uma nova pasta.
     * @return a pasta criada, ou null se o nome já existir / for inválido.
     */
    fun createFolder(context: Context, name: String): File? {
        val sanitized = name.trim().replace(Regex("[/\\\\:*?\"<>|]"), "_")
        if (sanitized.isBlank()) return null

        val folder = File(galleryRoot(context), sanitized)
        if (folder.exists()) return null          // nome duplicado

        folder.mkdirs()
        File(folder, NOMEDIA).createNewFile()     // bloqueia media scanner dentro da pasta
        Log.d(TAG, "Pasta criada: ${folder.path}")
        return folder
    }

    /**
     * Renomeia uma pasta.
     * @return true se teve sucesso.
     */
    fun renameFolder(folder: File, newName: String): Boolean {
        val sanitized = newName.trim().replace(Regex("[/\\\\:*?\"<>|]"), "_")
        if (sanitized.isBlank()) return false

        val dest = File(folder.parentFile!!, sanitized)
        if (dest.exists()) return false

        return folder.renameTo(dest).also { ok ->
            if (ok) Log.d(TAG, "Pasta renomeada: ${folder.name} → $sanitized")
        }
    }

    /**
     * Deleta uma pasta e todo o seu conteúdo.
     * @return true se teve sucesso.
     */
    fun deleteFolder(folder: File): Boolean {
        return folder.deleteRecursively().also { ok ->
            Log.d(TAG, "Pasta deletada (ok=$ok): ${folder.name}")
        }
    }

    // ─────────────────────────────────────────────
    // IMAGENS
    // ─────────────────────────────────────────────

    /** Retorna todas as capturas de uma pasta, ordenadas da mais nova para a mais antiga. */
    fun listCaptures(folder: File): List<File> =
        folder.listFiles { f -> f.isFile && f.extension == "png" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /**
     * Salva um bitmap como PNG dentro de [folder].
     * O nome do arquivo é gerado automaticamente com timestamp.
     * @return o arquivo salvo, ou null em caso de erro.
     */
    fun saveCapture(folder: File, bitmap: Bitmap): File? {
        return try {
            val ts   = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
            val file = File(folder, "$ts.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
            Log.d(TAG, "Captura salva: ${file.path} (${bitmap.width}x${bitmap.height})")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao salvar captura: ${e.message}")
            null
        }
    }

    /**
     * Deleta uma imagem.
     * @return true se teve sucesso.
     */
    fun deleteCapture(file: File): Boolean =
        file.delete().also { ok ->
            Log.d(TAG, "Captura deletada (ok=$ok): ${file.name}")
        }

    // ─────────────────────────────────────────────
    // MESCLA: imagem de fundo + overlay traduzido
    // ─────────────────────────────────────────────

    /**
     * Combina o [background] (frame da tela) com o [overlay] já renderizado
     * em um único bitmap para salvar.
     *
     * O overlay é desenhado em cima do background usando as mesmas dimensões.
     * Se os tamanhos diferem, o overlay é escalado para cobrir o background.
     */
    fun mergeBitmaps(background: Bitmap, overlay: Bitmap): Bitmap {
        val result = background.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        if (overlay.width == background.width && overlay.height == background.height) {
            canvas.drawBitmap(overlay, 0f, 0f, null)
        } else {
            // Escala o overlay para cobrir o background exatamente
            val src  = RectF(0f, 0f, overlay.width.toFloat(),     overlay.height.toFloat())
            val dst  = RectF(0f, 0f, background.width.toFloat(),  background.height.toFloat())
            canvas.drawBitmap(overlay, null, dst, null)
        }

        return result
    }

    /**
     * Renderiza o overlay de tradução (lista de bolhas) sobre o [background]
     * sem precisar de uma View visível na tela.
     *
     * Usado quando o usuário salva diretamente do FloatingService,
     * fora do contexto de uma Activity.
     */
    fun renderTranslationOverBitmap(
        background: Bitmap,
        results: List<TextResult>,
        screenWidth: Int,
        screenHeight: Int,
        cropRect: android.graphics.Rect?,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): Bitmap {
        val result = background.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        val density = 2f   // densidade fixa para renderização off-screen (MDPI×2)

        val offsetX = cropRect?.left?.toFloat() ?: 0f
        val offsetY = cropRect?.top?.toFloat()  ?: 0f

        val scaleX = (cropRect?.width()?.toFloat() ?: screenWidth.toFloat()) /
                (if (bitmapWidth  > 0) bitmapWidth  else screenWidth).toFloat()
        val scaleY = (cropRect?.height()?.toFloat() ?: screenHeight.toFloat()) /
                (if (bitmapHeight > 0) bitmapHeight else screenHeight).toFloat()

        val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 255, 255, 248); style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 80, 80, 200); style = Paint.Style.STROKE; strokeWidth = 2f
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(255, 15, 15, 70); textSize = 13f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val padH = 8f * density
        val padV = 5f * density
        val cornerR = 7f * density

        results.forEach { result2 ->
            val box = result2.boundingBox ?: return@forEach
            val text = result2.translatedText.ifBlank { result2.originalText }

            val sL = box.left   * scaleX + offsetX
            val sT = box.top    * scaleY + offsetY
            val sR = box.right  * scaleX + offsetX
            val sB = box.bottom * scaleY + offsetY

            val boxW   = (sR - sL).coerceAtLeast(60f * density)
            val lines  = wrapText(text, textPaint, boxW - padH * 2)
            val lineH  = textPaint.fontSpacing
            val boxH   = lines.size * lineH + padV * 2

            val bTop  = (sT - boxH - 2f * density).coerceAtLeast(0f)
            val bLeft = sL.coerceIn(0f, (result.width - boxW).coerceAtLeast(0f))

            val boxRect = RectF(bLeft, bTop, bLeft + boxW, bTop + boxH)
            canvas.drawRoundRect(boxRect, cornerR, cornerR, bubblePaint)
            canvas.drawRoundRect(boxRect, cornerR, cornerR, strokePaint)

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
    // INFORMAÇÕES DE PASTA
    // ─────────────────────────────────────────────

    /** Retorna o número de capturas em uma pasta (excluindo .nomedia). */
    fun captureCount(folder: File): Int =
        folder.listFiles { f -> f.isFile && f.extension == "png" }?.size ?: 0

    /** Retorna o tamanho total de uma pasta em bytes. */
    fun folderSizeBytes(folder: File): Long =
        folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun formatSize(bytes: Long): String = when {
        bytes < 1024             -> "${bytes} B"
        bytes < 1024 * 1024      -> "${"%.1f".format(bytes / 1024.0)} KB"
        else                     -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
    }
}