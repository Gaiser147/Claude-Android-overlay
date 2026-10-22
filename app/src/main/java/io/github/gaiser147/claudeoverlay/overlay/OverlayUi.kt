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
import io.github.gaiser147.claudeoverlay.R
import io.github.gaiser147.claudeoverlay.cli.ClaudeSession
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
        Header(state, controller, actions)
        HorizontalDivider(color = c.border)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Transcript(state, controller)
        }
        if (state.needsLogin) LoginBanner(controller, actions)
        Composer(state, controller, actions)
    }
}

@Composable
private fun Header(state: ChatUiState, controller: ChatController, actions: OverlayWindowActions) {
    val c = LocalOverlayColors.current
    var modelMenu by remember { mutableStateOf(false) }
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
        Box {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { modelMenu = true }.padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.servedModel ?: ClaudeSession.MODELS.firstOrNull { it.second == state.model }?.first ?: state.model,
                    color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Default.KeyboardArrowDown, null, tint = c.muted, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                ClaudeSession.MODELS.forEach { (label, alias) ->
                    DropdownMenuItem(
                        text = { Text(label + if (alias == state.model) "  ✓" else "") },
                        onClick = { modelMenu = false; controller.setModel(alias) },
                    )
                }
            }
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

@Composable
private fun Transcript(state: ChatUiState, controller: ChatController) {
    val c = LocalOverlayColors.current
    val list = rememberLazyListState()
    // Follow the stream while the reader is at the bottom; leave them alone if they scrolled up.
    val lastText = (state.items.lastOrNull() as? ChatItem.Assistant)?.text?.length ?: 0
    LaunchedEffect(state.items.size, lastText) {
        val atBottom = !list.canScrollForward || list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 >= state.items.size - 2
        if (atBottom && state.items.isNotEmpty()) list.scrollToItem(state.items.size - 1, Int.MAX_VALUE / 2)
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
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.items, key = { it.key }) { item ->
            when (item) {
                is ChatItem.User -> UserCard(item)
                is ChatItem.Assistant -> SelectionContainer {
                    Markdown(content = item.text, modifier = Modifier.fillMaxWidth())
                }
                is ChatItem.Tool -> ToolChip(item)
                is ChatItem.Notice -> Text(
                    item.text,
                    color = if (item.isError) c.err else c.muted,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (state.busy && state.items.lastOrNull() !is ChatItem.Assistant) {
            item(key = "thinking") { Text("…", color = c.muted, fontSize = 18.sp) }
        }
    }
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
