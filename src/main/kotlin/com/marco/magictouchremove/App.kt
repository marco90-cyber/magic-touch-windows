package com.marco.magictouchremove

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.Dialog
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.text.SimpleDateFormat
import java.util.Date
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.layout.heightIn
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// =========================================================================
// File dialog nativi di Windows
// =========================================================================

fun pickImageToOpen(): File? {
    val d = FileDialog(null as Frame?, "Apri foto", FileDialog.LOAD)
    d.file = "*.jpg;*.jpeg;*.png;*.bmp;*.gif"
    d.isVisible = true
    val name = d.file ?: return null
    return File(d.directory, name)
}

fun pickImageToSave(suggested: String): File? {
    val d = FileDialog(null as Frame?, "Salva con nome", FileDialog.SAVE)
    d.file = suggested
    d.isVisible = true
    val name = d.file ?: return null
    return File(d.directory, name)
}

fun EditorState.imageInfo(): String? = image?.let { "$fileName (${it.width}×${it.height} px)" }

/** Salva il registro eventi in un file di testo scelto dall'utente. */
fun EditorState.exportLogDialog() {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    val d = FileDialog(null as Frame?, "Esporta registro eventi", FileDialog.SAVE)
    d.file = "magic-touch-remove-log-$stamp.txt"
    d.isVisible = true
    val name = d.file ?: return
    var f = File(d.directory, name)
    if (f.extension.isEmpty()) f = File(f.path + ".txt")
    try {
        AppLog.export(f, imageInfo())
        message = "Registro esportato: ${f.name}"
    } catch (e: Exception) {
        message = "Esportazione non riuscita: ${e.message}"
        AppLog.e("Esportazione del registro non riuscita", e)
    }
}

fun EditorState.openDialog() { pickImageToOpen()?.let { open(it) } }

fun EditorState.saveDialog() {
    if (image == null) return
    val base = fileName.substringBeforeLast('.', fileName)
    pickImageToSave("$base-ripulita.png")?.let { save(it) }
}

// =========================================================================
// Schermata principale
// =========================================================================

@Composable
fun App(st: EditorState) {
    var showAbout by remember { mutableStateOf(false) }

    LaunchedEffect(st.message) {
        if (st.message != null) {
            delay(6000)
            st.message = null
        }
    }

    Column(Modifier.fillMaxSize().background(PsPanel)) {
        MenuBar(st, onAbout = { showAbout = true })
        OptionsBar(st)
        Row(Modifier.weight(1f).fillMaxWidth()) {
            ToolBar(st)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                DocumentTab(st)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    CanvasView(st, Modifier.fillMaxSize())
                    if (st.image == null) EmptyState(st)
                    if (st.busy) BusyOverlay(st.busyText)
                }
            }
            RightPanels(st)
        }
        StatusBar(st)
    }

    if (showAbout) AboutDialog { showAbout = false }
    if (st.showLog) LogDialog(st)
}

// =========================================================================
// Barra dei menu
// =========================================================================

@Composable
private fun MenuBar(st: EditorState, onAbout: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(28.dp).background(PsPanelHeader),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.padding(horizontal = 10.dp)) {
            Text("Ps", color = PsAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Menu("File") { close ->
            Item("Apri…", "Ctrl+O") { close(); st.openDialog() }
            Item("Salva con nome…", "Ctrl+S", st.image != null) { close(); st.saveDialog() }
            Item("Chiudi immagine", "Ctrl+W", st.image != null) { close(); st.close() }
        }
        Menu("Modifica") { close ->
            Item("Annulla", "Ctrl+Z", st.canUndo) { close(); st.undo() }
            Item("Ripeti", "Ctrl+Maiusc+Z", st.canRedo) { close(); st.redo() }
            Item("Deseleziona", "Ctrl+D", st.hasMask) { close(); st.deselect() }
            Item("Cancella con IA", "Invio", st.hasMask && !st.busy) { close(); st.remove() }
        }
        Menu("Visualizza") { close ->
            Item("Adatta alla finestra", "Ctrl+0", st.image != null) { close(); st.fit() }
            Item("Dimensione reale (100%)", "Ctrl+1", st.image != null) { close(); st.actualSize() }
            Item("Ingrandisci", "Ctrl++", st.image != null) { close(); st.zoomAt(1.25f) }
            Item("Riduci", "Ctrl+-", st.image != null) { close(); st.zoomAt(0.8f) }
        }
        Menu("Aiuto") { close ->
            Item("Registro eventi…", "Ctrl+L") { close(); st.showLog = true }
            Item("Informazioni e licenze", "") { close(); onAbout() }
        }
    }
}

