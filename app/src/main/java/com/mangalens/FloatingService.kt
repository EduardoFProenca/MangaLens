package com.mangalens

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import android.widget.ImageButton
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.*

class FloatingService : LifecycleService() {

    // ─── WindowManager: responsável por desenhar na tela ───
    private lateinit var windowManager: WindowManager
    private lateinit var floatingButton: View

    // ─── MediaProjection: responsável por capturar a tela ───
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // ─── Controle de toque longo ───
    private val handler = Handler(Looper.getMainLooper())
    private var isLongPress = false
    private val LONG_PRESS_DURATION = 500L // meio segundo

    // ─── Coroutine para não travar a UI ───
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val EXTRA_RESULT_CODE = "RESULT_CODE"
        const val EXTRA_RESULT_DATA = "RESULT_DATA"
        private const val CHANNEL_ID = "MangaLensChannel"
        private const val NOTIFICATION_ID = 1
    }

    // ─────────────────────────────────────────────
    // CICLO DE VIDA DO SERVIÇO
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
                // Pega os dados de permissão vindos da MainActivity
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

                startForeground(NOTIFICATION_ID, buildNotification())
                setupMediaProjection(resultCode, resultData)
                showFloatingButton()
            }
            ACTION_STOP -> {
                stopSelf()
            }
        }

        return START_STICKY // reinicia se for morto pelo sistema
    }

    override fun onDestroy() {
        super.onDestroy()
        // Limpeza: muito importante para não vazar memória!
        serviceScope.cancel()
        mediaProjection?.stop()
        virtualDisplay?.release()
        imageReader?.close()
        if (::floatingButton.isInitialized) {
            windowManager.removeView(floatingButton)
        }
    }

    // ─────────────────────────────────────────────
    // BOTÃO FLUTUANTE
    // ─────────────────────────────────────────────

    private fun showFloatingButton() {
        // Infla o layout do botão
        floatingButton = LayoutInflater.from(this)
            .inflate(R.layout.layout_floating_button, null)

        // Parâmetros da janela: fica por cima de tudo
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        setupButtonTouchListener(params)
        windowManager.addView(floatingButton, params)
    }

    private fun setupButtonTouchListener(params: WindowManager.LayoutParams) {
        var initialX = 0; var initialY = 0
        var touchX = 0f; var touchY = 0f

        floatingButton.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    isLongPress = false
                    // Agenda o toque longo
                    handler.postDelayed({
                        isLongPress = true
                        showRadialMenu() // abre menu circular
                    }, LONG_PRESS_DURATION)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    // Só move se não for toque longo
                    if (!isLongPress) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(floatingButton, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacksAndMessages(null)
                    if (!isLongPress) {
                        // Toque rápido = captura imediata
                        captureAndProcess()
                    }
                    true
                }
                else -> false
            }
        }
    }

    // ─────────────────────────────────────────────
    // MENU RADIAL (toque longo)
    // ─────────────────────────────────────────────

    private fun showRadialMenu() {
        // Por enquanto exibe um Toast; você vai criar o layout depois
        android.widget.Toast.makeText(
            this, "Menu: Tempo Real | Área | Tela Cheia", android.widget.Toast.LENGTH_SHORT
        ).show()
        // TODO: Implementar o menu circular com as 3 opções + Switch Modo Game
    }

    // ─────────────────────────────────────────────
    // CAPTURA DE TELA (MediaProjection)
    // ─────────────────────────────────────────────

    private fun setupMediaProjection(resultCode: Int, data: Intent?) {
        if (data == null) return

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        // Pega o tamanho da tela
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        // ImageReader: buffer que guarda o frame mais recente
        // MAX_IMAGES = 2 evita acumular frames na memória
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        // VirtualDisplay: espelha a tela real para o ImageReader
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MangaLensCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null, null
        )
    }

    /**
     * Captura um frame e passa para o OCR.
     * Roda em background para não travar a UI.
     */
    private fun captureAndProcess() {
        serviceScope.launch(Dispatchers.IO) {
            val bitmap = captureScreen() ?: return@launch

            // Passa para o processador de OCR + Tradução
            withContext(Dispatchers.Main) {
                OcrProcessor.process(this@FloatingService, bitmap) { results ->
                    // results = lista de TextBlock com texto e BoundingBox
                    GameOverlayManager.show(this@FloatingService, windowManager, results)
                }
            }
        }
    }

    /**
     * Extrai o Bitmap do ImageReader de forma eficiente.
     * acquireLatestImage() pega só o frame mais recente (descarta os velhos).
     */
    private fun captureScreen(): android.graphics.Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null

        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = android.graphics.Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                android.graphics.Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            bitmap
        } finally {
            image.close() // CRÍTICO: sem isso a memória esgota!
        }
    }

    // ─────────────────────────────────────────────
    // NOTIFICAÇÃO (obrigatória para Foreground Service)
    // ─────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "MangaLens Overlay",
            NotificationManager.IMPORTANCE_LOW // LOW = sem som
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, FloatingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MangaLens ativo")
            .setContentText("Toque no botão flutuante para traduzir")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(android.R.drawable.ic_delete, "Parar", stopPending)
            .build()
    }
}