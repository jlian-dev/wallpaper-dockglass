package com.dockliquida.app.ui

import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dockliquida.app.data.Kind

/**
 * Raiz do app: tema Material 3 + abas + editor em tela cheia.
 * O Liquid Glass aparece em dois lugares: a barra inferior (aqui) e os botões do topo (nas telas).
 */
@Composable
fun AppRoot() {
    val app: AppVM = viewModel()
    val editor: EditorVM = viewModel()
    val theme by app.prefs.theme.collectAsState()
    val glassFull by app.prefs.glassFull.collectAsState()
    val dynamicColors by app.prefs.dynamicColors.collectAsState()
    // Historico real de abas: voltar remove a ultima, sem alternar em circulo.
    var tabHistory by rememberSaveable { mutableStateOf(listOf("home")) }
    val tab = tabHistory.last()
    fun openTab(destination: String) {
        if (tab != destination) tabHistory = tabHistory + destination
    }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    var lastExitBack by remember { mutableLongStateOf(0L) }

    DockTheme(theme, dynamicColors = dynamicColors) {
        CompositionLocalProvider(LocalGlassFull provides glassFull) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                AppLaunchScreen {
                    val id = editing
                    if (id != null) {
                        EditorScreen(editor, id) { editing = null }
                    } else {
                        // Gestos do album e da selecao sao tratados dentro de GalleryScreen.
                        // Na raiz, um gesto volta a aba anterior; dois gestos proximos saem do app.
                        BackHandler {
                            if (tabHistory.size > 1) {
                                tabHistory = tabHistory.dropLast(1)
                                lastExitBack = 0L
                            } else {
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastExitBack <= 2000L) {
                                    (context as? android.app.Activity)?.finish()
                                } else {
                                    lastExitBack = now
                                    Toast.makeText(context, "Deslize novamente para sair", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        val navSpace = 76.dp                          // espaço reservado para a barra inferior não cobrir o fim da lista
                        val goHome = { openTab("home") }
                        GlassScope(
                            Modifier.fillMaxSize(),
                            chrome = {
                                // Barra inferior de vidro: o conteúdo (grade de imagens) rola por baixo e fica desfocado
                                GlassSurface(
                                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 10.dp),
                                    CircleShape, elevation = 8.dp
                                ) {
                                    // Barra fina: ~46 dp de altura, ocupa só o necessário e fica centralizada
                                    Row(Modifier.padding(horizontal = 6.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                                        NavItem(tab == "home", Icons.Rounded.Home, "Início") { openTab("home") }
                                        NavItem(tab == "gallery", DockIcons.Gallery, "Galeria") { openTab("gallery") }
                                        NavItem(tab == "auto", Icons.Rounded.Refresh, "Auto") { openTab("auto") }
                                        NavItem(tab == "settings", Icons.Rounded.Settings, "Ajustes") { openTab("settings") }
                                    }
                                }
                            }
                        ) {
                            // Saída e entrada sequenciais: a tela anterior some antes de montar a próxima.
                            // Isso evita renderizar duas galerias pesadas simultaneamente.
                            var visibleTab by rememberSaveable { mutableStateOf(tab) }
                            val screenOpacity = remember { Animatable(1f) }
                            val screenOffset = remember { Animatable(0f) }

                            LaunchedEffect(tab) {
                                if (visibleTab != tab) {
                                    screenOpacity.animateTo(0f, animationSpec = tween(95))
                                    visibleTab = tab
                                    screenOffset.snapTo(12f)
                                    coroutineScope {
                                        launch { screenOpacity.animateTo(1f, animationSpec = tween(210)) }
                                        launch { screenOffset.animateTo(0f, animationSpec = tween(210)) }
                                    }
                                }
                            }
                            Box(
                                Modifier.fillMaxSize().graphicsLayer {
                                    alpha = screenOpacity.value
                                    translationY = screenOffset.value * density
                                }
                            ) {
                                when (visibleTab) {
                                    "home" -> HomeScreen(app, { editing = it }, navSpace)
                                    "gallery" -> GalleryScreen(Kind.FIXED, app, { editing = it }, goHome, navSpace)
                                    "auto" -> GalleryScreen(Kind.ROTATION, app, { editing = it }, goHome, navSpace)
                                    else -> SettingsScreen(app, navSpace)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
