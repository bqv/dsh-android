package uk.xa0.dsh.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import uk.xa0.dsh.DshViewModel
import uk.xa0.dsh.TerminalPhase
import uk.xa0.dsh.TerminalUiState
import uk.xa0.dsh.term.ATTR_BOLD
import uk.xa0.dsh.term.ATTR_DIM
import uk.xa0.dsh.term.ATTR_HIDDEN
import uk.xa0.dsh.term.ATTR_ITALIC
import uk.xa0.dsh.term.ATTR_REVERSE
import uk.xa0.dsh.term.ATTR_STRIKE
import uk.xa0.dsh.term.ATTR_UNDERLINE
import uk.xa0.dsh.term.COLOR_DEFAULT
import uk.xa0.dsh.term.COLOR_RGB_FLAG
import uk.xa0.dsh.term.TerminalEmulator
import uk.xa0.dsh.term.TerminalIssue
import uk.xa0.dsh.term.TerminalKey
import uk.xa0.dsh.term.TerminalKeys
import uk.xa0.dsh.term.terminalIssueFact
import uk.xa0.dsh.ui.theme.DshRadius
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType

/**
 * The session terminal: a real PTY for the open session, rendered natively.
 *
 * No WebView and no xterm.js — the emulator is `uk.xa0.dsh.term`, and this file is
 * its only renderer. The grid is drawn on one `Canvas` rather than composed: a
 * 100×40 screen is 4,000 cells, and `htop` repaints most of them several times a
 * second, so a composable per cell would be thousands of nodes per frame.
 *
 * The screen is read *in place* from the view model's [TerminalEmulator], and
 * [TerminalUiState.revision] is what invalidates the draw. That is safe because the
 * emulator is only ever mutated from the view model's main-dispatcher collector —
 * the same thread this Canvas draws on.
 */
@Composable
fun TerminalScreen(
    vm: DshViewModel,
    sessionId: String?,
    modifier: Modifier = Modifier,
) {
    val state by vm.terminal.collectAsState()
    val emulator = vm.terminalScreen()

    LaunchedEffect(sessionId) { vm.enterTerminal(sessionId) }
    // Leaving the tab detaches the *attachment*, not the process: the host keeps the
    // shell alive by design, and this is the whole of "leaving must not close it".
    DisposableEffectOnLeave(sessionId) { vm.leaveTerminal() }

    Column(modifier.fillMaxWidth().background(DshTheme.colors.bgBase)) {
        TerminalBar(
            state = state,
            onNew = vm::newTerminal,
            onClose = vm::closeActiveTerminal,
            onRetry = vm::retryTerminal,
            onSelect = vm::selectTerminal,
        )
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (emulator != null &&
                (state.phase == TerminalPhase.CONNECTED || state.phase == TerminalPhase.DISCONNECTED)
            ) {
                TerminalSurface(
                    emulator = emulator,
                    revision = state.revision,
                    onWrite = vm::terminalWrite,
                    onResize = vm::terminalResize,
                )
            } else {
                TerminalPlaceholder(state = state, onRetry = vm::retryTerminal)
            }
        }
    }
}

/** A `DisposableEffect` with one knob, so the leave hook reads as what it is. */
@Composable
private fun DisposableEffectOnLeave(key: Any?, onLeave: () -> Unit) {
    androidx.compose.runtime.DisposableEffect(key) {
        onDispose { onLeave() }
    }
}

@Composable
private fun TerminalBar(
    state: TerminalUiState,
    onNew: () -> Unit,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val colors = DshTheme.colors
    Column {
        Row(
            Modifier.fillMaxWidth().height(38.dp).padding(horizontal = DshSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.title.ifBlank { "Terminal" },
                style = DshType.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = terminalPhaseWord(state.phase),
                style = DshType.micro,
                color = if (state.phase == TerminalPhase.CONNECTED) colors.success else colors.labelTertiary,
            )
            Spacer(Modifier.width(DshSpacing.md))
            if (state.phase != TerminalPhase.CONNECTED && state.phase != TerminalPhase.LOADING &&
                state.phase != TerminalPhase.CONNECTING && state.phase != TerminalPhase.CREATING
            ) {
                BarAction("Reconnect", onRetry)
            }
            BarAction("New", onNew)
            BarAction("Close", onClose, enabled = state.active != null)
        }
        // A phone shows one grid, so a session with more than one terminal needs a
        // way back to the others that is not a second pane.
        if (state.terminals.size > 1) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = DshSpacing.md, vertical = DshSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(DshSpacing.sm),
            ) {
                state.terminals.forEach { item ->
                    val active = item.id == state.active?.id
                    Text(
                        text = item.title.ifBlank { item.id.take(8) },
                        style = DshType.micro,
                        color = if (active) colors.link else colors.labelTertiary,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(DshRadius.pill))
                            .background(if (active) colors.active else colors.bgLayer2)
                            .clickableNoRipple { onSelect(item.id) }
                            .padding(horizontal = DshSpacing.md, vertical = DshSpacing.xs),
                    )
                }
            }
        }
        TerminalNotice(state)
    }
}

