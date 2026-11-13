package com.dockliquida.app.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dockliquida.app.data.Album
import com.dockliquida.app.data.Kind
import com.dockliquida.app.data.Store
import com.dockliquida.app.data.Wall

/*
 * GalleryScreen.kt — galeria fixa e galeria automática (mesma tela, `kind` diferente), no layout da versão anterior:
 *   • Automática: cartão "Importar da galeria fixa" + Slideshow (até 2 álbuns)
 *   • Raiz: "Meus álbuns" (cartões) e "Wallpapers"   • Dentro de um álbum: ações do álbum + imagens
 * Corpo em Material 3; vidro só no botão voltar / ação do topo (rolam por cima da grade, com desfoque).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun GalleryScreen(kind: Kind, vm: AppVM, onOpen: (String) -> Unit, onBack: () -> Unit, bottomPad: Dp) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val allWalls by vm.store.walls.collectAsState()
    val allAlbums by vm.store.albums.collectAsState()
    val albums = allAlbums.filter { it.kind == kind }
    var albumId by remember { mutableStateOf<String?>(null) }            // null = tela raiz (lista de álbuns)
    var selected by remember { mutableStateOf(setOf<String>()) }          // seleção por toque longo
    var toDelete by remember { mutableStateOf(setOf<String>()) }          // imagens aguardando confirmação de exclusão
    var dialog by remember { mutableStateOf("") }                         // novo | renomear | mover | apagarAlbum | ""
    var pick by remember { mutableStateOf("") }                           // "" | app | fixed (seletor de imagens do app)
    var text by remember { mutableStateOf("") }

    val album = albums.firstOrNull { it.id == albumId }
    val shown = allWalls.filter { it.kind == kind && (if (album == null) it.albumId == null else it.albumId == album.id) }.sortedByDescending { it.addedAt }
    val phonePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris -> vm.importUris(uris, kind, albumId) }
    fun addFromPhone() = phonePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    BackHandler(enabled = selected.isNotEmpty() || album != null) { if (selected.isNotEmpty()) selected = emptySet() else albumId = null }

    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    GlassScope(
        Modifier.fillMaxSize(),
        chrome = {
            // ---- topo: voltar + título + ação (ou barra de seleção), tudo em vidro, sobre um degradê do fundo ----
            val bg = cs.background
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(bg.copy(alpha = 0.96f), bg.copy(alpha = 0f))))) {
                if (selected.isEmpty()) {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar", { if (album != null) albumId = null else onBack() })
                        Text(if (kind == Kind.FIXED) "Galeria" else "Galeria automática", Modifier.weight(1f).padding(horizontal = 16.dp), style = MaterialTheme.typography.headlineSmall, maxLines = 1)
                        GlassAction("Adicionar", { addFromPhone() }, icon = Icons.Rounded.Add)
                    }
                } else {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlassIconButton(Icons.Rounded.Close, "Cancelar seleção", { selected = emptySet() })
                        Text("${selected.size} selecionada(s)", Modifier.weight(1f).padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
                        GlassAction("Mover", { dialog = "mover" })
                        GlassAction("Excluir", { toDelete = selected }, Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(6), modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = topInset + 92.dp, bottom = bottomPad + 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ---- (automática) importar da galeria fixa + slideshow ----
            if (kind == Kind.ROTATION) item(span = { GridItemSpan(maxLineSpan) }) {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh)) {
                    Row(Modifier.fillMaxWidth().clickable { pick = "fixed" }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                            Icon(DockIcons.Gallery, null, tint = cs.onPrimaryContainer)
                        }
                        Text("Importar da galeria fixa", Modifier.weight(1f).padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
                        Text("›", style = MaterialTheme.typography.headlineSmall, color = cs.onSurfaceVariant)
                    }
                    HorizontalDivider()
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Slideshow", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Text("Até 2 álbuns", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        }
                        for (a in albums) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHighest).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Switch(a.slide, { on -> if (!vm.store.setSlide(a.id, on)) Toast.makeText(ctx, "Máximo de 2 álbuns no slideshow.", Toast.LENGTH_SHORT).show() })
                            Text(a.name, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1)
                            Text("${allWalls.count { it.albumId == a.id }} imagens", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        }
                        Text(if (albums.any { it.slide }) "Somente os álbuns marcados" else "Todos os wallpapers", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                }
            }

            if (album == null) {
                // ---- raiz: Meus álbuns ----
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Meus álbuns", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        FilledTonalButton(onClick = { text = ""; dialog = "novo" }) { Icon(Icons.Rounded.Add, null, Modifier.size(20.dp)); Text("  Novo álbum") }
                    }
                }
                if (albums.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("Nenhum álbum ainda.", color = cs.onSurfaceVariant) }
                items(albums, key = { it.id }, span = { GridItemSpan(3) }) { a ->
                    val cover = allWalls.filter { it.albumId == a.id }.minByOrNull { it.addedAt }
                    val n = allWalls.count { it.albumId == a.id }
                    Card(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).clickable { albumId = a.id }, shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh)) {
                        Column(Modifier.padding(12.dp)) {
                            Box(Modifier.fillMaxWidth().aspectRatio(1.4f).clip(RoundedCornerShape(16.dp)).background(cs.surfaceVariant)) {
                                if (cover != null) Thumb(vm.store.thumb(cover.id), Modifier.fillMaxSize())
                            }
                            Text(a.name, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            Text("$n imagens", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        }
                    }
                }
            } else {
                // ---- dentro de um álbum: só o título e UM menu ⋮ (a saída é o botão voltar de vidro, no topo) ----
                item(span = { GridItemSpan(maxLineSpan) }) {
                    var menu by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(album.name, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                            Text("${shown.size} imagens", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        }
                        Box {
                            FilledTonalIconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Opções do álbum") }
                            DropdownMenu(
                                expanded = menu,
                                onDismissRequest = { menu = false },
                                modifier = Modifier.widthIn(min = 250.dp, max = 290.dp)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Adicionar do celular") },
                                    leadingIcon = { Icon(PopupIcons.Phone, null, Modifier.size(18.dp)) },
                                    onClick = { menu = false; addFromPhone() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Adicionar da galeria do app") },
                                    leadingIcon = { Icon(DockIcons.Gallery, null, Modifier.size(18.dp)) },
                                    onClick = { menu = false; pick = "app" }
                                )
                                DropdownMenuItem(
                                    text = { Text("Renomear álbum") },
                                    leadingIcon = { Icon(PopupIcons.Edit, null, Modifier.size(18.dp)) },
                                    onClick = { menu = false; text = album.name; dialog = "renomear" }
                                )
                                HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = cs.outlineVariant)
                                DropdownMenuItem(
                                    text = { Text("Excluir álbum", color = cs.error) },
                                    leadingIcon = { Icon(PopupIcons.Delete, null, Modifier.size(18.dp), tint = cs.error) },
                                    onClick = { menu = false; dialog = "apagarAlbum" }
                                )
                            }
                        }
                    }
                }
            }

            // ---- wallpapers ----
            item(span = { GridItemSpan(maxLineSpan) }) { Text("Wallpapers", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleLarge) }
            if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Nada por aqui ainda. Toque em Adicionar para trazer imagens do aparelho.", color = cs.onSurfaceVariant)
            }
            items(shown, key = { it.id }, span = { GridItemSpan(2) }) { w ->
                val on = w.id in selected
                val shape = RoundedCornerShape(20.dp)
                Box(
                    Modifier.aspectRatio(0.62f).clip(shape)
                        .border(if (on) 3.dp else 1.dp, if (on) cs.primary else cs.outlineVariant, shape)
                        .combinedClickable(
                            onClick = { if (selected.isNotEmpty()) selected = if (on) selected - w.id else selected + w.id else onOpen(w.id) },
                            onLongClick = { selected = if (on) selected - w.id else selected + w.id }
                        )
                ) {
                    Thumb(vm.store.thumb(w.id), Modifier.fillMaxSize())
                    Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).clickable { toDelete = setOf(w.id) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, "Excluir", Modifier.size(16.dp), tint = Color.White)
                    }
                    if (on) Box(Modifier.align(Alignment.BottomStart).padding(8.dp).size(30.dp).clip(CircleShape).background(cs.primary), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = cs.onPrimary)
                    }
                }
            }
        }
    }

    // ---- seletor de imagens que já estão no app ----
    if (pick == "app") WallPickerDialog("Adicionar ao álbum", allWalls.filter { it.kind == kind && it.albumId != albumId }, vm.store,
        onConfirm = { ids -> vm.store.assignAlbum(ids, albumId); pick = "" }, onDismiss = { pick = "" })
    if (pick == "fixed") WallPickerDialog("Importar da galeria fixa", allWalls.filter { it.kind == Kind.FIXED }, vm.store,
        onConfirm = { ids -> vm.copyTo(ids, Kind.ROTATION, albumId) { n -> Toast.makeText(ctx, "$n imagem(ns) importada(s).", Toast.LENGTH_SHORT).show() }; pick = "" },
        onDismiss = { pick = "" })

    // ---- diálogos ----
    when (dialog) {
        "novo", "renomear" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text(if (dialog == "novo") "Novo álbum" else "Renomear álbum") },
            text = { OutlinedTextField(text, { text = it.take(40) }, label = { Text("Nome") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (dialog == "novo") albumId = vm.store.createAlbum(text, kind).id else album?.let { vm.store.renameAlbum(it.id, text) }
                    dialog = ""
                }) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancelar") } }
        )
        "mover" -> AlertDialog(
            onDismissRequest = { dialog = "" }, title = { Text("Mover para o álbum") },
            text = {
                Column {
                    TextButton(onClick = { vm.store.assignAlbum(selected, null); selected = emptySet(); dialog = "" }) { Text("Sem álbum") }
                    for (a in albums) TextButton(onClick = { vm.store.assignAlbum(selected, a.id); selected = emptySet(); dialog = "" }) { Text(a.name) }
                }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancelar") } }
        )
        "apagarAlbum" -> AlertDialog(
            onDismissRequest = { dialog = "" }, title = { Text("Excluir o álbum?") },
            text = { Text("As imagens não são apagadas: elas só saem do álbum.") },
            confirmButton = { TextButton(onClick = { album?.let { vm.store.deleteAlbum(it.id) }; albumId = null; dialog = "" }) { Text("Excluir") } },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancelar") } }
        )
    }
    if (toDelete.isNotEmpty()) AlertDialog(
        onDismissRequest = { toDelete = emptySet() }, title = { Text("Excluir ${toDelete.size} imagem(ns)?") },
        text = { Text("Isso remove as imagens e os ajustes de dock delas do app. Não dá para desfazer.") },
        confirmButton = { TextButton(onClick = { vm.store.remove(toDelete); selected = selected - toDelete; toDelete = emptySet() }) { Text("Excluir") } },
        dismissButton = { TextButton(onClick = { toDelete = emptySet() }) { Text("Cancelar") } }
    )
}

/** Tela cheia para escolher várias imagens que já estão no app (Material 3). */
@Composable
private fun WallPickerDialog(title: String, candidates: List<Wall>, store: Store, onConfirm: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var sel by remember { mutableStateOf(setOf<String>()) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(16.dp), shape = RoundedCornerShape(28.dp), color = cs.surfaceContainerHigh) {
            Column {
                Text(title, Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
                if (candidates.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Nenhuma imagem disponível.", color = cs.onSurfaceVariant) }
                else LazyVerticalGrid(
                    GridCells.Adaptive(96.dp), Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(candidates, key = { it.id }) { w ->
                        val on = w.id in sel
                        val shape = RoundedCornerShape(16.dp)
                        Box(Modifier.aspectRatio(0.62f).clip(shape).border(if (on) 3.dp else 1.dp, if (on) cs.primary else cs.outlineVariant, shape).clickable { sel = if (on) sel - w.id else sel + w.id }) {
                            Thumb(store.thumb(w.id), Modifier.fillMaxSize())
                            if (on) Box(Modifier.align(Alignment.BottomStart).padding(6.dp).size(26.dp).clip(CircleShape).background(cs.primary), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = cs.onPrimary)
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Cancelar") }
                    Button(onClick = { onConfirm(sel) }, enabled = sel.isNotEmpty()) { Text("Adicionar (${sel.size})") }
                }
            }
        }
    }
}
