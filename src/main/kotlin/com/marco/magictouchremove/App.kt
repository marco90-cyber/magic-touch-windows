package com.marco.magictouchremove

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.ui.DragData
import androidx.compose.ui.onExternalDrag
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

fun EditorState.openDialog() { pickImageToOpen()?.let { requestOpen(it) } }

fun EditorState.saveDialog() { if (image != null) showExport = true }

// =========================================================================
// Schermata principale
// =========================================================================

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun App(st: EditorState) {
    var showAbout by remember { mutableStateOf(false) }

    LaunchedEffect(st.message) {
        if (st.message != null) {
            delay(6000)
            st.message = null
        }
    }

    LaunchedEffect(st.showExport, st.busy) {
        // salvataggio annullato: non aprire più il file in attesa
        if (!st.showExport && !st.busy) st.pendingOpen = null
    }

    Column(
        Modifier.fillMaxSize().background(PsPanel)
            .onExternalDrag(
                onDragStart = { st.dropping = true },
                onDragExit = { st.dropping = false },
                onDrop = { v ->
                    st.dropping = false
                    val d = v.dragData
                    if (d is DragData.FilesList) {
                        val f = d.readFiles().firstNotNullOfOrNull { u ->
                            runCatching { File(java.net.URI(u)) }.getOrNull()
                                ?.takeIf { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "bmp", "gif") }
                        }
                        if (f != null) st.requestOpen(f) else st.message = "Trascina un file immagine (JPG, PNG, BMP, GIF)"
                    }
                }
            )
    ) {
        MenuBar(st, onAbout = { showAbout = true })
        OptionsBar(st)
        UpdateBanner(st)
        Row(Modifier.weight(1f).fillMaxWidth()) {
            ToolBar(st)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                DocumentTab(st)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    CanvasView(st, Modifier.fillMaxSize())
                    ContextMenu(st)
                    if (st.image == null) EmptyState(st)
                    if (st.busy) BusyOverlay(st.busyText, if (st.cancellable) ({ st.cancelJob() }) else null)
                }
            }
            RightPanels(st)
        }
        StatusBar(st)
    }

    if (showAbout) AboutDialog { showAbout = false }
    if (st.dropping) Box(
        Modifier.fillMaxSize().background(Color(0x552D8CEB)).border(3.dp, PsAccent),
        contentAlignment = Alignment.Center
    ) { Text("Rilascia qui la foto per aprirla", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
    st.pendingOpen?.let { UnsavedDialog(st, it) }
    if (st.showLog) LogDialog(st)
    if (st.crashText != null) CrashDialog(st)
    if (st.showGenerate) GenerateDialog(st)
    if (st.showExport) ExportDialog(st)
    if (st.showSysInfo) SysInfoDialog(st)
    if (Updater.dialogOpen) UpdateDialog(st)
}

// =========================================================================
// Barra dei menu
// =========================================================================

@Composable
private fun MenuBar(st: EditorState, onAbout: () -> Unit) {
    val scope = rememberCoroutineScope()
    Row(
        Modifier.fillMaxWidth().height(28.dp).background(PsPanelHeader),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = AppIcon, contentDescription = "Magic Touch Remove",
            modifier = Modifier.padding(horizontal = 8.dp).size(18.dp)
        )
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
        Menu("IA") { close ->
            Item("Genera con descrizione…", "", st.hasMask && !st.busy) { close(); st.showGenerate = true }
            Item("Ricostruisci la selezione (generativa)", "", st.hasMask && !st.busy) { close(); st.generate("", 20, 7.5f) }
            Item("Ingrandisci la foto (Real-ESRGAN)", "", st.image != null && !st.busy) { close(); st.upscale() }
            Item("Rimuovi sfondo", "", st.image != null && !st.busy) { close(); st.removeBackground() }
        }
        Menu("Visualizza") { close ->
            Item("Adatta alla finestra", "Ctrl+0", st.image != null) { close(); st.fit() }
            Item("Dimensione reale (100%)", "Ctrl+1", st.image != null) { close(); st.actualSize() }
            Item("Ingrandisci", "Ctrl++", st.image != null) { close(); st.zoomAt(1.25f) }
            Item("Riduci", "Ctrl+-", st.image != null) { close(); st.zoomAt(0.8f) }
        }
        Menu(if (Updater.status == UpdateStatus.AVAILABLE) "Aggiornamenti ●" else "Aggiornamenti") { close ->
            Item("Controlla aggiornamenti…", "") { close(); scope.launch { Updater.check(true) } }
            if (Updater.status == UpdateStatus.AVAILABLE) {
                Item("Installa la versione ${Updater.info?.name ?: ""}…", "") { close(); Updater.dialogOpen = true }
            }
            Item((if (Updater.autoCheck) "✓  " else "     ") + "Controlla all'avvio", "") { close(); Updater.changeAutoCheck(!Updater.autoCheck) }
            Item("Pagina delle versioni (GitHub)", "") { close(); Updater.openReleasesPage() }
        }
        Menu("Aiuto") { close ->
            Item("Registro eventi…", "Ctrl+L") { close(); st.showLog = true }
            Item("Informazioni sistema…", "") { close(); st.showSysInfo = true }
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
        Image(painter = AppIcon, contentDescription = null, modifier = Modifier.size(96.dp))
        Spacer(Modifier.height(12.dp))
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
private fun BusyOverlay(text: String, onCancel: (() -> Unit)? = null) {
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
            if (onCancel != null) {
                Spacer(Modifier.height(14.dp))
                PsButton("Annulla  (Esc)") { onCancel() }
            }
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
    var confirmDelete by remember { mutableStateOf(false) }
    val used = remember(ModelManager.revision) { ModelManager.folderBytes() }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ModelGroup("Rimozione oggetti", Kind.INPAINT, scope)
        ModelGroup("Selezione automatica", Kind.SELECT, scope)
        ModelGroup("IA generativa (riempi con testo)", Kind.GENERATE, scope)
        ModelGroup("Ingrandimento foto", Kind.UPSCALE, scope)
        ModelGroup("Rimozione sfondo", Kind.BGREMOVE, scope)
        var boostOn by remember { mutableStateOf(DetailBoost.enabled) }
        Row(
            Modifier.clip(RoundedCornerShape(3.dp)).clickable { boostOn = !boostOn; DetailBoost.enabled = boostOn }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(14.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (boostOn) PsAccent else Color(0xFF1F1F1F)).border(1.dp, PsBorder),
                contentAlignment = Alignment.Center
            ) { if (boostOn) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(11.dp)) }
            Spacer(Modifier.width(6.dp))
            Text("Migliora i dettagli della ricostruzione", color = PsText, fontSize = 12.sp)
        }
        Text(
            if (DetailBoost.available) "Usa il modello di ingrandimento (Real-ESRGAN): risultato più nitido, un po' più lento"
            else "Scarica un modello «Ingrandimento foto» qui sopra per attivarlo",
            color = PsDim, fontSize = 11.sp
        )
        var grainV by remember { mutableFloatStateOf(TextureMatch.grain) }
        var sharpV by remember { mutableFloatStateOf(TextureMatch.sharp) }
        PsSlider("Grana", grainV, 0f, 2f, "${(grainV * 100).toInt()}%", { grainV = (it * 20).roundToInt() / 20f; TextureMatch.grain = grainV }, trackWidth = 120.dp)
        PsSlider("Nitidezza", sharpV, 0f, 1f, "${(sharpV * 100).toInt()}%", { sharpV = (it * 20).roundToInt() / 20f; TextureMatch.sharp = sharpV }, trackWidth = 120.dp)
        Text("Grana e nitidezza si adattano alla foto, per togliere l'effetto liscio della ricostruzione", color = PsDim, fontSize = 11.sp)
        var gpuOn by remember { mutableStateOf(Gpu.enabled) }
        Row(
            Modifier.clip(RoundedCornerShape(3.dp)).clickable { gpuOn = !gpuOn; Gpu.enabled = gpuOn }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(14.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (gpuOn) PsAccent else Color(0xFF1F1F1F)).border(1.dp, PsBorder),
                contentAlignment = Alignment.Center
            ) { if (gpuOn) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(11.dp)) }
            Spacer(Modifier.width(6.dp))
            Text("Usa la GPU (DirectML)", color = PsText, fontSize = 12.sp)
        }
        if (Gpu.adapters.isNotEmpty()) {
            Text("Scheda video da usare (NVIDIA, AMD e Intel):", color = PsDim, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                var dev by remember { mutableIntStateOf(Gpu.device) }
                PsButton("Auto", primary = dev < 0) { dev = -1; Gpu.device = -1 }
                for (ad in Gpu.adapters.filter { !it.software }) {
                    PsButton("#${ad.index}", primary = dev == ad.index) { dev = ad.index; Gpu.device = ad.index }
                }
            }
            for (ad in Gpu.adapters.filter { !it.software }) {
                Text("#${ad.index}  ${ad.name} · ${ad.vramMb} MB", color = PsDim, fontSize = 10.sp)
            }
        }
        Text(
            when {
                !Gpu.available -> "GPU non inclusa in questa installazione: calcoli sulla CPU"
                !gpuOn -> "GPU disattivata: calcoli sulla CPU"
                Gpu.active -> "GPU attiva"
                else -> "GPU pronta: verrà usata al prossimo caricamento di un modello"
            },
            color = PsDim, fontSize = 11.sp
        )
        Text("Spazio usato dai modelli: ${formatBytes(used)}", color = PsDim, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PsButton("Apri cartella") { ModelManager.openFolder() }
            PsButton("Elimina modelli", enabled = used > 0) { confirmDelete = true }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = PsPanel,
            title = { Text("Eliminare i modelli?", color = PsText, fontSize = 15.sp) },
            text = {
                Text(
                    "Verranno cancellati tutti i modelli IA scaricati (${formatBytes(used)}). " +
                        "Per usare di nuovo l'app dovrai scaricarli un'altra volta.",
                    color = PsDim, fontSize = 12.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; ModelManager.deleteAll() }) { Text("Elimina", color = PsError) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annulla", color = PsText) } }
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
                    Text("Versione $APP_VERSION", color = PsText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${entries.size} righe", color = PsDim, fontSize = 12.sp)
                    PsButton("Salva in .txt", primary = true) { st.exportLogDialog() }
                    PsButton("Copia") { copyLog(st) }
                    PsButton("Cancella") { AppLog.clear(); AppLog.i("Registro cancellato") }
                    PsButton("Chiudi") { st.showLog = false }
                }
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
                PsButton("Copia tutto") { copyLog(st) }
                PsButton("Cancella") { AppLog.clear(); AppLog.i("Registro cancellato") }
                Spacer(Modifier.weight(1f))
                PsButton("Chiudi") { st.showLog = false }
            }
        }
    }
}

