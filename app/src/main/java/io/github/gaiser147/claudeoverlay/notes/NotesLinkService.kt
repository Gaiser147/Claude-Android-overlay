package io.github.gaiser147.claudeoverlay.notes

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.gaiser147.claudeoverlay.AppGraph
import io.github.gaiser147.claudeoverlay.R
import io.github.gaiser147.notesbridge.INotesBridge
import io.github.gaiser147.notesbridge.INotesLink
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Where the bridge inside the Claude Notes clone connects (it binds here while a note editor
 * is open). Exported, so every caller is checked against the pairing ([NotesPairing]); the
 * first clone to call is paired only after the user confirms it in a notification.
 */
class NotesLinkService : Service() {
    private val settings get() = AppGraph.get(this).settings

    private val binder = object : INotesLink.Stub() {
        override fun attach(bridge: INotesBridge, protocol: Int) {
            val packages = packageManager.getPackagesForUid(Binder.getCallingUid())?.toList().orEmpty()
            // A binder thread: blocking on DataStore briefly is fine here.
            val pinned = NotesPin.decode(runBlocking { settings.notesPin() })
            NotesLink.setPin(pinned)
            when (val verdict = NotesPairing.decide(packages, ::certSha256, pinned)) {
                is NotesPairing.Verdict.Trusted -> NotesLink.attach(bridge, verdict.pin, protocol)
                is NotesPairing.Verdict.Ask -> {
                    NotesLink.ask(bridge, verdict.candidate, protocol)
                    askToPair(verdict.candidate)
                }
                is NotesPairing.Verdict.Refused -> throw SecurityException(verdict.reason)
            }
        }

        override fun editorChanged(open: Boolean, noteId: String?) {
            val pin = NotesLink.state.value.pin ?: return
            val packages = packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
            if (pin.packageName in packages) NotesLink.editorChanged(open, noteId)
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAIR -> NotesLink.confirm()?.let { pin ->
                runBlocking { settings.setNotesPin(pin.encode()) }
                thread(name = "notes-refresh") { NotesLink.refresh() }
            }
            ACTION_DECLINE -> NotesLink.decline()
        }
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun askToPair(candidate: NotesPin) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notes_channel), NotificationManager.IMPORTANCE_HIGH)
        )
        fun action(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, NotesLinkService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = getString(
            R.string.notes_pair_text, appLabel(candidate.packageName), NotesPairing.shortFingerprint(candidate.certSha256),
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notes_pair_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .addAction(0, getString(R.string.notes_pair), action(ACTION_PAIR, 1))
            .addAction(0, getString(R.string.notes_decline), action(ACTION_DECLINE, 2))
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
    }

    private fun appLabel(pkg: String): String = runCatching {
        "${packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0))} ($pkg)"
    }.getOrDefault(pkg)

    /** SHA-256 (hex) of the package's signing certificate; null if there isn't exactly one. */
    private fun certSha256(pkg: String): String? = runCatching {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            val info = packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                ?: return null
            if (info.hasMultipleSigners()) return null
            info.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        }
        val cert = signatures?.lastOrNull() ?: return null
        MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString("") { "%02x".format(it) }
    }.getOrNull()

    companion object {
        private const val ACTION_PAIR = "io.github.gaiser147.claudeoverlay.notes.PAIR"
        private const val ACTION_DECLINE = "io.github.gaiser147.claudeoverlay.notes.DECLINE"
        private const val CHANNEL = "notes"
        private const val NOTIFICATION_ID = 7
    }
}
