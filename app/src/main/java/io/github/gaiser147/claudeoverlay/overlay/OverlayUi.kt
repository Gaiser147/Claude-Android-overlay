package io.github.gaiser147.claudeoverlay.overlay

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.rememberMarkdownState
import io.github.gaiser147.claudeoverlay.R
import io.github.gaiser147.claudeoverlay.cli.ModelInfo
import io.github.gaiser147.claudeoverlay.ui.theme.LocalOverlayColors

/** Window-level actions the Compose UI can't do itself (it doesn't own the WindowManager). */
interface OverlayWindowActions {
    fun moveBy(dx: Float, dy: Float)
    fun resizeBy(dx: Float, dy: Float)
    fun expand()
    fun collapse()
    fun pickFiles()
    fun openApp()
}

/** The collapsed orb: tap to open, drag to move, ✓ when a reply finished while tucked away. */
@Composable
fun Bubble(state: ChatUiState, actions: OverlayWindowActions) {
    val c = LocalOverlayColors.current
    val pulse = rememberInfiniteTransition(label = "busy")
    val busyAlpha by pulse.animateFloat(
        initialValue = 1f, targetValue = 0.55f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "busyAlpha",
    )
    Box(
        Modifier
            .size(BUBBLE_DP.dp)
            .padding(4.dp)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(c.accent)
            .alpha(if (state.busy) busyAlpha else 1f)
            .pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); actions.moveBy(drag.x, drag.y) } }
            .clickable { actions.expand() },
        contentAlignment = Alignment.Center,
    ) {
        Text("✳", color = c.onAccent, fontSize = 26.sp)
        if (state.doneWhileCollapsed) {
            Box(
                Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(c.text),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Check, null, tint = c.bg, modifier = Modifier.size(13.dp)) }
        }
    }
}

@Composable
fun ChatPanel(state: ChatUiState, controller: ChatController, actions: OverlayWindowActions) {
    val c = LocalOverlayColors.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(6.dp)
            .shadow(10.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(c.bg)
            .border(1.dp, c.border, RoundedCornerShape(16.dp)),
    ) {
        var picker by remember { mutableStateOf(false) }
        Header(state, controller, actions, onPickModel = { picker = !picker })
        HorizontalDivider(color = c.border)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Transcript(state, controller)
            if (picker) ModelPicker(state, onPick = { controller.setModel(it); picker = false })
        }
        if (state.needsLogin) LoginBanner(controller, actions)
        Composer(state, controller, actions)
    }
}

@Composable
private fun Header(
    state: ChatUiState,
    controller: ChatController,
    actions: OverlayWindowActions,
    onPickModel: () -> Unit,
) {
    val c = LocalOverlayColors.current
    var moreMenu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); actions.moveBy(drag.x, drag.y) } }
            .padding(start = 12.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("✳", color = c.accent, fontSize = 18.sp)
        Spacer(Modifier.width(6.dp))
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onPickModel).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                ModelInfo.label(state.models, state.servedModel ?: state.model),
                color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Default.KeyboardArrowDown, null, tint = c.muted, modifier = Modifier.size(16.dp))
        }
        if (state.contextTokens > 0) {
            Text("${state.contextTokens / 1000}k", color = c.faint, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { controller.setScreenshots(!state.screenshots) }, enabled = state.canCapture) {
            Icon(
                Icons.Default.Screenshot, stringResource(R.string.toggle_screenshots),
                tint = if (state.screenshots && state.canCapture) c.accent else c.faint,
            )
        }
        Box {
            IconButton(onClick = { moreMenu = true }) { Icon(Icons.Default.MoreVert, null, tint = c.muted) }
            DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.new_conversation)) }, onClick = {
                    moreMenu = false; controller.newConversation()
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.attach_files)) }, onClick = {
                    moreMenu = false; actions.pickFiles()
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.open_app)) }, onClick = {
                    moreMenu = false; actions.openApp()
                })
            }
        }
        IconButton(onClick = actions::collapse) { Icon(Icons.Default.Close, stringResource(R.string.collapse), tint = c.muted) }
    }
}

/**
 * The model menu, laid over the transcript: the CLI's own list for this login, each with its
 * current version and concrete id, plus a field for any other model id (e.g. a pinned older
 * version). An in-panel sheet rather than a popup, so the id field can take the keyboard.
 */
@Composable
private fun ModelPicker(state: ChatUiState, onPick: (String) -> Unit) {
    val c = LocalOverlayColors.current
    var custom by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
    ) {
        Text(
            stringResource(R.string.model), color = c.muted, fontSize = 12.sp,
            modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
        )
        state.models.forEach { m ->
            val selected = m.value == state.model
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) c.toolBg else c.bg)
                    .clickable { onPick(m.value) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.title, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        if (m.displayName != m.title && m.value == "default") {
                            Text("  · ${m.displayName}", color = c.muted, fontSize = 12.sp)
                        }
                    }
                    m.resolvedModel?.let {
                        Text(it, color = c.faint, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    if (m.subtitle.isNotEmpty()) Text(m.subtitle, color = c.muted, fontSize = 12.sp)
                }
                if (selected) Icon(Icons.Default.Check, null, tint = c.accent, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(R.string.model_custom), color = c.muted, fontSize = 12.sp,
            modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            BasicTextField(
                value = custom,
                onValueChange = { custom = it.trim() },
                singleLine = true,
                textStyle = TextStyle(color = c.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onPick(custom) }),
                decorationBox = { inner ->
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp)).background(c.field)
                            .border(1.dp, c.border, RoundedCornerShape(8.dp)).padding(8.dp),
                    ) {
                        if (custom.isEmpty()) Text("claude-opus-4-8", color = c.faint, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        inner()
                    }
                },
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onPick(custom) }, enabled = custom.isNotEmpty()) {
                Text(stringResource(R.string.model_use))
            }
        }
    }
}

