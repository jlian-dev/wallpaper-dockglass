package com.dockliquida.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dockliquida.app.system.RotationWorker
import com.dockliquida.app.ui.AppRoot

/** Única Activity do app: liga a tela de ponta a ponta (barras transparentes) e entrega tudo ao Compose. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Garante que o agendamento da troca automática bate com as preferências (ele pode se perder após restaurar
        // o backup automático do Android). runCatching: um problema aqui nunca pode impedir o app de abrir.
        runCatching { RotationWorker.ensureScheduled(applicationContext) }
        setContent { AppRoot() }
    }
}