@Composable
private fun UnsavedDialog(st: EditorState, next: File) {
    Dialog(onDismissRequest = { st.pendingOpen = null }) {
        Column(
            Modifier.width(480.dp).clip(RoundedCornerShape(6.dp)).background(PsPanel)
                .border(1.dp, PsBorder, RoundedCornerShape(6.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Aprire una nuova immagine?", color = PsText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "«${st.fileName}» ha modifiche non salvate. Prima di aprire «${next.name}» cosa vuoi fare?",
                color = PsDim, fontSize = 12.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PsButton("Salva…", primary = true) { st.showExport = true }
                PsButton("Chiudi senza salvare") { st.pendingOpen = null; st.open(next) }
                Spacer(Modifier.weight(1f))
                PsButton("Annulla") { st.pendingOpen = null }
            }
        }
    }
}

@Composable
private fun GenerateDialog(st: EditorState) {
    var prompt by remember { mutableStateOf(Prefs.get("genPrompt", "")) }
    var steps by remember { mutableFloatStateOf(20f) }
    var guidance by remember { mutableFloatStateOf(7.5f) }
    val installed = ModelManager.isInstalled(OPT_SD2_INPAINT)
    Dialog(onDismissRequest = { st.showGenerate = false }) {
        Column(
            Modifier.width(560.dp).clip(RoundedCornerShape(6.dp)).background(PsPanel)
                .border(1.dp, PsBorder, RoundedCornerShape(6.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Genera con l'IA", color = PsText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Descrivi cosa deve comparire nella zona selezionata (funziona meglio in inglese, es. \u00abgreen bush\u00bb). " +
                    "Se lasci vuoto, l'IA ricostruisce lo sfondo togliendo l'oggetto.",
                color = PsDim, fontSize = 12.sp
            )
            Box(Modifier.fillMaxWidth().background(PsCanvas).border(1.dp, PsBorder).padding(8.dp)) {
                androidx.compose.foundation.text.BasicTextField(
                    value = prompt, onValueChange = { prompt = it.take(300) },
                    textStyle = androidx.compose.ui.text.TextStyle(color = PsText, fontSize = 13.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(PsText),
                    modifier = Modifier.fillMaxWidth()
                )
                if (prompt.isEmpty()) Text("Descrizione (facoltativa)", color = PsDim, fontSize = 13.sp)
            }
            PsSlider("Passi", steps, 10f, 40f, "${steps.toInt()}", { steps = it.roundToInt().toFloat() }, trackWidth = 260.dp)
            PsSlider("Aderenza", guidance, 1f, 12f, "%.1f".format(guidance), { guidance = (it * 2).roundToInt() / 2f }, trackWidth = 260.dp)
            Text(
                if (Gpu.active) "GPU attiva." else "Sulla CPU può richiedere diversi minuti; con la GPU è molto più veloce. Più passi = più qualità e più tempo.",
                color = PsDim, fontSize = 11.sp
            )
            if (!installed) Text("Prima scarica il modello «Stable Diffusion 2 Inpainting» dal pannello Modelli IA (circa 5 GB).", color = PsError, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                PsButton("Annulla") { st.showGenerate = false }
                PsButton("Genera", primary = true, enabled = installed && st.hasMask && !st.busy) {
                    Prefs.set("genPrompt", prompt)
                    st.showGenerate = false
                    st.generate(prompt, steps.toInt(), guidance)
                }
            }
        }
    }
}

private fun copyLog(st: EditorState) {
    try {
        Toolkit.getDefaultToolkit().systemClipboard
            .setContents(StringSelection(AppLog.asText(st.imageInfo())), null)
        st.message = "Registro copiato negli appunti"
    } catch (e: Exception) {
        st.message = "Impossibile copiare: ${e.message}"
    }
}

@Composable
private fun CrashDialog(st: EditorState) {
    val text = st.crashText ?: return
    Dialog(onDismissRequest = { st.crashText = null }) {
        Column(
            Modifier.width(520.dp).clip(RoundedCornerShape(6.dp)).background(PsPanel)
                .border(1.dp, PsError, RoundedCornerShape(6.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Si è verificato un errore", color = PsError, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(text, color = PsText, fontSize = 12.sp)
            Text("Vuoi salvare il registro eventi per capire cosa è successo?", color = PsDim, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PsButton("Salva registro…", primary = true) { st.exportLogDialog() }
                PsButton("Copia") { copyLog(st) }
                PsButton("Vedi registro") { st.crashText = null; st.showLog = true }
                Spacer(Modifier.weight(1f))
                PsButton("Chiudi") { st.crashText = null }
            }
        }
    }
}

// =========================================================================
// Salva con nome: formato, risoluzione, qualità
// =========================================================================

@Composable
private fun ExportDialog(st: EditorState) {
    val img = st.image
    if (img == null) { st.showExport = false; return }

    var format by remember { mutableStateOf(ExportFormat.fromId(Prefs.get("exportFormat", "png"))) }
    var scale by remember {
        val saved = Prefs.get("exportScale", "1").toFloatOrNull() ?: 1f
        mutableFloatStateOf(EXPORT_SCALES.minByOrNull { kotlin.math.abs(it - saved) } ?: 1f)
    }
    var quality by remember { mutableIntStateOf((Prefs.get("exportQuality", "95").toIntOrNull() ?: 95).coerceIn(80, 100)) }
    var estimate by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var fitPreview by remember { mutableStateOf(true) }
    val (w, h) = scaledSize(img, scale)

    // anteprima e peso stimato: si ricalcolano a ogni cambio di impostazioni (la qualità conta solo per il JPEG)
    LaunchedEffect(format, scale, if (format.lossy) quality else 0, img) {
        estimate = null
        delay(300)
        val result = withContext(Dispatchers.Default) {
            runCatching {
                val bytes = encodeImage(img, ExportOptions(format, quality, scale))
                bytes.size.toLong() to ImageIO.read(ByteArrayInputStream(bytes))
            }.getOrNull()
        }
        estimate = result?.let { formatBytes(it.first) } ?: "non disponibile"
        preview = result?.second?.toComposeImageBitmap()
    }

    Dialog(onDismissRequest = { st.showExport = false }) {
        Column(
            Modifier.width(980.dp).clip(RoundedCornerShape(6.dp)).background(PsPanel)
                .border(1.dp, PsBorder, RoundedCornerShape(6.dp))
        ) {
            Row(
                Modifier.fillMaxWidth().background(PsPanelHeader).padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Salva con nome", color = PsText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                PsButton("Chiudi") { st.showExport = false }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // ---- impostazioni
                Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Formato", color = PsDim, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (f in ExportFormat.values()) PsButton(f.label, primary = f == format) { format = f }
                        }
                        Text(format.hint, color = PsDim, fontSize = 11.sp)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Risoluzione", color = PsDim, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (sc in EXPORT_SCALES) PsButton(scaleLabel(sc), primary = sc == scale) { scale = sc }
                        }
                        Text("$w × $h px", color = PsText, fontSize = 12.sp)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Qualità", color = PsDim, fontSize = 12.sp)
                        if (format.lossy) {
                            PsSlider("", quality.toFloat(), 80f, 100f, "$quality%", { quality = it.roundToInt().coerceIn(80, 100) }, trackWidth = 290.dp)
                        } else {
                            Text("Non serve: ${format.label} non perde qualità.", color = PsDim, fontSize = 12.sp)
                        }
                    }
                    Text("Peso stimato: " + (estimate ?: "calcolo in corso…"), color = PsText, fontSize = 12.sp)
                }

                // ---- anteprima di come verrà salvata l'immagine
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Anteprima", color = PsDim, fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        PsButton("Intera", primary = fitPreview) { fitPreview = true }
                        PsButton("100%", primary = !fitPreview) { fitPreview = false }
                    }
                    Box(Modifier.fillMaxWidth().height(380.dp).background(PsCanvas).border(1.dp, PsBorder)) {
                        val bmp = preview
                        if (bmp == null) {
                            Text("Preparazione dell'anteprima…", color = PsDim, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center))
                        } else {
                            Canvas(Modifier.fillMaxSize()) {
                                if (fitPreview) {
                                    val sc = minOf(size.width / bmp.width, size.height / bmp.height, 1f)
                                    val dw = maxOf(1, (bmp.width * sc).roundToInt())
                                    val dh = maxOf(1, (bmp.height * sc).roundToInt())
                                    drawImage(
                                        image = bmp, srcOffset = IntOffset.Zero, srcSize = IntSize(bmp.width, bmp.height),
                                        dstOffset = IntOffset(((size.width - dw) / 2).roundToInt(), ((size.height - dh) / 2).roundToInt()),
                                        dstSize = IntSize(dw, dh), filterQuality = FilterQuality.Medium
                                    )
                                } else {
                                    // 1 pixel dell'immagine = 1 pixel dello schermo: si vedono i dettagli e la compressione
                                    val cw = minOf(bmp.width, size.width.toInt())
                                    val ch = minOf(bmp.height, size.height.toInt())
                                    drawImage(
                                        image = bmp,
                                        srcOffset = IntOffset((bmp.width - cw) / 2, (bmp.height - ch) / 2), srcSize = IntSize(cw, ch),
                                        dstOffset = IntOffset(((size.width - cw) / 2).roundToInt(), ((size.height - ch) / 2).roundToInt()),
                                        dstSize = IntSize(cw, ch), filterQuality = FilterQuality.None
                                    )
                                }
                            }
                        }
                        if (estimate == null && bmp != null) {
                            Text("aggiornamento…", color = PsAccent, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopStart).padding(8.dp))
                        }
                    }
                    Text(
                        (if (fitPreview) "Intera" else "Dettaglio al 100% (centro)") + " · $w × $h px · " + format.label +
                            (if (format.lossy) " $quality%" else ""),
                        color = PsDim, fontSize = 11.sp
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PsButton("Annulla") { st.showExport = false }
                PsButton("Salva…", primary = true) {
                    Prefs.set("exportFormat", format.id)
                    Prefs.set("exportScale", scale.toString())
                    Prefs.set("exportQuality", quality.toString())
                    val base = st.fileName.substringBeforeLast('.', st.fileName)
                    val chosen = pickImageToSave("$base-ripulita.${format.ext}")
                    if (chosen != null) {
                        val file = if (format.accepts(chosen.extension)) chosen else File(chosen.path + "." + format.ext)
                        st.showExport = false
                        st.save(file, ExportOptions(format, quality, scale))
                    }
                }
            }
        }
    }
}

