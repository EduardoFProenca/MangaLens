package com.mangalens

import android.app.*
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import android.view.*
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.*

class FloatingService : LifecycleService() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingRoot: View
    private lateinit var floatingBtn: TextView
    private lateinit var badgeLock: TextView
    private lateinit var badgeMode: TextView   // badge de modo no canto inferior

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth   = 0
    private var screenHeight  = 0
    private var screenDensity = 0

    enum class CaptureMode { SINGLE, AREA, CONTINUOUS }
    private var captureMode = CaptureMode.SINGLE

    // Estado da área
    private var lockedRect: Rect? = null
    private var areaLocked = false

    // ── Toggle do mini-game ───────────────────────
    // true  = mini-game (embaralha palavras)
    // false = tradução direta sobreposta
    private var gameModeEnabled = true
    // Última área usada (para repassar ao TranslationOverlay)
    private var lastCropRect: Rect? = null

    private val continuousCapture = ContinuousCapture(intervalMs = 1_500L, changeThreshold = 0.04f)

    private val handler     = Handler(Looper.getMainLooper())
    private var isLongPress = false
    private val LONG_PRESS  = 500L

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    @Volatile private var frameAvailable = false

    companion object {
        const val ACTION_START      = "START"
        const val ACTION_STOP       = "STOP"
        const val EXTRA_RESULT_CODE = "RESULT_CODE"
        const val EXTRA_RESULT_DATA = "RESULT_DATA"
        private const val CHANNEL_ID      = "MangaLensChannel"
        private const val NOTIFICATION_ID = 1
        private const val TAG             = "MangaLens"
    }

    // ─────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                startForeground(NOTIFICATION_ID, buildNotification())
                setupMediaProjection(code, data)
                showFloatingButton()
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        continuousCapture.stop()
        AreaSelectorOverlay.dismiss(windowManager)
        TranslationOverlay.dismiss(windowManager)
        tearDownProjection()
        if (::floatingRoot.isInitialized) runCatching { windowManager.removeView(floatingRoot) }
    }

    // ─────────────────────────────────────────────
    // BOTÃO FLUTUANTE
    // ─────────────────────────────────────────────

    private fun showFloatingButton() {
        floatingRoot = LayoutInflater.from(this).inflate(R.layout.layout_floating_button, null)
        floatingBtn  = floatingRoot.findViewById(R.id.floatingBtn)
        badgeLock    = floatingRoot.findViewById(R.id.badgeLock)
        badgeMode    = floatingRoot.findViewById(R.id.badgeMode)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 100; y = 300 }

        setupTouchListener(params)
        windowManager.addView(floatingRoot, params)
        updateButtonAppearance()
    }

    /**
     * Ícone e badges refletem o estado completo:
     *  modo captura → ícone principal
     *  mini-game    → badge "🎮" ou "📖" no canto inferior esquerdo
     *  trava área   → badge "🔒" no canto superior direito
     */
    private fun updateButtonAppearance() {
        val (icon, colorHex) = when (captureMode) {
            CaptureMode.SINGLE     -> "📷" to "#CC6200EE"
            CaptureMode.AREA       -> "▲"  to "#CCE65100"
            CaptureMode.CONTINUOUS -> "🔄" to "#CC00796B"
        }
        floatingBtn.text = icon
        val bg = floatingBtn.background
        if (bg is GradientDrawable) bg.setColor(Color.parseColor(colorHex))
        else floatingBtn.setBackgroundColor(Color.parseColor(colorHex))

        badgeLock.visibility = if (areaLocked) View.VISIBLE else View.GONE

        // Badge de modo de leitura
        badgeMode.text       = if (gameModeEnabled) "🎮" else "📖"
        badgeMode.visibility = View.VISIBLE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener(params: WindowManager.LayoutParams) {
        var ix = 0; var iy = 0; var tx = 0f; var ty = 0f; var moved = false

        floatingRoot.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    ix = params.x; iy = params.y
                    tx = event.rawX; ty = event.rawY
                    moved = false; isLongPress = false
                    handler.postDelayed({
                        if (!moved) { isLongPress = true; showModeMenu() }
                    }, LONG_PRESS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - tx).toInt()
                    val dy = (event.rawY - ty).toInt()
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                        moved = true
                        handler.removeCallbacksAndMessages(null)
                        params.x = ix + dx; params.y = iy + dy
                        windowManager.updateViewLayout(floatingRoot, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacksAndMessages(null)
                    if (!moved && !isLongPress) onTap()
                    true
                }
                else -> false
            }
        }
    }

    // ─────────────────────────────────────────────
    // MENU (toque longo)
    // ─────────────────────────────────────────────

    private fun showModeMenu() {
        val singleLabel     = if (captureMode == CaptureMode.SINGLE)     "📷  Tela Cheia ✓"      else "📷  Tela Cheia"
        val areaLabel       = if (captureMode == CaptureMode.AREA)       "▲   Seleção de Área ✓" else "▲   Seleção de Área"
        val continuousLabel = if (captureMode == CaptureMode.CONTINUOUS) "🔄  Tempo Real ✓"       else "🔄  Tempo Real"

        // Toggle do mini-game — sempre visível
        val gameLabel = if (gameModeEnabled) "🎮  Mini-game  [LIGADO]  → toque para desligar"
        else                  "📖  Tradução direta  [LIGADA]  → toque para ligar mini-game"

        // Trava — só aparece no modo Área
        val lockLabel = if (captureMode == CaptureMode.AREA) {
            if (areaLocked) "🔓  Destravar área" else "🔒  Travar área atual"
        } else null

        val options = listOfNotNull(
            singleLabel, areaLabel, continuousLabel,
            "─────────────────",   // separador visual
            gameLabel,
            lockLabel
        ).toTypedArray()

        handler.post {
            val dlg = android.app.AlertDialog.Builder(
                android.view.ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog)
            )
                .setTitle("MangaLens — Configurações")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> switchCaptureTo(CaptureMode.SINGLE)
                        1 -> switchCaptureTo(CaptureMode.AREA)
                        2 -> switchCaptureTo(CaptureMode.CONTINUOUS)
                        3 -> { /* separador — ignora */ }
                        4 -> toggleGameMode()
                        5 -> if (lockLabel != null) toggleLock()
                    }
                }
                .create()
            dlg.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dlg.show()
        }
    }

    // ─────────────────────────────────────────────
    // TOGGLE DO MINI-GAME
    // ─────────────────────────────────────────────

    private fun toggleGameMode() {
        gameModeEnabled = !gameModeEnabled
        GameOverlayManager.gameModeEnabled = gameModeEnabled
        updateButtonAppearance()

        val msg = if (gameModeEnabled)
            "🎮 Mini-game ligado — monte a frase!"
        else
            "📖 Tradução direta ligada — leitura rápida"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ─────────────────────────────────────────────
    // TROCA DE MODO DE CAPTURA
    // ─────────────────────────────────────────────

    private fun switchCaptureTo(mode: CaptureMode) {
        if (captureMode == CaptureMode.CONTINUOUS && mode != CaptureMode.CONTINUOUS) {
            continuousCapture.stop()
        }
        captureMode = mode
        when (mode) {
            CaptureMode.SINGLE -> {
                areaLocked = false; lockedRect = null
                updateButtonAppearance()
                Toast.makeText(this, "📷 Tela cheia", Toast.LENGTH_SHORT).show()
            }
            CaptureMode.AREA -> {
                updateButtonAppearance()
                Toast.makeText(this, "▲ Seleção de área — toque para desenhar", Toast.LENGTH_SHORT).show()
            }
            CaptureMode.CONTINUOUS -> {
                updateButtonAppearance()
                Toast.makeText(this, "🔄 Tempo Real — monitorando mudanças...", Toast.LENGTH_SHORT).show()
                startContinuousMode()
            }
        }
    }

    private fun toggleLock() {
        if (areaLocked) {
            areaLocked = false; lockedRect = null
            updateButtonAppearance()
            Toast.makeText(this, "🔓 Área destravada", Toast.LENGTH_SHORT).show()
        } else {
            if (lockedRect != null) {
                areaLocked = true
                updateButtonAppearance()
                Toast.makeText(this, "🔒 Área travada!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Selecione uma área primeiro", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ─────────────────────────────────────────────
    // TOQUE SIMPLES
    // ─────────────────────────────────────────────

    private fun onTap() {
        when (captureMode) {
            CaptureMode.SINGLE -> {
                Toast.makeText(this, "📸 Capturando...", Toast.LENGTH_SHORT).show()
                captureAndProcess(cropRect = null, isFullScreen = true)
            }
            CaptureMode.AREA -> {
                if (areaLocked && lockedRect != null) {
                    Toast.makeText(this, "🔒 Área travada — capturando...", Toast.LENGTH_SHORT).show()
                    captureAndProcess(cropRect = lockedRect, isFullScreen = false)
                } else {
                    openAreaSelector()
                }
            }
            CaptureMode.CONTINUOUS -> {
                if (continuousCapture.isRunning) {
                    continuousCapture.stop()
                    Toast.makeText(this, "⏸ Tempo Real pausado", Toast.LENGTH_SHORT).show()
                } else {
                    startContinuousMode()
                    Toast.makeText(this, "▶️ Tempo Real retomado", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ─────────────────────────────────────────────
    // SELETOR DE ÁREA
    // ─────────────────────────────────────────────

    private fun openAreaSelector() {
        AreaSelectorOverlay.show(
            context        = this,
            windowManager  = windowManager,
            onAreaSelected = { rect ->
                lockedRect = rect
                Log.d(TAG, "Área selecionada: $rect")
                Toast.makeText(this, "✂️ Capturando área...", Toast.LENGTH_SHORT).show()
                captureAndProcess(cropRect = rect, isFullScreen = false)
            },
            onCancel = { Toast.makeText(this, "Seleção cancelada", Toast.LENGTH_SHORT).show() }
        )
    }

    // ─────────────────────────────────────────────
    // MODO CONTÍNUO
    // ─────────────────────────────────────────────

    private fun startContinuousMode() {
        rebuildImageReader()
        continuousCapture.start(
            scope     = serviceScope,
            cropRect  = lockedRect,
            onCapture = { captureScreen() },
            onChanged = { bitmap, crop ->
                OcrProcessor.process(this, bitmap, crop) { results ->
                    if (results.isNotEmpty()) showResults(results, crop, isFullScreen = false)
                }
            },
            onIdle = { Log.d(TAG, "Contínuo: tela estável") }
        )
    }

    // ─────────────────────────────────────────────
    // PIPELINE PRINCIPAL
    // ─────────────────────────────────────────────

    private fun captureAndProcess(cropRect: Rect?, isFullScreen: Boolean) {
        lastCropRect = cropRect

        serviceScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { rebuildImageReader() }

            val deadline = System.currentTimeMillis() + 4_000L
            while (!frameAvailable && System.currentTimeMillis() < deadline) delay(40)

            if (!frameAvailable) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FloatingService,
                        "❌ Tela não capturada — tente novamente", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            delay(80)

            val bitmap: Bitmap = captureScreen() ?: run {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FloatingService, "❌ Erro ao ler frame", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            Log.d(TAG, "Frame: ${bitmap.width}x${bitmap.height} | crop=$cropRect | game=$gameModeEnabled")
            saveDebugBitmap(bitmap)

            withContext(Dispatchers.Main) {
                OcrProcessor.process(this@FloatingService, bitmap, cropRect) { results ->
                    if (results.isEmpty()) {
                        Toast.makeText(this@FloatingService,
                            "🔍 Nenhum texto encontrado", Toast.LENGTH_SHORT).show()
                    } else {
                        showResults(results, cropRect, isFullScreen)
                    }
                }
            }
        }
    }

    /**
     * Decide qual overlay exibir com base no estado do mini-game.
     */
    private fun showResults(results: List<TextResult>, cropRect: Rect?, isFullScreen: Boolean) {
        if (gameModeEnabled) {
            // Mini-game: embaralha palavras
            GameOverlayManager.gameModeEnabled = true
            GameOverlayManager.show(
                context        = this,
                windowManager  = windowManager,
                results        = results,
                filterSystemUi = isFullScreen
            )
        } else {
            // Tradução direta: sobrepõe o texto traduzido na posição original
            TranslationOverlay.show(
                context       = this,
                windowManager = windowManager,
                results       = results,
                screenWidth   = screenWidth,
                screenHeight  = screenHeight,
                cropRect      = cropRect
            )
        }
    }

    // ─────────────────────────────────────────────
    // MEDIAPROJECTION
    // ─────────────────────────────────────────────

    private fun setupMediaProjection(resultCode: Int, data: Intent?) {
        if (data == null) return
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = pm.getMediaProjection(resultCode, data)
        val m = resources.displayMetrics
        screenWidth   = m.widthPixels
        screenHeight  = m.heightPixels
        screenDensity = m.densityDpi
        rebuildImageReader()
    }

    private fun rebuildImageReader() {
        virtualDisplay?.release(); imageReader?.close()
        imageReader    = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        frameAvailable = false
        imageReader?.setOnImageAvailableListener({ frameAvailable = true }, handler)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MangaLens", screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun captureScreen(): Bitmap? {
        val reader = imageReader ?: return null
        frameAvailable = false
        var image: Image? = null
        return try {
            var c = reader.acquireLatestImage()
            while (c != null) { image?.close(); image = c; c = reader.acquireLatestImage() }
            val img = image ?: return null
            val plane  = img.planes[0]
            val rowPad = plane.rowStride - plane.pixelStride * img.width
            val bmp = Bitmap.createBitmap(
                img.width + rowPad / plane.pixelStride, img.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(plane.buffer)
            if (rowPad == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
        } catch (e: Exception) { Log.e(TAG, "captureScreen: ${e.message}"); null }
        finally { image?.close() }
    }

    private fun tearDownProjection() {
        mediaProjection?.stop(); virtualDisplay?.release(); imageReader?.close()
        mediaProjection = null; virtualDisplay = null; imageReader = null
    }

    private fun saveDebugBitmap(bmp: Bitmap) {
        runCatching {
            val f = java.io.File(getExternalFilesDir(null), "debug_capture.png")
            java.io.FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }
    }

    // ─────────────────────────────────────────────
    // NOTIFICAÇÃO
    // ─────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "MangaLens Overlay", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getService(this, 0,
            Intent(this, FloatingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MangaLens ativo")
            .setContentText("Toque longo no botão para configurar")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(android.R.drawable.ic_delete, "Parar", pi)
            .build()
    }
}