@Composable
private fun BarAction(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    val colors = DshTheme.colors
    Text(
        text = label,
        style = DshType.micro,
        color = if (enabled) colors.link else colors.labelDimmed,
        modifier = Modifier
            .clip(RoundedCornerShape(DshSpacing.sm))
            .clickableNoRipple(enabled = enabled, onClick = onClick)
            .padding(horizontal = DshSpacing.sm, vertical = DshSpacing.xs),
    )
}

/**
 * The state the reader needs named, and nothing else.
 *
 * An issue is stated as the fact it is — "no live agent, send a prompt" — rather
 * than left to a spinner, because a session with no live agent will never connect
 * on its own.
 */
@Composable
private fun TerminalNotice(state: TerminalUiState) {
    val colors = DshTheme.colors
    val issue = state.issue?.takeIf { it != TerminalIssue.UNKNOWN }
    val message = when {
        issue != null -> terminalIssueFact(issue, state.limit)
        state.error != null -> state.error
        state.phase == TerminalPhase.DISCONNECTED -> "The terminal stream dropped. Reconnecting to the host…"
        state.phase == TerminalPhase.CLOSED -> "The shell was closed."
        state.phase == TerminalPhase.LOADING || state.phase == TerminalPhase.CREATING ->
            "Starting a shell in this session…"
        state.phase == TerminalPhase.CONNECTING -> "Attaching to the shell…"
        else -> null
    } ?: return
    val bad = issue != null || state.error != null || state.phase == TerminalPhase.CLOSED
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (bad) colors.bgLayer2 else colors.bgLayer1)
            .padding(horizontal = DshSpacing.md, vertical = DshSpacing.sm),
    ) {
        Text(
            text = message,
            style = DshType.micro,
            color = if (bad) colors.labelSecondary else colors.labelTertiary,
        )
    }
}

@Composable
private fun TerminalPlaceholder(state: TerminalUiState, onRetry: () -> Unit) {
    val colors = DshTheme.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = when (state.phase) {
                    TerminalPhase.FAILED -> "No terminal."
                    TerminalPhase.CLOSED -> "The shell was closed."
                    TerminalPhase.IDLE -> "No session open."
                    else -> "Connecting…"
                },
                style = DshType.bodySmall,
                color = colors.labelSecondary,
            )
            if (state.phase == TerminalPhase.FAILED || state.phase == TerminalPhase.CLOSED) {
                Spacer(Modifier.height(DshSpacing.md))
                BarAction("Open a terminal", onRetry)
            }
        }
    }
}

/**
 * The grid, the hardware keys and the soft-keyboard path.
 *
 * The panel is measured into a cols×rows grid from the *cell* size, not from a
 * guess: the cell is one 'M' in the monospace face this draws with, measured by the
 * same [TextMeasurer] that lays the rows out, so a column boundary in the model is a
 * column boundary on screen.
 */