// =========================================================================
// Aggiornamenti
// =========================================================================

/** Barra di avviso sotto la barra delle opzioni, quando c'è una nuova versione. */
@Composable
private fun UpdateBanner(st: EditorState) {
    val scope = rememberCoroutineScope()
    val i = Updater.info
    if (Updater.status != UpdateStatus.AVAILABLE || i == null || Updater.bannerDismissed || Updater.dialogOpen) return
    Row(
        Modifier.fillMaxWidth().height(32.dp).background(Color(0xFF1F4E79)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("È disponibile la versione ${i.name} di Magic Touch Remove", color = Color.White, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        PsButton("Scarica e installa", primary = true) {
            Updater.dialogOpen = true
            scope.launch { Updater.downloadAndInstall { st.exitApp() } }
        }
        PsButton("Dettagli") { Updater.dialogOpen = true }
        Text(
            "✕", color = Color.White, fontSize = 14.sp,
            modifier = Modifier.clickable { Updater.bannerDismissed = true }.padding(horizontal = 6.dp)
        )
    }
}

@Composable
private fun UpdateDialog(st: EditorState) {
    val scope = rememberCoroutineScope()
    val status = Updater.status
    val info = Updater.info
    Dialog(onDismissRequest = { if (status != UpdateStatus.DOWNLOADING) Updater.dialogOpen = false }) {
        Column(
            Modifier.width(540.dp).clip(RoundedCornerShape(6.dp)).background(PsPanel)
                .border(1.dp, PsBorder, RoundedCornerShape(6.dp))
        ) {
            Text(
                "Aggiornamenti", color = PsText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().background(PsPanelHeader).padding(horizontal = 14.dp, vertical = 9.dp)
            )
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Versione installata: $APP_VERSION", color = PsDim, fontSize = 12.sp)
                when (status) {
                    UpdateStatus.IDLE, UpdateStatus.CHECKING -> {
                        Text("Controllo degli aggiornamenti…", color = PsText, fontSize = 13.sp)
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = PsAccent, trackColor = Color(0xFF1F1F1F))
                    }
                    UpdateStatus.UP_TO_DATE -> Text("Hai già l'ultima versione.", color = PsText, fontSize = 13.sp)
                    UpdateStatus.AVAILABLE -> if (info != null) {
                        Text("Disponibile la versione ${info.name}", color = PsText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Aggiornamento dei file · " + formatBytes(info.size),
                            color = PsDim, fontSize = 12.sp
                        )
                        if (info.notes.isNotEmpty()) {
                            Text("Novità", color = PsDim, fontSize = 12.sp)
                            Box(Modifier.fillMaxWidth().heightIn(max = 140.dp).background(PsCanvas).padding(8.dp)) {
                                Text(info.notes, color = PsText, fontSize = 12.sp, modifier = Modifier.verticalScroll(rememberScrollState()))
                            }
                        }
                        Text(
                            "Si aggiornano solo i file del programma: modelli e impostazioni restano. L'app si chiude e si riapre da sola.",
                            color = PsDim, fontSize = 11.sp
                        )
                    }
                    UpdateStatus.DOWNLOADING -> {
                        Text("Download in corso… ${(Updater.progress * 100).toInt()}%", color = PsText, fontSize = 13.sp)
                        LinearProgressIndicator(
                            progress = { Updater.progress }, modifier = Modifier.fillMaxWidth(),
                            color = PsAccent, trackColor = Color(0xFF1F1F1F)
                        )
                    }
                    UpdateStatus.READY -> Text(Updater.message, color = PsText, fontSize = 13.sp)
                    UpdateStatus.ERROR -> {
                        Text("Operazione non riuscita", color = PsError, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(Updater.error ?: "", color = PsDim, fontSize = 12.sp)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (status == UpdateStatus.AVAILABLE && info != null) {
                    PsButton("Più tardi") { Updater.dialogOpen = false }
                    PsButton("Scarica e installa", primary = true) {
                        scope.launch { Updater.downloadAndInstall { st.exitApp() } }
                    }
                } else if (status == UpdateStatus.ERROR) {
                    PsButton("Pagina delle versioni") { Updater.openReleasesPage() }
                    PsButton("Riprova", primary = true) { scope.launch { Updater.check(true) } }
                    PsButton("Chiudi") { Updater.dialogOpen = false }
                } else if (status != UpdateStatus.DOWNLOADING) {
                    PsButton("Chiudi", primary = status != UpdateStatus.CHECKING) { Updater.dialogOpen = false }
                }
            }
        }
    }
}

// =========================================================================
// Menu del tasto destro nella tela: stesse funzioni della barra delle opzioni
// =========================================================================

@Composable
private fun MenuRow(label: String, shortcut: String = "", enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(28.dp)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp, color = if (enabled) PsText else PsDim)
        Text(shortcut, fontSize = 12.sp, color = PsDim)
    }
}

@Composable
private fun MenuSeparator() {
    Box(Modifier.padding(vertical = 3.dp).fillMaxWidth().height(1.dp).background(PsBorder))
}

/** Pannello disegnato sopra la tela (non un popup, così non si chiude da solo). */
@Composable
private fun ContextMenu(st: EditorState) {
    val pos = st.ctxPos ?: return
    var size by remember { mutableStateOf(IntSize.Zero) }
    val close = { st.ctxPos = null }

    // un clic fuori dal pannello lo chiude
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false).consume()
                st.ctxPos = null
                waitForUpOrCancellation()
            }
        }
    )

    val x = pos.x.roundToInt().coerceIn(0, maxOf(0, st.viewWidth.roundToInt() - size.width))
    val y = pos.y.roundToInt().coerceIn(0, maxOf(0, st.viewHeight.roundToInt() - size.height))
    Column(
        Modifier
            .offset { IntOffset(x, y) }
            .width(300.dp)
            .onSizeChanged { size = it }
            .clip(RoundedCornerShape(4.dp))
            .background(PsPanel)
            .border(1.dp, PsBorder, RoundedCornerShape(4.dp))
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(vertical = 4.dp)
    ) {
        Text(
            st.tool.label.uppercase(), color = PsDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 6.dp)
        )

        // --- parte che cambia con lo strumento (come la barra in alto)
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (st.tool) {
                Tool.BRUSH, Tool.ERASER, Tool.RESTORE -> {
                    PsSlider("Dimensione", st.brushSize, 4f, 300f, "${st.brushSize.toInt()} px", { st.brushSize = it })
                    PsSlider("Durezza", st.hardness, 0f, 1f, "${(st.hardness * 100).toInt()}%", { st.hardness = it })
                }
                Tool.SELECT -> Text("Clic su un oggetto, oppure trascina un riquadro", color = PsDim, fontSize = 11.sp)
                Tool.LASSO -> Text("Disegna un contorno attorno all'oggetto", color = PsDim, fontSize = 11.sp)
                Tool.HAND -> Text("Trascina per spostare · rotellina per lo zoom", color = PsDim, fontSize = 11.sp)
                Tool.ZOOM -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PsButton("Ingrandisci", primary = !st.zoomOut) { st.zoomOut = false }
                        PsButton("Riduci", primary = st.zoomOut) { st.zoomOut = true }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PsButton("Adatta schermo") { close(); st.fit() }
                        PsButton("100%") { close(); st.actualSize() }
                    }
                }
            }
            // --- parte uguale per tutti gli strumenti: espansione, AUTO e pulsanti
            PsSlider("Espansione", st.expand, 0f, 15f, "${st.expand.toInt()} px", { st.expand = it })
            Row(
                Modifier.clip(RoundedCornerShape(3.dp)).clickable { st.auto = !st.auto }.padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(14.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (st.auto) PsAccent else Color(0xFF1F1F1F)).border(1.dp, PsBorder),
                    contentAlignment = Alignment.Center
                ) {
                    if (st.auto) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(11.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text("AUTO (cancella appena finisci di selezionare)", color = PsText, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PsButton("Confronta", modifier = Modifier.pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        st.comparing = true
                        tryAwaitRelease()
                        st.comparing = false
                    })
                })
                PsButton("Deseleziona", enabled = st.hasMask) { close(); st.deselect() }
                PsButton("Cancella", primary = true, enabled = st.hasMask && !st.busy) { close(); st.remove() }
            }
        }

        MenuSeparator()
        MenuRow("Genera con descrizione…", "", st.hasMask && !st.busy) { close(); st.showGenerate = true }
        MenuRow("Seleziona tutto", "Ctrl+A", !st.busy) { close(); st.selectAll() }
        MenuRow("Inverti selezione", "", !st.busy) { close(); st.invertSelection() }
        MenuSeparator()
        MenuRow("Annulla", "Ctrl+Z", st.canUndo) { close(); st.undo() }
        MenuRow("Ripeti", "Ctrl+Maiusc+Z", st.canRedo) { close(); st.redo() }
        MenuSeparator()
        MenuRow("Adatta alla finestra", "Ctrl+0") { close(); st.fit() }
        MenuRow("100%", "Ctrl+1") { close(); st.actualSize() }
    }
}

