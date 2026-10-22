package io.github.gaiser147.claudeoverlay.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Read-only view of the CLI's stored login (~/.claude/.credentials.json), ported from the
 * desktop overlay's authstate.py.
 *
 * Why not just run `claude auth status`? Every CLI process may refresh the OAuth tokens, and
 * the refresh token rotates: two processes refreshing at once, or one killed halfway through,
 * can leave the stored login unusable — which is exactly "I have to sign in again". So the app
 * never starts a second CLI just to look; it reads the file.
 */
object Credentials {
    enum class State {
        /** Tokens are stored; the login should work. */
        LoggedIn,
        /** The CLI blanked the tokens after a refresh was rejected: only a new login helps. */
        Dead,
        /** Never signed in (or signed out). */
        Missing,
        /** Something is there but unreadable; no evidence either way. */
        Unknown,
    }

    private const val MAX_BYTES = 256 * 1024

    fun file(home: File) = File(home, ".claude/.credentials.json")

    fun state(file: File): State {
        if (!file.isFile) return State.Missing
        if (file.length() > MAX_BYTES) return State.Unknown
        val root = runCatching { Json.parseToJsonElement(file.readText()) }.getOrNull() as? JsonObject
            ?: return State.Unknown
        val oauth = root["claudeAiOauth"] as? JsonObject ?: return State.Missing
        val refresh = (oauth["refreshToken"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val access = (oauth["accessToken"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when {
            // Exactly the marker the CLI writes after invalid_grant, nothing looser.
            refresh == "" && access.isNullOrEmpty() -> State.Dead
            !refresh.isNullOrEmpty() || !access.isNullOrEmpty() -> State.LoggedIn
            else -> State.Unknown
        }
    }

    /**
     * Cheap change detector: any token refresh or a new login rewrites the file, and a CLI
     * process started before that may still hold the superseded copy.
     */
    fun signature(file: File): Pair<Long, Long>? =
        if (file.isFile) file.lastModified() to file.length() else null

    private val authErrorMarkers = listOf(
        "oauth session expired",
        "oauth refresh token is no longer valid",
        "oauthrefreshdeaderror",
        "run /login",
        "claude auth login",
        "invalid api key",
        "please run /login",
    )

    /** Whether CLI *error* text says the login is unusable (don't use on ordinary replies). */
    fun isAuthError(text: String?): Boolean {
        val low = text?.lowercase() ?: return false
        return authErrorMarkers.any { it in low }
    }
}