@Composable
private fun Transcript(state: ChatUiState, controller: ChatController) {
    val c = LocalOverlayColors.current
    val list = rememberLazyListState()
    // The list is laid out bottom-up (reverseLayout), so the newest item is index 0 and stays
    // pinned to the bottom edge while it grows: following the stream needs no scrolling at all,
    // and a reader who scrolled up keeps their place. Only a new message of our own jumps down.
    val newestIsUser = state.items.lastOrNull() is ChatItem.User
    LaunchedEffect(state.items.size) {
        if (newestIsUser) list.scrollToItem(0)
    }
    if (state.items.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.empty_title), color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.empty_hint), color = c.muted, fontSize = 13.sp)
            if (state.resumableSession != null) {
                Spacer(Modifier.size(12.dp))
                TextButton(onClick = controller::resumeLast) { Text(stringResource(R.string.resume_last)) }
            }
        }
        return
    }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        reverseLayout = true,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
    ) {
        if (state.busy && state.items.lastOrNull() !is ChatItem.Assistant) {
            item(key = "thinking") { Text("…", color = c.muted, fontSize = 18.sp) }
        }
        items(state.items.asReversed(), key = { it.key }) { item ->
            when (item) {
                is ChatItem.User -> UserCard(item)
                is ChatItem.Assistant -> SelectionContainer { StreamingMarkdown(item.text) }
                is ChatItem.Tool -> ToolChip(item)
                is ChatItem.Notice -> Text(
                    item.text,
                    color = if (item.isError) c.err else c.muted,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Markdown that never blanks out while the text grows. The renderer re-parses on every change
 * and passes through a Loading state in between; drawn as-is, every streamed token flashed the
 * whole reply. So only Success states are drawn, and the previous one stays up until the next
 * parse is done (plain text until the very first one is).
 */
@Composable
private fun StreamingMarkdown(text: String) {
    val parsed by rememberMarkdownState(text, immediate = true).state.collectAsState()
    // A plain holder, not snapshot state: `parsed` changing already recomposes this.
    val lastSuccess = remember { arrayOfNulls<State.Success>(1) }
    (parsed as? State.Success)?.let { lastSuccess[0] = it }
    val ready = lastSuccess[0]
    if (ready != null) Markdown(ready, modifier = Modifier.fillMaxWidth())
    else Text(text, color = LocalOverlayColors.current.text, fontSize = 14.sp, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun UserCard(item: ChatItem.User) {
    val c = LocalOverlayColors.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (item.text.isNotBlank()) {
            SelectionContainer {
                Text(
                    item.text, color = c.text, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(c.userCard).padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        item.note?.let { Text(it, color = c.faint, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp)) }
    }
}

@Composable
private fun ToolChip(item: ChatItem.Tool) {
    val c = LocalOverlayColors.current
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(c.toolBg).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (item.status) {
            ToolStatus.Running -> Text("●", color = c.accent, fontSize = 10.sp)
            ToolStatus.Done -> Icon(Icons.Default.Check, null, tint = c.muted, modifier = Modifier.size(12.dp))
            ToolStatus.Failed -> Icon(Icons.Default.Error, null, tint = c.err, modifier = Modifier.size(12.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(item.name, color = c.text, fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
        if (item.summary.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(item.summary, color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LoginBanner(controller: ChatController, actions: OverlayWindowActions) {
    val c = LocalOverlayColors.current
    Row(
        Modifier.fillMaxWidth().background(c.toolBg).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.needs_login), color = c.err, fontSize = 12.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = { actions.openApp(); controller.recheckLogin() }) { Text(stringResource(R.string.sign_in)) }
    }
}

@Composable
private fun Composer(state: ChatUiState, controller: ChatController, actions: OverlayWindowActions) {
    val c = LocalOverlayColors.current
    var text by rememberSaveable { mutableStateOf("") }
    val send = {
        if (!state.busy) {
            controller.send(text)
            text = ""
        }
    }
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        if (state.pendingFiles.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "📎 " + state.pendingFiles.joinToString(", "), color = c.muted, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = controller::clearFiles) { Text(stringResource(R.string.remove)) }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(c.field)
                .border(1.dp, c.border, RoundedCornerShape(14.dp))
                .padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = actions::pickFiles) { Icon(Icons.Default.AttachFile, stringResource(R.string.attach_files), tint = c.muted) }
            Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                if (text.isEmpty()) {
                    Text(
                        stringResource(if (state.screenshots && state.canCapture) R.string.input_hint_screen else R.string.input_hint),
                        color = c.faint, fontSize = 14.sp,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = TextStyle(color = c.text, fontSize = 14.sp),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 20.dp),
                )
            }
            if (state.busy) {
                IconButton(onClick = controller::stop) { Icon(Icons.Default.Stop, stringResource(R.string.stop), tint = c.accent) }
            } else {
                IconButton(onClick = send) {
                    Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send), tint = c.accent)
                }
            }
        }
        // Corner grip for resizing the panel.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                "◢", color = c.faint, fontSize = 12.sp,
                modifier = Modifier
                    .pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); actions.resizeBy(drag.x, drag.y) } }
                    .padding(start = 16.dp, top = 2.dp),
            )
        }
    }
}

const val BUBBLE_DP = 60
