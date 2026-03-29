package com.mangalens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

data class TextResult(
    val originalText: String,
    val translatedText: String,
    val boundingBox: Rect?,
    val detectedLanguage: String = "und",
    val rawText: String = originalText
)

object OcrProcessor {

    private const val TAG = "MangaLens_OCR"

    // ── Padding APENAS no eixo Y (cima e baixo) ──────────────────────────
    // Balões de mangá têm linhas de texto próximas à borda superior/inferior.
    // Aumentar só o vertical evita incluir texto de balões vizinhos nas laterais.
    private const val CROP_PAD_TOP_BOTTOM_PX = 40  // pixels extras em cima e embaixo
    // Horizontal: zero — não mexemos nas laterais conforme solicitado.

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val translators = mutableMapOf<String, Translator>()
    private val modelReady  = mutableMapOf<String, Boolean>()

    // ─────────────────────────────────────────────
    // DOWNLOAD
    // ─────────────────────────────────────────────

    fun downloadModelsIfNeeded(onReady: () -> Unit) {
        val pairs = listOf(
            TranslateLanguage.ENGLISH  to TranslateLanguage.PORTUGUESE,
            TranslateLanguage.JAPANESE to TranslateLanguage.PORTUGUESE
        )
        var remaining = pairs.size
        pairs.forEach { (src: String, tgt: String) ->
            val key = "$src->$tgt"
            getOrCreateTranslator(src, tgt).downloadModelIfNeeded()
                .addOnSuccessListener {
                    modelReady[key] = true
                    Log.d(TAG, "Modelo pronto: $key")
                    if (--remaining == 0) onReady()
                }
                .addOnFailureListener { e: Exception ->
                    Log.e(TAG, "Falha $key: ${e.message}")
                    if (--remaining == 0) onReady()
                }
        }
    }

    fun downloadModelIfNeeded(onReady: () -> Unit) = downloadModelsIfNeeded(onReady)

    // ─────────────────────────────────────────────
    // RE-TRADUÇÃO APÓS EDIÇÃO
    // ─────────────────────────────────────────────

    fun retranslate(text: String, onResult: (String) -> Unit) {
        val isJa = containsJapanese(text)
        val src  = if (isJa) TranslateLanguage.JAPANESE else TranslateLanguage.ENGLISH
        val tgt  = TranslateLanguage.PORTUGUESE
        val key  = "$src->$tgt"
        if (modelReady[key] != true) { onResult(text); return }
        getOrCreateTranslator(src, tgt).translate(text)
            .addOnSuccessListener { onResult(it) }
            .addOnFailureListener { onResult(text) }
    }

    // ─────────────────────────────────────────────
    // PROCESSO PRINCIPAL
    // ─────────────────────────────────────────────

    fun process(
        context: Context,
        bitmap: Bitmap,
        cropRect: Rect? = null,
        onResult: (List<TextResult>) -> Unit
    ) {
        val source = applyCrop(bitmap, cropRect)
        Log.d(TAG, "OCR em ${source.width}x${source.height}")

        recognizer.process(InputImage.fromBitmap(source, 0))
            .addOnSuccessListener { visionText ->
                Log.d(TAG, "OCR raw: ${visionText.text.trim().take(200)}")

                val blocks: List<Pair<String, Rect?>> = visionText.textBlocks
                    .mapNotNull { block ->
                        val text = block.text.trim()
                        if (text.isBlank()) return@mapNotNull null
                        Pair(text, block.boundingBox)
                    }
                    .filter { (text, _) -> text.length >= 2 }

                Log.d(TAG, "Blocos após coleta: ${blocks.size}")
                if (blocks.isEmpty()) { onResult(emptyList()); return@addOnSuccessListener }

                val filtered = blocks.filter { (text, box) ->
                    if (box == null) return@filter true
                    val widthFraction = box.width().toFloat() / source.width
                    text.length > 6 || widthFraction < 0.85f
                }

                val corrected = filtered.map { (rawText, box) ->
                    val fixed = CharacterCorrector.correct(rawText)
                    if (fixed != rawText) Log.d(TAG, "Corrigido: \"$rawText\" → \"$fixed\"")
                    Pair(fixed, box)
                }

                val rawMap = filtered.zip(corrected)
                    .associate { (orig, fix) -> fix.first to orig.first }

                Log.d(TAG, "Blocos para tradução: ${corrected.size}")
                corrected.forEachIndexed { i, (t, b) ->
                    Log.d(TAG, "  [$i] box=${b} texto='${t.take(50)}'")
                }

                translateAll(corrected, rawMap, onResult)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR falhou: ${e.message}")
                onResult(emptyList())
            }
    }

