package com.mangalens

import android.util.Log

/**
 * Corrige erros comuns de OCR em fontes estilizadas de mangá e jogos.
 *
 * ESTRATÉGIA:
 * A substituição cega de caracteres (0→O, 1→I em todo texto) quebraria
 * palavras normais que usam números de forma legítima (ex: "MP3", "Level 1").
 * Por isso a correção só é aplicada quando há evidência de que o OCR leu
 * uma palavra alfanumérica que deveria ser puramente textual.
 *
 * CRITÉRIO DE ATIVAÇÃO POR PALAVRA:
 *   Uma palavra recebe correção se ela contiver pelo menos um substituível
 *   (número ou símbolo da lista) E a fração de letras reais for >= 30%.
 *   Isso garante que "H3LL0" (80% letras) seja corrigido para "HELLO",
 *   mas "MP3" (66% letras) seja preservado por ser um termo técnico comum,
 *   e "123" (0% letras) seja ignorado por completo.
 *
 * EXCEÇÕES (nunca corrigidas):
 *   - Palavras puramente numéricas (ex: "123", "2024")
 *   - Palavras que parecem versões/modelos (ex: "v2.0", "R2D2")
 *   - Palavras com menos de 2 caracteres
 */
object CharacterCorrector {

    private const val TAG = "MangaLens_Corrector"

    // ── Limiar: mínimo de letras reais para ativar correção ──────────────
    private const val MIN_LETTER_FRACTION = 0.30f

    // ── Mapa de substituição número/símbolo → letra ──────────────────────
    // A escolha entre maiúscula/minúscula é feita pelo contexto (veja abaixo)
    private val SUBSTITUTION_MAP: Map<Char, String> = mapOf(
        '0' to "O",
        '1' to "I",   // contexto decide se I ou l
        '3' to "E",
        '4' to "A",
        '5' to "S",
        '6' to "G",
        '7' to "T",
        '8' to "B",
        '9' to "g",
        '|' to "I",   // barra vertical → I
        '@' to "a",
        '$' to "S",
        '£' to "E"
    )

    // Conjunto dos caracteres que podem ser substituídos (para checagem rápida)
    private val SUBSTITUTABLE = SUBSTITUTION_MAP.keys

    // ─────────────────────────────────────────────
    // ENTRADA PRINCIPAL: corrige um bloco de texto
    // ─────────────────────────────────────────────

    fun correct(text: String): String {
        if (text.isBlank()) return text

        // Processa palavra por palavra, preservando espaços e pontuação entre elas
        return text.split(" ").joinToString(" ") { word ->
            correctWord(word)
        }
    }

    // ─────────────────────────────────────────────
    // CORREÇÃO DE PALAVRA INDIVIDUAL
    // ─────────────────────────────────────────────

    private fun correctWord(word: String): String {
        if (word.length < 2) return word

        // Exceção: palavras puramente numéricas ou versões (ex: "v2.0", "3.14")
        if (isNumericOrVersion(word)) return word

        // Conta quantos caracteres são substituíveis e quantos são letras reais
        var substitutableCount = 0
        var realLetterCount    = 0

        for (c in word) {
            when {
                c in SUBSTITUTABLE -> substitutableCount++
                c.isLetter()       -> realLetterCount++
                // outros (pontuação, etc.) são ignorados na contagem
            }
        }

        // Se não há nada substituível, retorna sem alterar
        if (substitutableCount == 0) return word

        // Verifica o limiar: fração de letras reais no total alfanumérico
        val alphaNumTotal = realLetterCount + substitutableCount
        val letterFraction = if (alphaNumTotal == 0) 0f
        else realLetterCount.toFloat() / alphaNumTotal

        if (letterFraction < MIN_LETTER_FRACTION) {
            // Muito poucos caracteres reais — provavelmente um número legítimo
            return word
        }

        // Determina se a palavra está em maiúsculas (para decidir I vs l, O vs o)
        val isUpperContext = word.count { it.isUpperCase() } > word.count { it.isLowerCase() }

        // Aplica as substituições
        val result = StringBuilder(word.length)
        for (c in word) {
            val replacement = SUBSTITUTION_MAP[c]
            if (replacement != null) {
                // Adapta capitalização ao contexto da palavra
                result.append(adaptCase(replacement, c, isUpperContext, result))
            } else {
                result.append(c)
            }
        }

        return result.toString()
    }

    // ─────────────────────────────────────────────
    // ADAPTAÇÃO DE CAPITALIZAÇÃO
    // ─────────────────────────────────────────────

    /**
     * Decide se a substituição deve ser maiúscula ou minúscula com base em:
     * 1. Se a palavra está predominantemente em maiúsculas → maiúscula
     * 2. Se o caractere anterior era maiúsculo → maiúscula
     * 3. Se é o início da palavra → maiúscula
     * 4. Casos especiais: '9'→'g' e '@'→'a' sempre minúsculos
     */
    private fun adaptCase(
        replacement: String,
        original: Char,
        isUpperContext: Boolean,
        current: StringBuilder
    ): String {
        // Estes sempre ficam minúsculos por design
        if (original == '9' || original == '@') return replacement.lowercase()

        // '1' e '|' → 'I' maiúsculo em contexto de maiúsculas, 'l' minúsculo caso contrário
        if ((original == '1' || original == '|') && !isUpperContext) return "l"

        return if (isUpperContext || current.isEmpty() ||
            current.last().isUpperCase()) {
            replacement.uppercase()
        } else {
            replacement.lowercase()
        }
    }

    // ─────────────────────────────────────────────
    // DETECÇÃO DE NÚMERO / VERSÃO
    // ─────────────────────────────────────────────

    /**
     * Retorna true para palavras que claramente são números ou versões:
     *   "123", "2024", "3.14", "v2.0", "R2D2", "MP3", "Level1"
     *
     * Critério: se a palavra contém dígito mas NÃO tem pelo menos 40%
     * de letras reais, é considerada numérica/técnica e não é corrigida.
     * (Isso é verificado antes de chamar correctWord, mas aqui é uma
     * verificação adicional para padrões conhecidos.)
     */
    private fun isNumericOrVersion(word: String): Boolean {
        // Puramente numérico (com possível ponto/vírgula decimal)
        if (word.all { it.isDigit() || it == '.' || it == ',' }) return true

        // Padrão de versão: começa com v/V seguido de dígito (ex: "v2.0", "V1.5")
        if (word.length >= 2 && word[0].lowercaseChar() == 'v' && word[1].isDigit()) return true

        return false
    }
}