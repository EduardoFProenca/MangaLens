package com.mangalens

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager

    // Lida com o resultado da permissão de captura de tela
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startFloatingService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Permissão negada", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Botão principal
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            checkOverlayPermissionAndStart()
        }

        // Baixa o modelo de tradução em background assim que abre o app
        OcrProcessor.downloadModelIfNeeded {
            Toast.makeText(this, "Modelo de tradução pronto!", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Pede permissão de overlay (aparecer sobre outros apps).
     * Sem isso o botão flutuante não funciona.
     */
    private fun checkOverlayPermissionAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            // Abre as configurações para o usuário permitir manualmente
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(
                this,
                "Ative 'Aparecer sobre outros apps' e volte aqui",
                Toast.LENGTH_LONG
            ).show()
        } else {
            // Pede permissão de captura de tela
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }

    private fun startFloatingService(resultCode: Int, data: Intent) {
        val intent = Intent(this, FloatingService::class.java).apply {
            action = FloatingService.ACTION_START
            putExtra(FloatingService.EXTRA_RESULT_CODE, resultCode)
            putExtra(FloatingService.EXTRA_RESULT_DATA, data)
        }
        startForegroundService(intent)
        // Minimiza o app para liberar a tela
        moveTaskToBack(true)
    }
}