@Composable
private fun TerminalSurface(
    emulator: TerminalEmulator,
    revision: Long,
    onWrite: (String) -> Unit,
    onResize: (Int, Int) -> Unit,
) {
    val colors = DshTheme.colors
    val palette = remember { xtermPalette() }
    val measurer = rememberTextMeasurer()
    val baseStyle = remember {
        TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 15.sp)
    }
    val metrics = remember(measurer, baseStyle) {
        measurer.measure(AnnotatedString("MMMMMMMMMM"), style = baseStyle)
    }
    val cellWidth = (metrics.size.width / 10f).toFloat().coerceAtLeast(1f)
    val cellHeight = metrics.size.height.toFloat().coerceAtLeast(1f)

    var ctrlArmed by remember { mutableStateOf(false) }
    var softField by remember { mutableStateOf(TextFieldValue("")) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(colors.bgBase),
        ) {
            val columns = (constraints.maxWidth / cellWidth).toInt().coerceAtLeast(2)
            val rows = (constraints.maxHeight / cellHeight).toInt().coerceAtLeast(1)
            // The host owns the PTY size; the panel only reports its measurement.
            LaunchedEffect(columns, rows) { onResize(columns, rows) }

            Box(
                Modifier
                    .fillMaxSize()
                    // Preview, not `onKeyEvent`: this runs before the hidden text
                    // field, so the keys a terminal owns never reach the IME's
                    // editor, while ordinary letters fall through to it and arrive
                    // as text deltas.
                    .onPreviewKeyEvent { event ->
                        handleHardwareKey(event, emulator.applicationCursorKeys, onWrite)
                    },
            ) {
                Canvas(
                    modifier = Modifier.fillMaxSize(),
                    // The screen is mutated in place, so the draw has to *name* the
                    // revision it draws. Without that key this lambda would be
                    // remembered against an unchanged emulator instance and a
                    // repainting `htop` would freeze on its first frame.
                    onDraw = remember(emulator, revision, cellWidth, cellHeight, palette, baseStyle, measurer) {
                        {
                            drawTerminalGrid(
                                emulator = emulator,
                                measurer = measurer,
                                baseStyle = baseStyle,
                                palette = palette,
                                defaultFg = colors.labelPrimary,
                                defaultBg = colors.bgBase,
                                cellWidth = cellWidth,
                                cellHeight = cellHeight,
                            )
                        }
                    },
                )
                // A soft keyboard cannot produce Esc, Ctrl or the arrows, so it only
                // has to produce text; everything else is the key row below. The
                // field is kept empty and each change is treated as input, which is
                // what makes backspace/interference-free typing possible at all.
                BasicTextField(
                    value = softField,
                    onValueChange = { next ->
                        // The field is emptied on every change, so each callback is
                        // one burst of typing and `next.text` is exactly what the IME
                        // just produced. A shrinking value means the IME's own delete.
                        val previous = softField.text
                        softField = TextFieldValue("")
                        when {
                            next.text.length > previous.length -> {
                                val added = next.text.substring(previous.length)
                                val data = if (ctrlArmed) {
                                    ctrlArmed = false
                                    added.map { TerminalKeys.typed(it, ctrl = true) }.joinToString("")
                                } else {
                                    added
                                }
                                onWrite(data)
                            }
                            next.text.length < previous.length -> {
                                repeat(previous.length - next.text.length) { onWrite("\u007F") }
                            }
                        }
                    },
                    modifier = Modifier
                        .size(1.dp)
                        .alpha(0f)
                        .focusRequester(focusRequester),
                    textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
                    keyboardOptions = KeyboardOptions(autoCorrect = false),
                )
                // Tapping the grid puts the keyboard back after a hardware key or a
                // focus loss; the tap must not be swallowed by the Canvas.
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickableNoRipple { runCatching { focusRequester.requestFocus() } },
                )
            }
        }
        TerminalKeyRow(
            ctrlArmed = ctrlArmed,
            onCtrl = { ctrlArmed = !ctrlArmed },
            onKey = { key -> onWrite(TerminalKeys.key(key, applicationCursorKeys = emulator.applicationCursorKeys)) },
            onWrite = onWrite,
        )
    }
}

/**
 * The keys a soft keyboard cannot type.
 *
 * The brief is the whole point of this row: without Esc, Ctrl and the arrows a
 * terminal on a phone can run `ls` and nothing else. It is not a keyboard — it is
 * the gap between one and a shell.
 */
@Composable
private fun TerminalKeyRow(
    ctrlArmed: Boolean,
    onCtrl: () -> Unit,
    onKey: (TerminalKey) -> Unit,
    onWrite: (String) -> Unit,
) {
    val colors = DshTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.bgLayer1)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = DshSpacing.sm, vertical = DshSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyCap("Esc") { onKey(TerminalKey.ESCAPE) }
        KeyCap("Tab") { onKey(TerminalKey.TAB) }
        KeyCap("^C") { onWrite("\u0003") }
        KeyCap("Ctrl", active = ctrlArmed, onClick = onCtrl)
        KeyCap("\u2190") { onKey(TerminalKey.LEFT) }
        KeyCap("\u2191") { onKey(TerminalKey.UP) }
        KeyCap("\u2193") { onKey(TerminalKey.DOWN) }
        KeyCap("\u2192") { onKey(TerminalKey.RIGHT) }
        KeyCap("PgUp") { onKey(TerminalKey.PAGE_UP) }
        KeyCap("PgDn") { onKey(TerminalKey.PAGE_DOWN) }
        KeyCap("Home") { onKey(TerminalKey.HOME) }
        KeyCap("End") { onKey(TerminalKey.END) }
    }
}

