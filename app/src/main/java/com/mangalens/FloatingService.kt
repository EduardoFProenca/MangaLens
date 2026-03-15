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

    // ── UI ────────────────────────────────────────
    private lateinit var windowManager: WindowManager
    private lateinit var floatingRoot: View
    private lateinit var floatingBtn: TextView
    private lateinit var badgeLock: TextView

    // ── MediaProjection ───────────────────────────
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth  = 0
    private var screenHeight = 0
    private var screenDensity = 0

    // ── Modos de captura ──────────────────────────
    private enum class CaptureMode { SINGLE, AREA }
    private var captureMode = CaptureMode.SINGLE

    // ── Estado da área ────────────────────────────
    private var lockedRect: Rect? = null   // área travada (cadeado ativo)
    private var areaLocked = false         // true = não pede seleção a cada toque

    // ── Controle de toque ─────────────────────────
    private val handler     = Handler(Looper.getMainLooper())
    private var isLongPress = false
    private val LONG_PRESS  = 500L

    // ── Coroutines ────────────────────────────────
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
        AreaSelectorOverlay.dismiss(windowManager)
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

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 100; y = 300 }

        setupTouchListener(params)
        windowManager.addView(floatingRoot, params)
    }

    /** Atualiza ícone e cor do botão conforme o modo atual */
    private fun updateButtonAppearance() {
        val (icon, color) = when (captureMode) {
            CaptureMode.SINGLE -> "📷" to Color.parseColor("#CC6200EE")  // roxo
            CaptureMode.AREA   -> "▲"  to Color.parseColor("#CC E65100".replace(" ","")) // laranja
        }
        floatingBtn.text = icon
        (floatingBtn.background as? GradientDrawable)?.setColor(color)
            ?: floatingBtn.setBackgroundColor(color)

        badgeLock.visibility = if (areaLocked) View.VISIBLE else View.GONE
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
        val options = when (captureMode) {
            CaptureMode.SINGLE -> arrayOf(
                "📷  Captura tela cheia (atual)",
                "▲   Modo seleção de área"
            )
            CaptureMode.AREA -> arrayOf(
                "📷  Captura tela cheia",
                "▲   Modo seleção de área (atual)",
                if (areaLocked) "🔓  Destravar área" else "🔒  Travar área atual"
            )
        }

        handler.post {
            val dlg = android.app.AlertDialog.Builder(
                android.view.ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog)
            )
                .setTitle("Modo de captura")
                .setItems(options) { _, which ->
                    when {
                        // Tela cheia
                        which == 0 && captureMode != CaptureMode.SINGLE -> {
                            captureMode = CaptureMode.SINGLE
                            lockedRect  = null
                            areaLocked  = false
                            updateButtonAppearance()
                            Toast.makeText(this, "📷 Captura tela cheia", Toast.LENGTH_SHORT).show()
                        }
                        // Área
                        (which == 1) -> {
                            captureMode = CaptureMode.AREA
                            areaLocked  = false
                            lockedRect  = null
                            updateButtonAppearance()
                            Toast.makeText(this, "▲ Seleção de área — toque para desenhar", Toast.LENGTH_SHORT).show()
                        }
                        // Travar / destravar (só aparece no modo AREA)
                        (which == 2 && captureMode == CaptureMode.AREA) -> {
                            toggleLock()
                        }
                    }
                }
                .create()
            dlg.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dlg.show()
        }
    }

    private fun toggleLock() {
        if (areaLocked) {
            // Destrava
            areaLocked = false
            lockedRect = null
            updateButtonAppearance()
            Toast.makeText(this, "🔓 Área destravada", Toast.LENGTH_SHORT).show()
        } else {
            // Trava a área atual (se existir)
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
    // TOQUE SIMPLES → ação de captura
    // ─────────────────────────────────────────────

    private fun onTap() {
        when (captureMode) {
            CaptureMode.SINGLE -> {
                Toast.makeText(this, "📸 Capturando tela...", Toast.LENGTH_SHORT).show()
                captureAndProcess(cropRect = null)
            }
            CaptureMode.AREA -> {
                if (areaLocked && lockedRect != null) {
                    // Área travada: captura direto sem pedir seleção
                    Toast.makeText(this, "🔒 Capturando área travada...", Toast.LENGTH_SHORT).show()
                    captureAndProcess(cropRect = lockedRect)
                } else {
                    // Sem trava: abre seletor a cada toque
                    openAreaSelector()
                }
            }
        }
    }

    // ─────────────────────────────────────────────
    // SELETOR DE ÁREA
    // ─────────────────────────────────────────────

    private fun openAreaSelector() {
        AreaSelectorOverlay.show(
            context       = this,
            windowManager = windowManager,
            onAreaSelected = { rect ->
                lockedRect = rect
                Log.d(TAG, "Área selecionada: $rect")
                // Captura imediatamente após selecionar
                Toast.makeText(this, "✂️ Capturando área...", Toast.LENGTH_SHORT).show()
                captureAndProcess(cropRect = rect)
            },
            onCancel = {
                Toast.makeText(this, "Seleção cancelada", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // ─────────────────────────────────────────────
    // CAPTURA + OCR + TRADUÇÃO  ← pipeline principal
    // ─────────────────────────────────────────────

    private fun captureAndProcess(cropRect: Rect?) {
        serviceScope.launch(Dispatchers.IO) {

            // 1. Reconstrói o ImageReader para garantir frame novo
            withContext(Dispatchers.Main) { rebuildImageReader() }

            // 2. Aguarda sinal de frame disponível (até 4 s)
            val deadline = System.currentTimeMillis() + 4_000L
            while (!frameAvailable && System.currentTimeMillis() < deadline) {
                delay(40)
            }

            if (!frameAvailable) {
                Log.w(TAG, "Timeout aguardando frame")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FloatingService,
                        "❌ Tela não capturada — tente novamente", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            // Pequena pausa para o frame estabilizar
            delay(80)

            // 3. Lê o bitmap
            val fullBitmap: Bitmap = captureScreen() ?: run {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FloatingService,
                        "❌ Erro ao ler frame", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            Log.d(TAG, "Frame capturado: ${fullBitmap.width}x${fullBitmap.height}")
            saveDebugBitmap(fullBitmap)

            // 4. Envia para OCR+Tradução na main thread (ML Kit exige)
            withContext(Dispatchers.Main) {
                OcrProcessor.process(
                    context  = this@FloatingService,
                    bitmap   = fullBitmap,
                    cropRect = cropRect
                ) { results ->
                    if (results.isEmpty()) {
                        Toast.makeText(
                            this@FloatingService,
                            "🔍 Nenhum texto encontrado na área",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        GameOverlayManager.show(
                            context       = this@FloatingService,
                            windowManager = windowManager,
                            results       = results
                        )
                    }
                }
            }
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
        virtualDisplay?.release()
        imageReader?.close()

        imageReader    = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        frameAvailable = false

        imageReader?.setOnImageAvailableListener({ frameAvailable = true }, handler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MangaLens", screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
        Log.d(TAG, "VirtualDisplay criado: ${screenWidth}x${screenHeight}")
    }

    private fun captureScreen(): Bitmap? {
        val reader = imageReader ?: return null
        frameAvailable = false
        var image: Image? = null
        return try {
            var c = reader.acquireLatestImage()
            while (c != null) { image?.close(); image = c; c = reader.acquireLatestImage() }
            val img = image ?: return null
            val plane = img.planes[0]
            val rowPad = plane.rowStride - plane.pixelStride * img.width
            val bmp = Bitmap.createBitmap(
                img.width + rowPad / plane.pixelStride, img.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(plane.buffer)
            if (rowPad == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
        } catch (e: Exception) {
            Log.e(TAG, "captureScreen: ${e.message}"); null
        } finally {
            image?.close()
        }
    }

    private fun tearDownProjection() {
        mediaProjection?.stop(); virtualDisplay?.release(); imageReader?.close()
        mediaProjection = null; virtualDisplay = null; imageReader = null
    }

    private fun saveDebugBitmap(bmp: Bitmap) {
        runCatching {
            val f = java.io.File(getExternalFilesDir(null), "debug_capture.png")
            java.io.FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
            Log.d(TAG, "Debug salvo: ${f.absolutePath}")
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
            .setContentText("Toque longo para mudar o modo de captura")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(android.R.drawable.ic_delete, "Parar", pi)
            .build()
    }
}