@Composable
private fun Menu(title: String, content: @Composable MenuScope.(close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            title,
            color = PsText,
            fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .background(if (open) PsHover else Color.Transparent)
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 5.dp)
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(PsPanel).widthIn(min = 240.dp)
        ) {
            MenuScope.content { open = false }
        }
    }
}

private object MenuScope

@Composable
private fun MenuScope.Item(label: String, shortcut: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        enabled = enabled,
        onClick = onClick,
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, fontSize = 12.sp, color = if (enabled) PsText else PsDim)
                Text(shortcut, fontSize = 12.sp, color = PsDim)
            }
        },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        modifier = Modifier.height(28.dp)
    )
}

// =========================================================================
// Barra delle opzioni (cambia con lo strumento attivo)
// =========================================================================

@Composable
private fun OptionsBar(st: EditorState) {
    Row(
        Modifier.fillMaxWidth().height(38.dp).background(PsBar).border(1.dp, PsBorder).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text(st.tool.label, color = PsText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        Box(Modifier.width(1.dp).height(20.dp).background(PsBorder))

        when (st.tool) {
            Tool.BRUSH, Tool.ERASER, Tool.RESTORE -> {
                PsSlider("Dimensione", st.brushSize, 4f, 300f, "${st.brushSize.toInt()} px", { st.brushSize = it })
                PsSlider("Durezza", st.hardness, 0f, 1f, "${(st.hardness * 100).toInt()}%", { st.hardness = it })
            }
            Tool.SELECT -> Text(
                "Clic su un oggetto, oppure trascina un riquadro",
                color = PsDim, fontSize = 12.sp
            )
            Tool.LASSO -> Text("Disegna un contorno attorno all'oggetto", color = PsDim, fontSize = 12.sp)
            Tool.HAND -> Text("Trascina per spostare · rotellina per lo zoom", color = PsDim, fontSize = 12.sp)
            Tool.ZOOM -> {
                PsButton("Ingrandisci", primary = !st.zoomOut) { st.zoomOut = false }
                PsButton("Riduci", primary = st.zoomOut) { st.zoomOut = true }
                PsButton("Adatta schermo", enabled = st.image != null) { st.fit() }
                PsButton("100%", enabled = st.image != null) { st.actualSize() }
                Text("Clic per ingrandire · Alt+clic per ridurre · trascina per zoom su un riquadro", color = PsDim, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.weight(1f))

        PsSlider("Espansione", st.expand, 0f, 15f, "${st.expand.toInt()} px", { st.expand = it })

        Row(
            Modifier.clip(RoundedCornerShape(3.dp)).clickable { st.auto = !st.auto }.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(14.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (st.auto) PsAccent else Color(0xFF1F1F1F))
                    .border(1.dp, PsBorder),
                contentAlignment = Alignment.Center
            ) {
                if (st.auto) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(11.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text("AUTO", color = PsText, fontSize = 12.sp)
        }

        PsButton("Confronta", enabled = st.image != null, modifier = Modifier.pointerInput(Unit) {
            detectTapGestures(onPress = {
                st.comparing = true
                tryAwaitRelease()
                st.comparing = false
            })
        })
        PsButton("Deseleziona", enabled = st.hasMask) { st.deselect() }
        PsButton("Cancella", primary = true, enabled = st.hasMask && !st.busy) { st.remove() }
    }
}

@Composable
fun PsButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val bg = if (!enabled) Color(0xFF3A3A3A) else if (primary) PsAccent else Color(0xFF4A4A4A)
    var m = modifier.clip(RoundedCornerShape(3.dp)).background(bg)
    if (onClick != null && enabled) m = m.clickable { onClick() }
    Text(
        text,
        color = if (enabled) Color.White else PsDim,
        fontSize = 12.sp,
        modifier = m.padding(horizontal = 12.dp, vertical = 5.dp)
    )
}

// =========================================================================
// Barra degli strumenti (a sinistra)
// =========================================================================

private fun iconOf(t: Tool): ImageVector = when (t) {
    Tool.SELECT -> Icons.Filled.AutoFixHigh
    Tool.BRUSH -> Icons.Filled.Brush
    Tool.LASSO -> Icons.Filled.Gesture
    Tool.ERASER -> Icons.Filled.Backspace
    Tool.RESTORE -> Icons.Filled.Restore
    Tool.HAND -> Icons.Filled.PanTool
    Tool.ZOOM -> Icons.Filled.ZoomIn
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolBar(st: EditorState) {
    Column(
        Modifier.width(44.dp).fillMaxHeight().background(PsPanel).border(1.dp, PsBorder).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        for (t in Tool.values()) {
            val selected = st.tool == t
            TooltipArea(
                tooltip = {
                    Text(
                        "${t.label} (${t.key})",
                        color = PsText, fontSize = 12.sp,
                        modifier = Modifier.background(PsPanelHeader).border(1.dp, PsBorder).padding(6.dp)
                    )
                },
                delayMillis = 500
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (selected) PsSelected else Color.Transparent)
                        .clickable { st.tool = t },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(iconOf(t), t.label, tint = if (selected) Color.White else PsText, modifier = Modifier.size(20.dp))
                }
            }
            if (t == Tool.RESTORE) {
                Box(Modifier.width(26.dp).height(1.dp).background(PsBorder))
            }
        }
    }
}

// =========================================================================
// Scheda del documento e stato vuoto
// =========================================================================

@Composable
private fun DocumentTab(st: EditorState) {
    Row(
        Modifier.fillMaxWidth().height(28.dp).background(PsPanelHeader),
        verticalAlignment = Alignment.Bottom
    ) {
        if (st.image != null) {
            val img = st.image!!
            Row(
                Modifier.height(26.dp).background(PsCanvas).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${st.fileName} @ ${(st.zoom * 100).toInt()}% (${img.width}×${img.height})",
                    color = PsText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun EmptyState(st: EditorState) {
    val scope = rememberCoroutineScope()
    val hasInpaint = ModelManager.activeOption(Kind.INPAINT) != null
    val hasSelect = ModelManager.activeOption(Kind.SELECT) != null
    val downloading = ModelManager.progress.isNotEmpty()
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Magic Touch Remove", color = PsText, fontSize = 22.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(6.dp))
        Text("Rimuovi oggetti, persone e ombre dalle foto con l'IA, tutto sul tuo PC.", color = PsDim, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))
        PsButton("Apri una foto…  (Ctrl+O)", primary = true) { st.openDialog() }
        if (!hasInpaint || !hasSelect) {
            Spacer(Modifier.height(14.dp))
            if (downloading) {
                Text("Download dei modelli in corso…", color = PsDim, fontSize = 12.sp)
            } else {
                PsButton("Scarica i modelli consigliati (LaMa + MobileSAM, circa 236 MB)") {
                    scope.launch {
                        if (!hasInpaint) ModelManager.download(OPT_LAMA)
                        if (!hasSelect) ModelManager.download(OPT_MOBILESAM)
                    }
                }
            }
        }
    }
}

@Composable
private fun BusyOverlay(text: String) {
    Box(
        Modifier.fillMaxSize().background(Color(0x66000000)).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.clip(RoundedCornerShape(6.dp)).background(PsPanelHeader).border(1.dp, PsBorder).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text, color = PsText, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                modifier = Modifier.width(220.dp),
                color = PsAccent,
                trackColor = Color(0xFF1F1F1F)
            )
        }
    }
}

// =========================================================================
// Pannelli a destra
// =========================================================================

@Composable
private fun RightPanels(st: EditorState) {
    Column(
        Modifier.width(290.dp).fillMaxHeight().background(PsPanel).border(1.dp, PsBorder)
    ) {
        Panel("Proprietà", Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PsSlider(
                    "Opacità maschera", st.maskOpacity, 0.1f, 1f,
                    "${(st.maskOpacity * 100).toInt()}%", { st.maskOpacity = it }
                )
                val img = st.image
                Text(
                    if (img != null) "Documento: ${img.width} × ${img.height} px" else "Nessun documento aperto",
                    color = PsDim, fontSize = 12.sp
                )
            }
        }
        Panel("Modelli IA", Modifier.fillMaxWidth().weight(1f)) { ModelsPanel() }
        Panel("Storia", Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp)) { HistoryPanel(st) }
    }
}