@Composable
private fun KeyCap(label: String, active: Boolean = false, onClick: () -> Unit) {
    val colors = DshTheme.colors
    Text(
        text = label,
        style = DshType.micro,
        color = if (active) colors.inkInverted else colors.labelSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(DshSpacing.sm))
            .background(if (active) colors.link else colors.bgLayer3)
            .clickableNoRipple(onClick = onClick)
            .padding(horizontal = DshSpacing.md, vertical = DshSpacing.sm),
    )
}

/** Maps the keys a terminal owns. Anything else is left to the IME. */
private fun terminalKeyOf(key: Key): TerminalKey? = when (key) {
    Key.DirectionUp -> TerminalKey.UP
    Key.DirectionDown -> TerminalKey.DOWN
    Key.DirectionLeft -> TerminalKey.LEFT
    Key.DirectionRight -> TerminalKey.RIGHT
    Key.MoveHome -> TerminalKey.HOME
    Key.MoveEnd -> TerminalKey.END
    Key.PageUp -> TerminalKey.PAGE_UP
    Key.PageDown -> TerminalKey.PAGE_DOWN
    Key.Insert -> TerminalKey.INSERT
    Key.Delete -> TerminalKey.DELETE
    Key.Tab -> TerminalKey.TAB
    Key.Escape -> TerminalKey.ESCAPE
    Key.Enter, Key.NumPadEnter -> TerminalKey.ENTER
    Key.Backspace -> TerminalKey.BACKSPACE
    Key.F1 -> TerminalKey.F1
    Key.F2 -> TerminalKey.F2
    Key.F3 -> TerminalKey.F3
    Key.F4 -> TerminalKey.F4
    Key.F5 -> TerminalKey.F5
    Key.F6 -> TerminalKey.F6
    Key.F7 -> TerminalKey.F7
    Key.F8 -> TerminalKey.F8
    Key.F9 -> TerminalKey.F9
    Key.F10 -> TerminalKey.F10
    Key.F11 -> TerminalKey.F11
    Key.F12 -> TerminalKey.F12
    else -> null
}

/**
 * True when the event was consumed and must not reach the text field.
 *
 * Ctrl chords arrive two ways depending on the key: as an already-mapped C0 code
 * (Android reports `\u0001` for Ctrl+A) or as the bare letter. Both are handled, so
 * neither a shell's Ctrl+A nor Ctrl+C is lost to the editor's own shortcuts.
 */
private fun handleHardwareKey(
    event: KeyEvent,
    applicationCursorKeys: Boolean,
    write: (String) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    terminalKeyOf(event.key)?.let { key ->
        write(
            TerminalKeys.key(
                key = key,
                ctrl = event.isCtrlPressed,
                alt = event.isAltPressed,
                shift = event.isShiftPressed,
                applicationCursorKeys = applicationCursorKeys,
            ),
        )
        return true
    }
    val code = event.utf16CodePoint
    if (code <= 0) return false
    val character = code.toChar()
    return when {
        event.isCtrlPressed && code in 1..31 -> { write(character.toString()); true }
        event.isCtrlPressed -> TerminalKeys.controlOf(character)?.let { write(it.toString()); true } ?: false
        event.isAltPressed -> { write(TerminalKeys.typed(character, alt = true)); true }
        else -> false
    }
}

/**
 * One frame of the terminal.
 *
 * A row is laid out as a single [AnnotatedString] with one span per style change,
 * and drawn once. The alternative — a text layout per run — is a few hundred
 * layouts per `htop` repaint, far past [TextMeasurer]'s cache; a row at a time is
 * tens, and the colours and attributes still resolve per cell.
 */
private fun DrawScope.drawTerminalGrid(
    emulator: TerminalEmulator,
    measurer: TextMeasurer,
    baseStyle: TextStyle,
    palette: IntArray,
    defaultFg: Color,
    defaultBg: Color,
    cellWidth: Float,
    cellHeight: Float,
) {
    val columns = emulator.columns
    val visibleRows = minOf(emulator.rows, (size.height / cellHeight).toInt() + 1)
    for (row in 0 until visibleRows) {
        val line = emulator.row(row)
        val builder = AnnotatedString.Builder()
        var spanStart = 0
        var current: SpanStyle? = null
        for (column in 0 until columns) {
            val cell = line.cells[column]
            val style = spanStyleOf(cell, palette, defaultFg, defaultBg)
            if (style != current) {
                current?.let { builder.addStyle(it, spanStart, builder.length) }
                current = style
                spanStart = builder.length
            }
            // A width-0 cell is the second half of a wide glyph: it owns its column
            // but contributes no character, or the glyph would be drawn twice.
            if (cell.width != 0) {
                builder.append(if (cell.attrs and ATTR_HIDDEN != 0) " " else cell.text())
            }
        }
        current?.let { builder.addStyle(it, spanStart, builder.length) }
        val layout = measurer.measure(builder.toAnnotatedString(), style = baseStyle, softWrap = false)
        drawText(layout, topLeft = Offset(0f, row * cellHeight))
    }

    if (!emulator.cursorVisible || emulator.cursorRow >= visibleRows) return
    val left = emulator.cursorCol * cellWidth
    val top = emulator.cursorRow * cellHeight
    val cell = emulator.row(emulator.cursorRow).cells[emulator.cursorCol]
    drawRect(color = defaultFg, topLeft = Offset(left, top), size = Size(cellWidth, cellHeight))
    if (cell.width == 0) return
    // The glyph under the block, in the block's own colour, which is what a block
    // cursor looks like everywhere else.
    val layout = measurer.measure(
        AnnotatedString(cell.text()),
        style = baseStyle.copy(color = defaultBg),
        softWrap = false,
    )
    drawText(layout, topLeft = Offset(left, top))
}

