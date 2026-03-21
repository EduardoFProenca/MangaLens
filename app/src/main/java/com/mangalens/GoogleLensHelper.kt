package com.mangalens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/*
 * Abre o Google Lens com o conteúdo atual da tela.
 *
 * LIMITAÇÃO DO ANDROID 10+:
 * Disparar o "Screen Search" (segurar Home) diretamente via Intent
 * foi bloqueado para apps de terceiros por razões de privacidade.
 * Não existe Intent pública para acionar o assistente em tela cheia.
 *
 * SOLUÇÃO IMPLEMENTADA:
 * 1. Captura o bitmap da tela atual
 * 2. Salva em arquivo temporário acessível via FileProvider
 * 3. Abre o Google Lens com a imagem via Intent ACTION_SEND
 *    → O Lens abre diretamente no modo "Traduzir" se o texto for detectado
 *
 * FALLBACKS em cascata:
 *  A. Google Lens app direto (packageName específico)
 *  B. Google app (Quick Search Box) via ACTION_SEND com image
 *  C. Seletor genérico do sistema (qualquer app que aceite image/png)
*/
object GoogleLensHelper {

private const val TAG = "MangaLens_Lens"

// Package names conhecidos do Google Lens / Google app
private const val PKG_LENS        = "com.google.ar.lens"
private const val PKG_GOOGLE_APP  = "com.google.android.googlequicksearchbox"
private const val PKG_GOOGLE_GO   = "com.google.android.apps.searchlite"

// Caminho do arquivo temporário (relativo ao cache dir)
private const val TEMP_FILENAME = "lens_capture.png"

/**
 * Ponto de entrada principal.
 *
 * @param context  Context do serviço
 * @param bitmap   Bitmap da tela já capturado pelo FloatingService
*/
fun openWithScreenshot(context: Context, bitmap: Bitmap) {
val imageUri = saveBitmapToCache(context, bitmap)
if (imageUri == null) {
Toast.makeText(context, "❌ Não foi possível preparar a imagem", Toast.LENGTH_SHORT).show()
return
}

// Tenta em cascata: Lens → Google App → Seletor
when {
tryOpenGoogleLens(context, imageUri)    -> return
tryOpenGoogleApp(context, imageUri)     -> return
else                                    -> openChooser(context, imageUri)
}
}

// ─────────────────────────────────────────────
// SALVAR IMAGEM NO CACHE
// ─────────────────────────────────────────────

private fun saveBitmapToCache(context: Context, bitmap: Bitmap): Uri? {
return try {
val file = File(context.cacheDir, TEMP_FILENAME)
FileOutputStream(file).use { out ->
bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
}
// FileProvider expõe o arquivo de forma segura para outros apps
FileProvider.getUriForFile(
context,
"${context.packageName}.fileprovider",
file
)
} catch (e: Exception) {
Log.e(TAG, "Erro ao salvar imagem: ${e.message}")
null
}
}

// ─────────────────────────────────────────────
// TENTATIVA A: Google Lens app
// ─────────────────────────────────────────────

private fun tryOpenGoogleLens(context: Context, imageUri: Uri): Boolean {
// Intent conhecida do Google Lens para abrir com uma imagem
val intent = Intent(Intent.ACTION_SEND).apply {
type    = "image/png"
`package` = PKG_LENS
putExtra(Intent.EXTRA_STREAM, imageUri)
addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
return tryStartActivity(context, intent, "Google Lens")
}

// ─────────────────────────────────────────────
// TENTATIVA B: Google app (Quick Search Box)
// Abre o Lens via "Pesquisa com câmera" dentro do Google app
// ─────────────────────────────────────────────

private fun tryOpenGoogleApp(context: Context, imageUri: Uri): Boolean {
// O Google app aceita ACTION_SEND com image/* e roteia para o Lens
val intent = Intent(Intent.ACTION_SEND).apply {
type    = "image/*"
`package` = PKG_GOOGLE_APP
putExtra(Intent.EXTRA_STREAM, imageUri)
addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
return tryStartActivity(context, intent, "Google App")
}

// ─────────────────────────────────────────────
// TENTATIVA C: Seletor genérico
// ─────────────────────────────────────────────

private fun openChooser(context: Context, imageUri: Uri) {
val baseIntent = Intent(Intent.ACTION_SEND).apply {
type = "image/png"
putExtra(Intent.EXTRA_STREAM, imageUri)
addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
val chooser = Intent.createChooser(baseIntent, "Abrir com Google Lens").apply {
addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

// Garante permissão de leitura para todos os apps no seletor
context.grantUriPermission("*", imageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

try {
context.startActivity(chooser)
Log.d(TAG, "Seletor aberto")
} catch (e: Exception) {
Log.e(TAG, "Falha no seletor: ${e.message}")
Toast.makeText(
context,
"Google Lens não encontrado. Instale o app Google.",
Toast.LENGTH_LONG
).show()
}
}

// ─────────────────────────────────────────────
// UTILITÁRIO
// ─────────────────────────────────────────────

private fun tryStartActivity(context: Context, intent: Intent, label: String): Boolean {
return try {
// Verifica se existe algum app que resolve esta Intent
val resolved = context.packageManager.resolveActivity(intent, 0)
if (resolved == null) {
Log.d(TAG, "$label não disponível")
false
} else {
context.startActivity(intent)
Log.d(TAG, "$label aberto com sucesso")
true
}
} catch (e: Exception) {
Log.e(TAG, "Falha ao abrir $label: ${e.message}")
false
}
}
}