    // ─────────────────────────────────────────────
    // CROP COM PADDING VERTICAL
    // ─────────────────────────────────────────────

    /**
     * Expande o rect de corte APENAS em cima e embaixo em [CROP_PAD_TOP_BOTTOM_PX].
     * As laterais (left/right) permanecem exatamente como o usuário selecionou,
     * evitando capturar texto de balões adjacentes horizontalmente.
     */
    private fun applyCrop(bitmap: Bitmap, crop: Rect?): Bitmap {
        if (crop == null) return bitmap

        val padV = CROP_PAD_TOP_BOTTOM_PX

        val l = crop.left .coerceIn(0, bitmap.width  - 1)          // lateral: sem mudança
        val r = crop.right.coerceIn(l + 1, bitmap.width)            // lateral: sem mudança
        val t = (crop.top    - padV).coerceIn(0, bitmap.height - 1) // expande para cima
        val b = (crop.bottom + padV).coerceIn(t + 1, bitmap.height) // expande para baixo

        val w = (r - l).coerceAtLeast(1)
        val h = (b - t).coerceAtLeast(1)

        Log.d(TAG, "Crop $crop → +${padV}px vertical: l=$l t=$t w=$w h=$h")
        return Bitmap.createBitmap(bitmap, l, t, w, h)
    }

    // ─────────────────────────────────────────────
    // TRADUÇÃO
    // ─────────────────────────────────────────────

    private fun translateAll(
        blocks: List<Pair<String, Rect?>>,
        rawMap: Map<String, String>,
        onResult: (List<TextResult>) -> Unit
    ) {
        if (blocks.isEmpty()) { onResult(emptyList()); return }

        val out     = arrayOfNulls<TextResult>(blocks.size)
        var pending = blocks.size

        blocks.forEachIndexed { i, (text, box) ->
            val isJa = containsJapanese(text)
            val lang = if (isJa) "ja" else "en"
            val src  = if (isJa) TranslateLanguage.JAPANESE else TranslateLanguage.ENGLISH
            val tgt  = TranslateLanguage.PORTUGUESE
            val key  = "$src->$tgt"
            val raw  = rawMap[text] ?: text

            if (modelReady[key] != true) {
                synchronized(out) {
                    out[i] = TextResult(text, text, box, lang, raw)
                    if (--pending == 0) onResult(out.filterNotNull())
                }
                return@forEachIndexed
            }

            getOrCreateTranslator(src, tgt).translate(text)
                .addOnSuccessListener { translated ->
                    synchronized(out) {
                        out[i] = TextResult(text, translated, box, lang, raw)
                        if (--pending == 0) onResult(out.filterNotNull())
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Falha tradução $i: ${e.message}")
                    synchronized(out) {
                        out[i] = TextResult(text, text, box, lang, raw)
                        if (--pending == 0) onResult(out.filterNotNull())
                    }
                }
        }
    }

    // ─────────────────────────────────────────────
    // UTILITÁRIOS
    // ─────────────────────────────────────────────

    private fun containsJapanese(text: String): Boolean {
        for (c in text) {
            val b = Character.UnicodeBlock.of(c)
            if (b == Character.UnicodeBlock.HIRAGANA ||
                b == Character.UnicodeBlock.KATAKANA  ||
                b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B)
                return true
        }
        return false
    }

    private fun getOrCreateTranslator(src: String, tgt: String): Translator =
        translators.getOrPut("$src->$tgt") {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(src)
                    .setTargetLanguage(tgt)
                    .build()
            )
        }
}