/** Resolves one cell's colours and attributes into a span. */
private fun spanStyleOf(
    cell: uk.xa0.dsh.term.TerminalCell,
    palette: IntArray,
    defaultFg: Color,
    defaultBg: Color,
): SpanStyle {
    var fg = resolveColor(cell.fg, defaultFg, palette)
    var bg = resolveColor(cell.bg, defaultBg, palette)
    // Reverse video is how `htop` draws its selected row and its headers, and how
    // a mid-screen program shows a highlighted region.
    if (cell.attrs and ATTR_REVERSE != 0) {
        val swap = fg
        fg = bg
        bg = swap
    }
    if (cell.attrs and ATTR_DIM != 0) fg = fg.copy(alpha = 0.6f)
    return SpanStyle(
        color = fg,
        background = bg,
        fontWeight = if (cell.attrs and ATTR_BOLD != 0) FontWeight.Bold else null,
        fontStyle = if (cell.attrs and ATTR_ITALIC != 0) androidx.compose.ui.text.font.FontStyle.Italic else null,
        textDecoration = when {
            cell.attrs and ATTR_UNDERLINE != 0 -> TextDecoration.Underline
            cell.attrs and ATTR_STRIKE != 0 -> TextDecoration.LineThrough
            else -> null
        },
    )
}

private fun resolveColor(value: Int, default: Color, palette: IntArray): Color = when {
    value == COLOR_DEFAULT -> default
    value and COLOR_RGB_FLAG != 0 -> Color(0xFF000000.toInt() or (value and 0xFFFFFF))
    value in 0..255 -> Color(palette[value])
    else -> default
}

/**
 * The xterm 256-colour table.
 *
 * The 16 base colours are the standard xterm values, not theme colours: a program
 * that asks for "red" means red, and a theme-tinted palette would make `htop`'s
 * meter colours lie. The *default* foreground and background do come from the
 * theme, which is where a terminal normally inherits its look.
 */
private fun xtermPalette(): IntArray {
    val table = IntArray(256)
    intArrayOf(
        0xFF000000.toInt(), 0xFFCD0000.toInt(), 0xFF00CD00.toInt(), 0xFFCDCD00.toInt(),
        0xFF0000EE.toInt(), 0xFFCD00CD.toInt(), 0xFF00CDCD.toInt(), 0xFFE5E5E5.toInt(),
        0xFF7F7F7F.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFFFFFF00.toInt(),
        0xFF5C5CFF.toInt(), 0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(), 0xFFFFFFFF.toInt(),
    ).copyInto(table)
    var index = 16
    for (red in 0 until 6) {
        for (green in 0 until 6) {
            for (blue in 0 until 6) {
                table[index++] = 0xFF000000.toInt() or
                    (cubeLevel(red) shl 16) or (cubeLevel(green) shl 8) or cubeLevel(blue)
            }
        }
    }
    for (step in 0 until 24) {
        val value = 8 + step * 10
        table[232 + step] = 0xFF000000.toInt() or (value shl 16) or (value shl 8) or value
    }
    return table
}

private fun cubeLevel(value: Int): Int = if (value == 0) 0 else 55 + value * 40

private fun terminalPhaseWord(phase: TerminalPhase): String = when (phase) {
    TerminalPhase.IDLE -> ""
    TerminalPhase.LOADING -> "loading"
    TerminalPhase.CREATING -> "starting"
    TerminalPhase.CONNECTING -> "connecting"
    TerminalPhase.CONNECTED -> "running"
    TerminalPhase.DISCONNECTED -> "detached"
    TerminalPhase.CLOSING -> "closing"
    TerminalPhase.CLOSED -> "closed"
    TerminalPhase.FAILED -> "failed"
}
