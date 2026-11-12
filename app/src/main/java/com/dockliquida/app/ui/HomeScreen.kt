package com.dockliquida.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dockliquida.app.data.Kind
import com.dockliquida.app.render.WallpaperRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Início (Material 3): prévia do último wallpaper num cartão, duas ações e a lista de recentes. */
@Composable
fun HomeScreen(vm: AppVM, onOpen: (String) -> Unit, bottomPad: Dp) {
    val walls by vm.store.walls.collectAsState()
    val lastId by vm.prefs.lastWall.collectAsState()
    val individual by vm.prefs.individual.collectAsState()
    val fixed = walls.filter { it.kind == Kind.FIXED }.sortedByDescending { it.addedAt }
    val hero = fixed.firstOrNull { it.id == lastId } ?: fixed.firstOrNull()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.importUris(listOf(uri), Kind.FIXED, null) { added -> added.firstOrNull()?.let { onOpen(it.id) } }
    }
    fun pick() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    // Prévia leve (≈900 px) feita pelo MESMO motor do editor; refaz quando a dock do wallpaper muda
    val heroImg by produceState<ImageBitmap?>(null, hero, individual) {
        value = if (hero == null) null else withContext(Dispatchers.Default) {
            val f = (900.0 / max(hero.outW, hero.outH)).coerceAtMost(1.0)
            WallpaperRenderer.renderFull(vm.store.file(hero.id), hero.outW, hero.outH, vm.prefs.dockFor(hero), f)?.asImageBitmap()
        }
    }

    Column(Modifier.statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = bottomPad + 16.dp)) {
        Text("Dock Líquida", Modifier.padding(top = 24.dp), style = MaterialTheme.typography.headlineMedium)
        Text("Wallpapers com dock de vidro", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Card(
            Modifier.fillMaxWidth().padding(top = 20.dp), shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Box(Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) {
                val img = heroImg
                if (hero != null && img != null) {
                    Image(img, "Prévia do wallpaper com a Dock", Modifier.height(520.dp).aspectRatio(hero.outW.toFloat() / hero.outH).clip(RoundedCornerShape(20.dp)).clickable { onOpen(hero.id) }, contentScale = ContentScale.Fit)
                } else Column(Modifier.height(280.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (hero == null) "Nenhum wallpaper ainda" else "Gerando prévia…", style = MaterialTheme.typography.titleMedium)
                    if (hero == null) Text("Escolha uma imagem para ver a prévia com a sua Dock.", Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { pick() }, Modifier.weight(1f).height(52.dp)) { Text("Escolher wallpaper") }
            FilledTonalButton(onClick = { if (hero != null) onOpen(hero.id) else pick() }, Modifier.weight(1f).height(52.dp)) { Text("Personalizar Dock") }
        }

        Text("Recentes", Modifier.padding(top = 28.dp, bottom = 10.dp), style = MaterialTheme.typography.titleMedium)
        if (fixed.isEmpty()) Text("As imagens que você abrir aparecem aqui.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(fixed.take(12), key = { it.id }) { w ->
                Thumb(vm.store.thumb(w.id), Modifier.width(96.dp).aspectRatio(0.62f).clip(RoundedCornerShape(20.dp)).clickable { onOpen(w.id) })
            }
        }
    }
}
