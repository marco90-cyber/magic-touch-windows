package com.marco.magictouchremove

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.rememberTrayState
import kotlinx.coroutines.delay
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    val scope = rememberCoroutineScope()
    val st = remember {
        AppLog.trimFileOnStart()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            AppLog.e("Errore non gestito nel thread «${t.name}»", e)
            prev?.uncaughtException(t, e)
        }
        AppLog.i("Avvio di Magic Touch Remove $APP_VERSION · ${System.getProperty("os.name")} ${System.getProperty("os.version")} · Java ${System.getProperty("java.version")}")
        AppLog.i("Modelli installati: " + (ALL_OPTIONS.filter { ModelManager.isInstalled(it) }.joinToString(", ") { it.title }.ifEmpty { "nessuno" }))
        EditorState(scope)
    }
    remember { st.exitApp = ::exitApplication }

    // controllo automatico all'avvio (se attivo) dopo qualche secondo
    LaunchedEffect(Unit) {
        delay(4000)
        if (Updater.autoCheck) Updater.check(false)
    }

    // notifica di Windows quando c'è un aggiornamento
    val trayState = rememberTrayState()
    val upd = Updater.info
    if (Updater.status == UpdateStatus.AVAILABLE && upd != null) {
        Tray(
            icon = AppIcon,
            state = trayState,
            tooltip = "Magic Touch Remove: aggiornamento ${upd.name} disponibile",
            onAction = { Updater.dialogOpen = true }
        )
        LaunchedEffect(upd.build) {
            delay(800)
            trayState.sendNotification(
                Notification(
                    "Aggiornamento disponibile",
                    "È disponibile la versione ${upd.name} di Magic Touch Remove. Apri il menu Aggiornamenti per installarla.",
                    Notification.Type.Info
                )
            )
        }
    }

    val winState = rememberWindowState(size = DpSize(1360.dp, 860.dp), position = WindowPosition(Alignment.Center))

    val title = if (st.image != null) "${st.fileName} — Magic Touch Remove" else "Magic Touch Remove"

    Window(
        onCloseRequest = ::exitApplication,
        title = title,
        icon = AppIcon,
        state = winState,
        onKeyEvent = { e ->
            if (e.type != KeyEventType.KeyDown) return@Window false
            val ctrl = e.isCtrlPressed
            when {
                ctrl && e.key == Key.O -> { st.openDialog(); true }
                ctrl && e.key == Key.S -> { st.saveDialog(); true }
                ctrl && e.key == Key.W -> { st.close(); true }
                ctrl && e.key == Key.Z && e.isShiftPressed -> { st.redo(); true }
                ctrl && e.key == Key.Y -> { st.redo(); true }
                ctrl && e.key == Key.Z -> { st.undo(); true }
                ctrl && e.key == Key.D -> { st.deselect(); true }
                ctrl && e.key == Key.A -> { st.selectAll(); true }
                ctrl && e.key == Key.L -> { st.showLog = !st.showLog; true }
                ctrl && e.key == Key.Zero -> { st.fit(); true }
                ctrl && e.key == Key.One -> { st.actualSize(); true }
                ctrl && (e.key == Key.Equals || e.key == Key.Plus) -> { st.zoomAt(1.25f); true }
                ctrl && e.key == Key.Minus -> { st.zoomAt(0.8f); true }
                ctrl -> false
                e.key == Key.Enter -> { st.remove(); true }
                e.key == Key.W -> { st.tool = Tool.SELECT; true }
                e.key == Key.B -> { st.tool = Tool.BRUSH; true }
                e.key == Key.L -> { st.tool = Tool.LASSO; true }
                e.key == Key.E -> { st.tool = Tool.ERASER; true }
                e.key == Key.R -> { st.tool = Tool.RESTORE; true }
                e.key == Key.H -> { st.tool = Tool.HAND; true }
                e.key == Key.Z -> { st.tool = Tool.ZOOM; true }
                e.key == Key.LeftBracket -> { st.brushSize = (st.brushSize - 6f).coerceAtLeast(4f); true }
                e.key == Key.RightBracket -> { st.brushSize = (st.brushSize + 6f).coerceAtMost(300f); true }
                else -> false
            }
        }
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = PsAccent,
                surface = PsPanel,
                background = PsPanel,
                onSurface = PsText,
                onBackground = PsText,
            )
        ) {
            CompositionLocalProvider(LocalTextStyle provides TextStyle(color = PsText, fontSize = 12.sp)) {
                App(st)
            }
        }
    }
}