@Composable
private fun Panel(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.border(0.5.dp, PsBorder)) {
        Text(
            title.uppercase(),
            color = PsDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth().background(PsPanelHeader).padding(horizontal = 10.dp, vertical = 6.dp)
        )
        Box(Modifier.weight(1f, fill = false)) { content() }
    }
}

@Composable
private fun ModelsPanel() {
    val scope = rememberCoroutineScope()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ModelGroup("Rimozione oggetti", Kind.INPAINT, scope)
        ModelGroup("Selezione automatica", Kind.SELECT, scope)
        Text(
            "I modelli vengono scaricati una volta sola in ${ModelManager.dir.path}",
            color = PsDim, fontSize = 11.sp
        )
    }
}

@Composable
private fun ModelGroup(title: String, kind: Kind, scope: kotlinx.coroutines.CoroutineScope) {
    Text(title, color = PsText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    for (opt in ALL_OPTIONS.filter { it.kind == kind }) {
        val installed = ModelManager.isInstalled(opt)
        val active = installed && ModelManager.activeOption(kind)?.id == opt.id
        val prog = ModelManager.progress[opt.id]
        val err = ModelManager.errors[opt.id]
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                .background(if (active) Color(0xFF3B4A5C) else Color(0xFF3A3A3A))
                .border(1.dp, if (active) PsAccent else PsBorder, RoundedCornerShape(4.dp))
                .padding(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(opt.title, color = PsText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("${formatSize(opt.totalSize)} · ${opt.license}", color = PsDim, fontSize = 11.sp)
            }
            Text(opt.desc, color = PsDim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
            if (prog != null) {
                LinearProgressIndicator(
                    progress = { prog },
                    modifier = Modifier.fillMaxWidth(),
                    color = PsAccent, trackColor = Color(0xFF1F1F1F)
                )
                Text("${(prog * 100).toInt()}%", color = PsDim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            } else if (!installed) {
                PsButton("Scarica") { scope.launch { ModelManager.download(opt) } }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (active) Text("In uso", color = PsAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    else PsButton("Usa") { ModelManager.setActive(opt) }
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.Filled.Delete, "Elimina", tint = PsDim,
                        modifier = Modifier.size(18.dp).clickable { ModelManager.delete(opt) }
                    )
                }
            }
            if (err != null) Text(err, color = PsError, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun HistoryPanel(st: EditorState) {
    val listState = rememberLazyListState()
    LaunchedEffect(st.states.size, st.pos) {
        if (st.pos >= 0 && st.pos < st.states.size) listState.animateScrollToItem(st.pos)
    }
    if (st.states.isEmpty()) {
        Text("Apri una foto per iniziare", color = PsDim, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
        return
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
        itemsIndexed(st.states) { i, s ->
            val current = i == st.pos
            val future = i > st.pos
            Text(
                s.label,
                color = if (future) PsDim else PsText,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth()
                    .background(if (current) PsAccent else Color.Transparent)
                    .clickable { st.goTo(i) }
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

// =========================================================================
// Barra di stato
// =========================================================================

@Composable
private fun StatusBar(st: EditorState) {
    Row(
        Modifier.fillMaxWidth().height(24.dp).background(PsPanelHeader).border(0.5.dp, PsBorder).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text("${(st.zoom * 100).toInt()}%", color = PsDim, fontSize = 11.sp, modifier = Modifier.width(50.dp))
        val img = st.image
        if (img != null) Text("${img.width} × ${img.height} px", color = PsDim, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        val msg = st.message
        if (msg != null) Text(msg, color = PsText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        else if (st.busy) Text(st.busyText, color = PsAccent, fontSize = 11.sp)
        else Text(modelsSummary(), color = PsDim, fontSize = 11.sp)
        Text(
            "Registro", color = PsDim, fontSize = 11.sp,
            modifier = Modifier.clickable { st.showLog = true }
        )
    }
}

private fun modelsSummary(): String {
    val a = ModelManager.activeOption(Kind.INPAINT)?.title ?: "nessun modello"
    val b = ModelManager.activeOption(Kind.SELECT)?.title ?: "nessun modello"
    return "Rimozione: $a · Selezione: $b"
}

// =========================================================================
// Informazioni e licenze
// =========================================================================

@Composable
private fun AboutDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = PsPanel,
        title = { Text("Magic Touch Remove per Windows", color = PsText, fontSize = 16.sp) },
        text = {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Rimozione di oggetti dalle foto con l'IA. Tutto gira sul tuo PC: le foto non vengono mai inviate a nessun server.",
                        color = PsText, fontSize = 12.sp
                    )
                    Text("Software e modelli usati:", color = PsText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "• LaMa (Apache-2.0, Samsung AI Center)\n• MI-GAN (MIT, Picsart AI Research)\n" +
                            "• MobileSAM (MIT)\n• SAM 2 Tiny (Apache-2.0, Meta)\n• ONNX Runtime (MIT, Microsoft)\n" +
                            "• Kotlin e Compose Multiplatform (Apache-2.0, JetBrains)",
                        color = PsDim, fontSize = 12.sp
                    )
                    Text("Scorciatoie: B pennello · L lasso · W selezione · E gomma · R ripristina · H mano · [ ] dimensione · Invio cancella", color = PsDim, fontSize = 11.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Chiudi", color = PsAccent) } }
    )
}

// =========================================================================
// Registro eventi
// =========================================================================

@Composable
private fun LogDialog(st: EditorState) {
    val rev = AppLog.revision   // legge lo stato osservabile, così la lista si aggiorna da sola
    val entries = remember(rev) { AppLog.snapshot() }
    val listState = rememberLazyListState()
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.size - 1)
    }
    Dialog(onDismissRequest = { st.showLog = false }) {
        Column(
            Modifier.size(width = 840.dp, height = 540.dp)
                .clip(RoundedCornerShape(6.dp)).background(PsPanel)
                .border(1.dp, PsBorder, RoundedCornerShape(6.dp))
        ) {
            Row(
                Modifier.fillMaxWidth().background(PsPanelHeader).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Registro eventi", color = PsText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("${entries.size} righe", color = PsDim, fontSize = 12.sp)
            }
            SelectionContainer(Modifier.weight(1f).fillMaxWidth().background(PsCanvas)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    items(entries) { e ->
                        Text(
                            e.line(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = when (e.level) {
                                "ERRORE" -> PsError
                                "AVVISO" -> Color(0xFFE0B040)
                                else -> PsText
                            },
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PsButton("Esporta in .txt…", primary = true) { st.exportLogDialog() }
                PsButton("Copia tutto") {
                    try {
                        Toolkit.getDefaultToolkit().systemClipboard
                            .setContents(StringSelection(AppLog.asText(st.imageInfo())), null)
                        st.message = "Registro copiato negli appunti"
                    } catch (e: Exception) {
                        st.message = "Impossibile copiare: ${e.message}"
                    }
                }
                PsButton("Cancella") { AppLog.clear(); AppLog.i("Registro cancellato") }
                Spacer(Modifier.weight(1f))
                PsButton("Chiudi") { st.showLog = false }
            }
        }
    }
}
