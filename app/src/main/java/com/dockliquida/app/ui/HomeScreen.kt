package com.dockliquida.app.ui

import android.util.LruCache
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.dockliquida.app.glass.GlassParams
import com.dockliquida.app.data.Kind
import com.dockliquida.app.render.WallpaperRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi

private data class HeroKey(val id: String, val revision: Int, val modifiedAt: Long, val outW: Int, val outH: Int, val dock: GlassParams)
private val heroCache = object : LruCache<HeroKey, ImageBitmap>(4 * 1024 * 1024) {
    override fun sizeOf(key: HeroKey, value: ImageBitmap) = value.width * value.height * 4
}
private val heroRenderMutex = Mutex()

/** Início (Material 3): prévia do último wallpaper num cartão, duas ações e a lista de recentes. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(vm: AppVM, onOpen: (String) -> Unit, bottomPad: Dp) {
    val walls by vm.store.walls.collectAsState()
    val lastId by vm.prefs.lastWall.collectAsState()
    val individual by vm.prefs.individual.collectAsState()
    val fixed = remember(walls) { walls.filter { it.kind == Kind.FIXED }.sortedByDescending { it.addedAt } }
    val hero = fixed.firstOrNull { it.id == lastId } ?: fixed.firstOrNull()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.importUris(listOf(uri), Kind.FIXED, null) { added -> added.firstOrNull()?.let { onOpen(it.id) } }
    }
    fun pick() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    val heroKey = remember(hero, walls, individual) {
        hero?.let { HeroKey(it.id, it.rev, vm.store.file(it.id).lastModified(), it.outW, it.outH, vm.prefs.dockFor(it)) }
    }
    var heroFailed by remember(heroKey) { mutableStateOf(false) }
    val heroImg = key(heroKey) {
        val result by produceState<ImageBitmap?>(initialValue = heroKey?.let { heroCache.get(it) }) {
            val snapshot = heroKey
            val loaded = if (snapshot == null) null else withContext(Dispatchers.Default) {
                heroRenderMutex.withLock {
                    heroCache.get(snapshot) ?: try {
                        val f = (900.0 / max(snapshot.outW, snapshot.outH)).coerceAtMost(1.0)
                        WallpaperRenderer.renderFull(vm.store.file(snapshot.id), snapshot.outW, snapshot.outH, snapshot.dock, f)
                            ?.asImageBitmap()?.also { heroCache.put(snapshot, it) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: OutOfMemoryError) {
                        null
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            value = loaded
            heroFailed = snapshot != null && loaded == null
        }
        result
    }

    val introClock = rememberIntroClock()

    Column(Modifier.statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = bottomPad + 16.dp)) {
        Text("Dock Líquida", Modifier.cascadeIn(0).padding(top = 24.dp), style = MaterialTheme.typography.headlineMedium)
        Text("Wallpapers com dock de vidro", Modifier.cascadeIn(1), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Card(
            Modifier.cascadeIn(2).fillMaxWidth().padding(top = 20.dp), shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Box(Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) {
                Crossfade(targetState = Triple(hero, heroImg, heroFailed), animationSpec = tween(320), label = "heroPreview") { (h, img, failed) ->
                    if (h != null && img != null) {
                        Image(img, "Prévia do wallpaper com a Dock", Modifier.height(520.dp).aspectRatio(h.outW.toFloat() / h.outH).pressScale(0.985f).clip(RoundedCornerShape(20.dp)).clickable { onOpen(h.id) }, contentScale = ContentScale.Fit)
                    } else Column(Modifier.height(280.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (h == null) "Nenhum wallpaper ainda" else if (failed) "Não foi possível gerar a prévia" else "Gerando prévia…", style = MaterialTheme.typography.titleMedium)
                        if (h == null) Text("Escolha uma imagem para ver a prévia com a sua Dock.", Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Row(Modifier.cascadeIn(3).fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { pick() }, Modifier.weight(1f).height(52.dp).pressScale()) { Text("Escolher wallpaper") }
            FilledTonalButton(onClick = { if (hero != null) onOpen(hero.id) else pick() }, Modifier.weight(1f).height(52.dp).pressScale()) { Text("Personalizar Dock") }
        }

        Text("Recentes", Modifier.cascadeIn(4).padding(top = 28.dp, bottom = 10.dp), style = MaterialTheme.typography.titleMedium)
        if (fixed.isEmpty()) Text("As imagens que você abrir aparecem aqui.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(fixed.take(12), key = { _, w -> w.id }) { index, w ->
                Thumb(vm.store.thumb(w.id), Modifier.animateItem().cascadeIn(index + 5, introClock).width(96.dp).aspectRatio(0.62f).pressScale(0.95f).clip(RoundedCornerShape(20.dp)).clickable { onOpen(w.id) }, revision = w.rev)
            }
        }
    }
}