// =========================================================================
// Informazioni sistema
// =========================================================================

@Composable
private fun SysInfoDialog(st: EditorState) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { SysInfo.refresh() }
    Dialog(onDismissRequest = { st.showSysInfo = false }) {
        Column(
            Modifier.size(width = 860.dp, height = 580.dp).clip(RoundedCornerShape(6.dp)).background(PsPanel)
                .border(1.dp, PsBorder, RoundedCornerShape(6.dp))
        ) {
            Row(
                Modifier.fillMaxWidth().background(PsPanelHeader).padding(horizontal = 14.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Informazioni sistema", color = PsText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (SysInfo.loading) Text("Rilevamento schede video in corso…", color = PsAccent, fontSize = 12.sp)
                    PsButton("Chiudi") { st.showSysInfo = false }
                }
            }
            if (SysInfo.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = PsAccent, trackColor = Color(0xFF1F1F1F))
            SelectionContainer(Modifier.weight(1f).fillMaxWidth().background(PsCanvas)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    for (sec in SysInfo.sections) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(sec.title, color = PsAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            for (r in sec.rows) {
                                Row {
                                    Text(r.label, color = PsDim, fontSize = 12.sp, modifier = Modifier.width(260.dp))
                                    Text(r.value, color = PsText, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PsButton("Esporta in .txt…", primary = true, enabled = SysInfo.sections.isNotEmpty()) {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
                    val d = FileDialog(null as Frame?, "Esporta informazioni sistema", FileDialog.SAVE)
                    d.file = "magic-touch-remove-sistema-$stamp.txt"
                    d.isVisible = true
                    val name = d.file
                    if (name != null) {
                        var f = File(d.directory, name)
                        if (f.extension.isEmpty()) f = File(f.path + ".txt")
                        try {
                            f.writeText(SysInfo.asText(), Charsets.UTF_8)
                            st.message = "Informazioni esportate: ${f.name}"
                        } catch (e: Exception) {
                            st.message = "Esportazione non riuscita: ${e.message}"
                        }
                    }
                }
                PsButton("Copia tutto", enabled = SysInfo.sections.isNotEmpty()) {
                    try {
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(SysInfo.asText()), null)
                        st.message = "Informazioni copiate negli appunti"
                    } catch (e: Exception) {
                        st.message = "Impossibile copiare: ${e.message}"
                    }
                }
                PsButton("Aggiorna", enabled = !SysInfo.loading) { scope.launch { SysInfo.refresh(force = true) } }
                Spacer(Modifier.weight(1f))
                PsButton("Chiudi") { st.showSysInfo = false }
            }
        }
    }
}
