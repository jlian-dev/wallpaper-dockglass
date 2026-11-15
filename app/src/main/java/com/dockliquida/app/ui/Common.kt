package com.dockliquida.app.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.graphics.graphicsLayer

/* Common.kt — peças Material 3 reutilizadas nas telas (cartões em camadas, sliders, chips, abas, miniaturas). */

/** Cartão de seção: superfície em camada do Material 3 (surfaceContainerHigh), cantos de 24 dp, sem borda extra. */
@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** Slider M3 com rótulo e valor à direita. */
@Composable
fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(valueText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}

/** Fileira de FilterChips do Material 3 para escolher UMA opção entre várias. */
@Composable
fun ChipRow(options: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((key, label) in options) FilterChip(selected = key == selected, onClick = { onSelect(key) }, label = { Text(label) })
    }
}

/**
 * Abas/seletor segmentado em Material 3 (trilho surfaceContainerHighest + opção ativa em primaryContainer).
 * A "pílula" da opção ativa DESLIZA de uma opção para a outra (mesma ideia das abas do editor).
 */
@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val count = options.size.coerceAtLeast(1)
    val index = options.indexOfFirst { it.first == selected }          // -1 = nenhuma opção ativa
    // bias: posição horizontal da pílula (-1 = primeira opção, +1 = última). Todas as opções têm a mesma largura.
    val bias by animateFloatAsState(
        targetValue = if (count <= 1) -1f else 2f * index.coerceAtLeast(0) / (count - 1) - 1f,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "segmentSlide"
    )
    val pillAlpha by animateFloatAsState(
        targetValue = if (index >= 0) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "segmentPill"
    )
    Surface(modifier, shape = RoundedCornerShape(22.dp), color = cs.surfaceContainerHighest) {
        Box(Modifier.padding(4.dp)) {
            // Fundo da seleção: uma única pílula (1/N da largura) que muda de posição.
            Box(Modifier.matchParentSize()) {
                Box(
                    Modifier
                        .fillMaxWidth(1f / count)
                        .fillMaxHeight()
                        .align(BiasAlignment(horizontalBias = bias, verticalBias = 0f))
                        .graphicsLayer { alpha = pillAlpha }
                        .clip(RoundedCornerShape(18.dp))
                        .background(cs.primaryContainer)
                )
            }
            Row(Modifier.fillMaxWidth()) {
                for ((key, label) in options) {
                    val on = key == selected
                    val labelColor by animateColorAsState(
                        targetValue = if (on) cs.onPrimaryContainer else cs.onSurfaceVariant,
                        animationSpec = tween(durationMillis = 220),
                        label = "segmentText"
                    )
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                            .clickable { onSelect(key) }.padding(vertical = 12.dp, horizontal = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, color = labelColor)
                    }
                }
            }
        }
    }
}

/** Cache de miniaturas na memória (~24 MB). */
private val thumbCache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

private val thumbDecodes = Semaphore(2)

/** Loads a thumbnail off the UI thread, with state isolated per file revision. */
@Composable
fun Thumb(file: File, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop, revision: Int = 0) {
    val path = file.absolutePath
    val cacheKey = "$path:${file.lastModified()}:${file.length()}:$revision"
    key(cacheKey) {
        val img by produceState<ImageBitmap?>(initialValue = thumbCache.get(cacheKey)) {
            value = withContext(Dispatchers.IO) {
                thumbDecodes.withPermit {
                    thumbCache.get(cacheKey) ?: BitmapFactory.decodeFile(path)?.asImageBitmap()?.also {
                        thumbCache.put(cacheKey, it)
                    }
                }
            }
        }
        val fade = animateFloatAsState(
            targetValue = if (img != null) 1f else 0f,
            animationSpec = tween(240),
            label = "thumbFade"
        )
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
            img?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }, contentScale = scale) }
        }
    }
}

fun Modifier.rounded(dp: Int) = this.clip(RoundedCornerShape(dp.dp))
