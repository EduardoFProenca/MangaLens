// app/src/main/java/com/mangalens/OcrProcessor.kt
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

    // ── Padding vertical aplicado ao bitmap ANTES de passar para o ML Kit ───
    // Pequeno buffer para o ML Kit não cortar texto na borda do balão.
    // A mesclagem de blocos próximos agora é feita em pós-processamento (mergeNearbyBlocks),
    // portanto este valor pode ser conservador — só precisa garantir que o topo/base
    // do balão não seja cortado pelo crop do usuário.
    private const val CROP_PAD_TOP_BOTTOM_PX = 30

    // ── Tolerância de mesclagem de blocos vizinhos ───────────────────────────
    //
    // PROBLEMA RAIZ (visível na Imagem 1 do usuário):
    // O ML Kit divide balões de mangá em múltiplos TextBlocks — um por linha ou
    // até por palavra — porque a fonte estilizada impede o agrupamento interno.
    // Resultado: "you" e "going" e "do something" ficam em blocos separados,
    // gerando 6+ traduções fragmentadas onde deveria haver 1 única.
    //
    // SOLUÇÃO: após o OCR, mesclar blocos cujas bounding boxes estejam dentro
    // de MERGE_GAP_Y pixels verticalmente E se sobrepõem (ou quase) no eixo X.
    //
    // MERGE_GAP_Y = 40px: distância máxima entre o bottom de um bloco e o top
    // do seguinte para considerá-los parte do mesmo balão.
    // Valor empírico: ~40px cobre espaçamentos de linha comuns em fontes de mangá
    // em telas 1080p sem mesclar balões completamente separados.
    //
    // MERGE_X_OVERLAP_RATIO = 0.15f: dois blocos são considerados "no mesmo eixo X"
    // se a interseção horizontal deles for >= 15% da largura do menor bloco.
    // Evita mesclar blocos de colunas diferentes (ex.: dois personagens falando
    // lado a lado com balões separados).
    private const val MERGE_GAP_Y             = 40
    private const val MERGE_X_OVERLAP_RATIO   = 0.15f

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
                Log.d(TAG, "OCR raw: ${visionText.text.trim().take(300)}")

                // ── 1. Coleta blocos brutos do ML Kit ────────────────────────
                val rawBlocks: List<Pair<String, Rect?>> = visionText.textBlocks
                    .mapNotNull { block ->
                        val text = block.text.trim()
                        if (text.isBlank()) return@mapNotNull null
                        Pair(text, block.boundingBox)
                    }
                    .filter { (text, _) -> text.length >= 2 }

                Log.d(TAG, "Blocos brutos ML Kit: ${rawBlocks.size}")
                if (rawBlocks.isEmpty()) { onResult(emptyList()); return@addOnSuccessListener }

                // ── 2. Mescla blocos verticalmente próximos (mesma coluna) ──
                //
                // Este passo é o núcleo da correção para o problema da Imagem 1.
                // Sem ele, cada linha do balão gera uma tradução separada.
                val merged = mergeNearbyBlocks(rawBlocks)
                Log.d(TAG, "Blocos após mesclagem: ${merged.size} (era ${rawBlocks.size})")
                merged.forEachIndexed { i, (t, b) ->
                    Log.d(TAG, "  MERGED[$i] box=$b '${t.take(60)}'")
                }

                // ── 3. Filtra blocos que ocupam quase toda a largura (UI) ──
                val filtered = merged.filter { (text, box) ->
                    if (box == null) return@filter true
                    val widthFraction = box.width().toFloat() / source.width
                    text.length > 6 || widthFraction < 0.85f
                }

                // ── 4. Correção de OCR caractere a caractere ─────────────────
                val corrected = filtered.map { (rawText, box) ->
                    val fixed = CharacterCorrector.correct(rawText)
                    if (fixed != rawText) Log.d(TAG, "Corrigido: \"$rawText\" → \"$fixed\"")
                    Pair(fixed, box)
                }

                val rawMap = filtered.zip(corrected)
                    .associate { (orig, fix) -> fix.first to orig.first }

                Log.d(TAG, "Blocos para tradução: ${corrected.size}")

                translateAll(corrected, rawMap, onResult)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR falhou: ${e.message}")
                onResult(emptyList())
            }
    }

    // ─────────────────────────────────────────────
    // MESCLAGEM DE BLOCOS PRÓXIMOS
    // ─────────────────────────────────────────────

    /**
     * Mescla TextBlocks do ML Kit que pertencem ao mesmo balão de mangá.
     *
     * ALGORITMO (Union-Find simplificado com ordenação):
     *
     * 1. Ordena os blocos pelo topo (rect.top) de cima para baixo.
     * 2. Para cada bloco, tenta unir com todos os blocos ainda abertos
     *    (cujo bottom ainda não foi ultrapassado + gap).
     * 3. Dois blocos são elegíveis para mesclagem se:
     *    a) A distância vertical entre eles é <= MERGE_GAP_Y pixels
     *    b) Há sobreposição ou proximidade horizontal (mesmo eixo X)
     * 4. Blocos mesclados têm seu texto concatenado com espaço e
     *    sua bounding box expandida para cobrir ambos.
     *
     * RESULTADO: balões com 4-6 linhas viram 1 único bloco com texto completo,
     * produzindo uma tradução gramaticalmente correta.
     *
     * PROTEÇÃO CONTRA OVER-MERGE:
     * - A verificação de sobreposição X (MERGE_X_OVERLAP_RATIO) impede que
     *   dois balões lado a lado (sem sobreposição horizontal) sejam mesclados.
     * - O limite MERGE_GAP_Y impede que balões em painéis diferentes
     *   (separados por > 40px) sejam unidos.
     */
    private fun mergeNearbyBlocks(
        blocks: List<Pair<String, Rect?>>
    ): List<Pair<String, Rect?>> {

        if (blocks.size <= 1) return blocks

        // Trabalha com listas mutáveis: texto acumulado + bounding box expandida
        data class MutableBlock(
            val texts: MutableList<String>,
            var box: Rect?
        )

        // Ordena por top para processar de cima para baixo
        val sorted = blocks.sortedBy { (_, box) -> box?.top ?: 0 }

        // Grupos de blocos já formados
        val groups = mutableListOf<MutableBlock>()

        for ((text, box) in sorted) {
            var merged = false

            // Tenta encaixar no grupo existente mais próximo verticalmente
            for (group in groups) {
                val gBox = group.box

                // Se não tem bounding box em algum lado, concatena sem checar posição
                if (box == null || gBox == null) {
                    group.texts.add(text)
                    if (box != null && gBox == null) group.box = Rect(box)
                    merged = true
                    break
                }

                // ── Cheque de proximidade vertical ───────────────────────────
                // Distância entre o bottom do grupo e o top do bloco novo
                val verticalGap = box.top - gBox.bottom
                // Também aceita blocos que se sobrepõem verticalmente (gap negativo)
                if (verticalGap > MERGE_GAP_Y) continue  // muito longe → próximo grupo

                // ── Cheque de alinhamento horizontal ─────────────────────────
                // Calcula a interseção horizontal entre os dois blocos
                val xOverlapLeft  = maxOf(box.left,  gBox.left)
                val xOverlapRight = minOf(box.right, gBox.right)
                val xOverlapWidth = xOverlapRight - xOverlapLeft

                // Largura mínima entre os dois blocos
                val minWidth = minOf(box.width(), gBox.width()).toFloat()

                // Se não há sobreposição horizontal significativa, pula
                if (minWidth > 0 && xOverlapWidth.toFloat() / minWidth < MERGE_X_OVERLAP_RATIO) {
                    continue
                }

                // ── Mescla ───────────────────────────────────────────────────
                group.texts.add(text)
                // Expande a bounding box do grupo para cobrir o novo bloco
                group.box = Rect(
                    minOf(gBox.left,   box.left),
                    minOf(gBox.top,    box.top),
                    maxOf(gBox.right,  box.right),
                    maxOf(gBox.bottom, box.bottom)
                )
                merged = true
                break
            }

            // Se não encontrou grupo compatível, cria um novo
            if (!merged) {
                groups.add(MutableBlock(mutableListOf(text), box?.let { Rect(it) }))
            }
        }

        // Converte grupos de volta para List<Pair<String, Rect?>>
        return groups.map { group ->
            // Junta linhas com espaço, limpando espaços duplos
            val joinedText = group.texts.joinToString(" ")
                .replace(Regex("\\s{2,}"), " ")
                .trim()
            Pair(joinedText, group.box)
        }.filter { (text, _) -> text.isNotBlank() }
    }

    // ─────────────────────────────────────────────
    // CROP COM PADDING VERTICAL CONSERVADOR
    // ─────────────────────────────────────────────

    /**
     * Expande o rect de corte APENAS em cima e embaixo em [CROP_PAD_TOP_BOTTOM_PX].
     * As laterais permanecem exatamente como o usuário selecionou.
     *
     * NOTA: com a mesclagem de blocos (mergeNearbyBlocks), o padding aqui
     * serve apenas como buffer de segurança para não cortar texto na borda.
     * O agrupamento não depende mais deste valor.
     */
    private fun applyCrop(bitmap: Bitmap, crop: Rect?): Bitmap {
        if (crop == null) return bitmap

        val padV = CROP_PAD_TOP_BOTTOM_PX

        val l = crop.left .coerceIn(0, bitmap.width  - 1)
        val r = crop.right.coerceIn(l + 1, bitmap.width)
        val t = (crop.top    - padV).coerceIn(0, bitmap.height - 1)
        val b = (crop.bottom + padV).coerceIn(t + 1, bitmap.height)

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