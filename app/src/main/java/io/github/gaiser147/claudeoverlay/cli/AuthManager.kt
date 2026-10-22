package io.github.gaiser147.claudeoverlay.cli

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.Writer
import kotlin.concurrent.thread

/**
 * Signs in by running the CLI's own `claude auth login` — the app never sees a password or
 * token, exactly like the desktop overlay, which also leaves login to the CLI.
 *
 * With no browser to launch the CLI prints the sign-in URL and waits for the code the
 * claude.com callback page shows; the UI opens the URL and feeds the pasted code to stdin.
 */
class AuthManager(private val env: CliEnvironment, private val proxy: LocalProxy) {

    sealed interface LoginState {
        data object Idle : LoginState
        data object Starting : LoginState
        data class AwaitingCode(val url: String) : LoginState
        data object Verifying : LoginState
        data object Success : LoginState
        data class Failed(val message: String) : LoginState
    }

    private val _state = MutableStateFlow<LoginState>(LoginState.Idle)
    val state: StateFlow<LoginState> = _state

    private var process: Process? = null
    private var stdin: Writer? = null

    suspend fun isLoggedIn(): Boolean = withContext(Dispatchers.IO) {
        if (!env.isInstalled) return@withContext false
        val p = env.processBuilder(listOf("auth", "status", "--json"), proxy).redirectErrorStream(false).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        parseLoggedIn(out)
    }

    fun startLogin() {
        cancel()
        _state.value = LoginState.Starting
        val p = try {
            env.processBuilder(listOf("auth", "login", "--claudeai"), proxy).redirectErrorStream(true).start()
        } catch (e: Exception) {
            _state.value = LoginState.Failed(e.message ?: e.toString())
            return
        }
        process = p
        stdin = p.outputStream.bufferedWriter()
        thread(name = "claude-login", isDaemon = true) {
            val seen = StringBuilder()
            val buf = CharArray(4096)
            val reader = p.inputStream.reader()
            while (true) {
                val n = runCatching { reader.read(buf) }.getOrDefault(-1)
                if (n < 0) break
                seen.append(buf, 0, n)
                val url = findLoginUrl(seen)
                if (url != null && _state.value == LoginState.Starting) {
                    _state.value = LoginState.AwaitingCode(url)
                }
            }
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            if (process !== p) return@thread
            _state.value = if (code == 0) LoginState.Success
            else LoginState.Failed(seen.toString().lines().lastOrNull { it.isNotBlank() }?.trim() ?: "exit $code")
        }
    }

    fun submitCode(code: String) {
        val w = stdin ?: return
        _state.value = LoginState.Verifying
        thread(name = "claude-login-code", isDaemon = true) {
            runCatching {
                w.write(code.trim())
                w.write("\n")
                w.flush()
            }.onFailure { _state.value = LoginState.Failed(it.message ?: "Couldn't send the code") }
        }
    }

    fun cancel() {
        process?.destroy()
        process = null
        stdin = null
        _state.value = LoginState.Idle
    }

    companion object {
        private val urlRegex = Regex("""https://\S+/oauth/authorize\?\S+""")

        fun findLoginUrl(output: CharSequence): String? = urlRegex.find(output)?.value

        /** `auth status --json` may print warnings first; the JSON object is what counts. */
        fun parseLoggedIn(output: String): Boolean {
            val start = output.indexOf('{')
            if (start < 0) return false
            val obj = runCatching { Json.parseToJsonElement(output.substring(start)) }.getOrNull() as? JsonObject
            return (obj?.get("loggedIn") as? JsonPrimitive)?.booleanOrNull == true
        }
    }
}
