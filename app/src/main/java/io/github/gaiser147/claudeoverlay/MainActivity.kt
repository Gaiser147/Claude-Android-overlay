package io.github.gaiser147.claudeoverlay

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.gaiser147.claudeoverlay.access.ControlService
import io.github.gaiser147.claudeoverlay.cli.AuthManager.LoginState
import io.github.gaiser147.claudeoverlay.overlay.OverlayService
import io.github.gaiser147.claudeoverlay.stylus.Stylus
import io.github.gaiser147.claudeoverlay.ui.theme.ClaudeOverlayTheme
import io.github.gaiser147.claudeoverlay.ui.theme.LocalOverlayColors
import kotlinx.coroutines.launch

/**
 * One-time setup, in order: overlay permission → notifications → download the CLI →
 * sign in → start the overlay (with or without screen capture).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ClaudeOverlayTheme { SetupScreen() } }
    }
}

@Composable
private fun SetupScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val graph = remember { AppGraph.get(context) }
    val scope = rememberCoroutineScope()
    val c = LocalOverlayColors.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var canNotify by remember { mutableStateOf(notificationsAllowed(context)) }
    var canRecord by remember { mutableStateOf(micAllowed(context)) }
    var unrestricted by remember { mutableStateOf(batteryUnrestricted(context)) }
    var crash by remember { mutableStateOf(CrashLog.latest()) }
    var cliVersion by remember { mutableStateOf(graph.installer.installedVersion()) }
    var download by remember { mutableStateOf<Float?>(null) }
    var cliError by remember { mutableStateOf<String?>(null) }
    var loggedIn by remember { mutableStateOf<Boolean?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val login by graph.auth.state.collectAsState()
    var withScreenshots by remember { mutableStateOf(true) }
    val accessibility by ControlService.instance.collectAsState()
    val stylus by Stylus.state.collectAsState()

    // Permissions are granted in Settings, so re-check whenever we come back.
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            Stylus.refresh()
            canOverlay = Settings.canDrawOverlays(context)
            canNotify = notificationsAllowed(context)
            canRecord = micAllowed(context)
            unrestricted = batteryUnrestricted(context)
            crash = CrashLog.latest()
            refresh++
        }
    }
    LaunchedEffect(cliVersion, refresh, login) {
        if (cliVersion != null && login !is LoginState.AwaitingCode && login !is LoginState.Verifying) {
            loggedIn = runCatching { graph.auth.isLoggedIn() }.getOrDefault(false)
        }
    }
    LaunchedEffect(login) {
        val s = login
        if (s is LoginState.AwaitingCode) openUrl(context, s.url)
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { canNotify = it }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { canRecord = it }
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.data != null && r.resultCode == android.app.Activity.RESULT_OK) {
            OverlayService.start(context, r.resultCode, r.data)
        } else {
            OverlayService.start(context)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.app_name), color = c.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.setup_intro), color = c.muted, fontSize = 14.sp)

        crash?.let { report ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.toolBg).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.crash_title), color = c.err, fontWeight = FontWeight.Medium)
                Text(report.lines().take(6).joinToString("\n"), color = c.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
                        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text(stringResource(R.string.crash_share)) }
                    TextButton(onClick = { CrashLog.clear(); crash = null }) { Text(stringResource(R.string.crash_dismiss)) }
                }
            }
        }

        Step(1, stringResource(R.string.step_overlay), done = canOverlay) {
            Button(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())
                )
            }) { Text(stringResource(R.string.grant)) }
        }

        Step(2, stringResource(R.string.step_notifications), done = canNotify) {
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }) { Text(stringResource(R.string.grant)) }
        }

        Step(3, stringResource(R.string.step_microphone), done = canRecord) {
            Button(onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }) { Text(stringResource(R.string.grant)) }
        }

        Step(4, stringResource(R.string.step_cli), done = cliVersion != null && download == null, keepOpen = true,
            detail = cliVersion?.let { stringResource(R.string.cli_installed, it) }) {
            val progress = download
            if (progress != null && progress >= 0f) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } else if (progress != null) {
                // No Content-Length: show how much has arrived instead (progress = -bytes).
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("${(-progress / 1_048_576).toInt()} MB", color = c.muted, fontSize = 13.sp)
            } else {
                Text(stringResource(R.string.cli_explain), color = c.muted, fontSize = 13.sp)
                Button(onClick = {
                    cliError = null
                    download = 0f
                    scope.launch {
                        try {
                            val release = graph.installer.latest()
                            graph.installer.install(release) { done, total ->
                                download = if (total > 0) done.toFloat() / total else -done.toFloat()
                            }
                            cliVersion = graph.installer.installedVersion()
                        } catch (e: Exception) {
                            cliError = e.message ?: e.toString()
                        } finally {
                            download = null
                        }
                    }
                }) { Text(stringResource(if (cliVersion == null) R.string.download else R.string.update)) }
            }
            cliError?.let { Text(it, color = c.err, fontSize = 13.sp) }
        }

        Step(5, stringResource(R.string.step_login), done = loggedIn == true, enabled = cliVersion != null) {
            when (val s = login) {
                is LoginState.AwaitingCode -> {
                    var code by remember { mutableStateOf("") }
                    Text(stringResource(R.string.login_paste), color = c.muted, fontSize = 13.sp)
                    OutlinedTextField(value = code, onValueChange = { code = it }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.login_code)) })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { graph.auth.submitCode(code) }, enabled = code.isNotBlank()) {
                            Text(stringResource(R.string.submit))
                        }
                        OutlinedButton(onClick = { openUrl(context, s.url) }) { Text(stringResource(R.string.reopen_browser)) }
                        TextButton(onClick = graph.auth::cancel) { Text(stringResource(R.string.cancel)) }
                    }
                }
                LoginState.Starting, LoginState.Verifying ->
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                is LoginState.Failed -> {
                    Text(s.message, color = c.err, fontSize = 13.sp)
                    Button(onClick = graph.auth::startLogin) { Text(stringResource(R.string.sign_in)) }
                }
                LoginState.Idle, LoginState.Success ->
                    if (loggedIn != true) Button(onClick = graph.auth::startLogin) { Text(stringResource(R.string.sign_in)) }
            }
        }

        Step(6, stringResource(R.string.step_accessibility), done = accessibility != null,
            detail = if (accessibility != null) stringResource(R.string.accessibility_on) else null) {
            Text(stringResource(R.string.accessibility_explain), color = c.muted, fontSize = 13.sp)
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text(stringResource(R.string.open_settings)) }
        }

        Step(7, stringResource(R.string.step_battery), done = unrestricted) {
            Text(stringResource(R.string.battery_explain), color = c.muted, fontSize = 13.sp)
            Button(onClick = {
                runCatching {
                    // Sideloaded, not on Play: a background assistant is exactly what this exemption is for.
                    @SuppressLint("BatteryLife")
                    val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
                    context.startActivity(ask)
                }
            }) { Text(stringResource(R.string.grant)) }
        }

        Step(8, stringResource(R.string.step_stylus), done = stylus == Stylus.State.Ready,
            detail = if (stylus == Stylus.State.Ready) stringResource(R.string.stylus_ready) else stringResource(R.string.optional)) {
            Text(stringResource(R.string.stylus_explain), color = c.muted, fontSize = 13.sp)
            when (stylus) {
                Stylus.State.NotInstalled -> Button(onClick = {
                    openUrl(context, "https://play.google.com/store/apps/details?id=${Stylus.SHIZUKU_PACKAGE}")
                }) { Text(stringResource(R.string.stylus_install)) }
                Stylus.State.NotRunning -> {
                    Text(stringResource(R.string.stylus_start_hint), color = c.muted, fontSize = 13.sp)
                    Button(onClick = {
                        context.packageManager.getLaunchIntentForPackage(Stylus.SHIZUKU_PACKAGE)
                            ?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }) { Text(stringResource(R.string.stylus_open)) }
                }
                Stylus.State.NeedsPermission -> Button(onClick = Stylus::requestPermission) {
                    Text(stringResource(R.string.grant))
                }
                Stylus.State.Ready -> Unit
            }
        }

        Step(9, stringResource(R.string.step_start), done = false, keepOpen = true, enabled = canOverlay && cliVersion != null && loggedIn == true) {
            val shotsViaService = accessibility != null && ControlService.canScreenshot
            if (shotsViaService) {
                Text(stringResource(R.string.screenshots_via_accessibility), color = c.muted, fontSize = 13.sp)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = withScreenshots, onCheckedChange = { withScreenshots = it })
                    Text(stringResource(R.string.with_screenshots), color = c.text, fontSize = 14.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (withScreenshots && !shotsViaService) {
                        val mpm = context.getSystemService(MediaProjectionManager::class.java)
                        projectionLauncher.launch(mpm.createScreenCaptureIntent())
                    } else {
                        OverlayService.start(context)
                    }
                }) { Text(stringResource(R.string.start_overlay)) }
                OutlinedButton(onClick = { OverlayService.stop(context) }) { Text(stringResource(R.string.stop_overlay)) }
            }
        }

        Text(stringResource(R.string.setup_footer), color = c.faint, fontSize = 12.sp)
    }
}

@Composable
private fun Step(
    number: Int,
    title: String,
    done: Boolean,
    enabled: Boolean = true,
    detail: String? = null,
    /** Keep the step's controls visible once done (update, stop, …). */
    keepOpen: Boolean = false,
    content: @Composable () -> Unit,
) {
    val c = LocalOverlayColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.toolBg)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (done) "✓" else "$number", color = if (done) c.accent else c.muted, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(10.dp))
            Column {
                Text(title, color = if (enabled) c.text else c.faint, fontWeight = FontWeight.Medium)
                detail?.let { Text(it, color = c.muted, fontSize = 12.sp) }
            }
        }
        if (enabled && (!done || keepOpen)) content()
    }
}

private fun notificationsAllowed(context: android.content.Context) =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun batteryUnrestricted(context: android.content.Context) =
    context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

private fun micAllowed(context: android.content.Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}
