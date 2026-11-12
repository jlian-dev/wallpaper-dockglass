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
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/* Common.kt — peças Material 3 reutilizadas nas telas (cartões em camadas, sliders, chips, abas, miniaturas). */

/** Cartão de seção: superfície em camada do Material 3 (surfaceContainerHigh), cantos de 24 dp, sem borda extra. */
@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
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

/** Abas/seletor segmentado em Material 3 (trilho surfaceContainerHighest + opção ativa em primaryContainer). */
@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(modifier, shape = RoundedCornerShape(22.dp), color = cs.surfaceContainerHighest) {
        Row(Modifier.padding(4.dp)) {
            for ((key, label) in options) {
                val on = key == selected
                val highlight by animateColorAsState(
                    targetValue = if (on) cs.primaryContainer else Color.Transparent,
                    animationSpec = tween(durationMillis = 220),
                    label = "segmentHighlight"
                )
                val labelColor by animateColorAsState(
                    targetValue = if (on) cs.onPrimaryContainer else cs.onSurfaceVariant,
                    animationSpec = tween(durationMillis = 220),
                    label = "segmentText"
                )
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                        .background(highlight)
                        .clickable { onSelect(key) }.padding(vertical = 12.dp, horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, color = labelColor)
                }
            }
        }
    }
}

/** Cache de miniaturas na memória (~24 MB). */
private val thumbCache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

/** Miniatura carregada em segundo plano (já é pequena: 360 px). */
@Composable
fun Thumb(file: File, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop) {
    val path = file.absolutePath
    val key = path + ":" + file.lastModified()                       // mudou o arquivo? a chave muda e a miniatura é recarregada
    val img by produceState<ImageBitmap?>(initialValue = thumbCache.get(key), key) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path)?.asImageBitmap() }?.also { thumbCache.put(key, it) }
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        img?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = scale) }
    }
}

fun Modifier.rounded(dp: Int) = this.clip(RoundedCornerShape(dp.dp))
