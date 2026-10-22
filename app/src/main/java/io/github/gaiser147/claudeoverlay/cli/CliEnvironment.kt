package io.github.gaiser147.claudeoverlay.cli

import android.content.Context
import java.io.File

/**
 * Where everything the Claude Code CLI needs lives on the device, and how to start it.
 *
 * The CLI is a linux-arm64-musl binary. Android can't execve anything the app downloaded
 * (W^X since API 29), and has no musl, so it is never exec'd directly: the bundled musl
 * loader (libmuslld.so, in nativeLibraryDir where exec IS allowed) is started with the CLI's
 * path as its first argument and maps it like a library.
 */
class CliEnvironment(context: Context) {
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    val loader = File(nativeDir, "libmuslld.so")
    val bash = File(nativeDir, "libbash.so")

    val cliDir = File(context.filesDir, "cli")
    val cliBinary = File(cliDir, "claude")
    val versionFile = File(cliDir, "version")

    val home = File(context.filesDir, "home")
    /** The CLI's cwd: a scratch folder the agent may freely read and write. */
    val workspace = File(home, "workspace")
    val tmp = File(context.cacheDir, "tmp")

    val isInstalled: Boolean get() = cliBinary.isFile && versionFile.isFile

    fun processBuilder(args: List<String>, proxy: LocalProxy): ProcessBuilder {
        workspace.mkdirs()
        tmp.mkdirs()
        val pb = ProcessBuilder(listOf(loader.path, cliBinary.path) + args)
        pb.directory(workspace)
        pb.environment().putAll(
            cliEnvironment(home = home, tmp = tmp, bash = bash, proxyUrl = proxy.url)
        )
        return pb
    }
}

/**
 * The environment variables layered over Android's own for every CLI process.
 *
 * - HTTPS_PROXY: musl resolves DNS from /etc/resolv.conf, which Android doesn't have, so the
 *   CLI can't look up api.anthropic.com itself. Every request goes through [LocalProxy], which
 *   resolves with Android's resolver instead.
 * - SHELL / CLAUDE_CODE_SHELL: the Bash tool refuses any shell whose path lacks "bash"/"zsh".
 * - DISABLE_AUTOUPDATER: the CLI's updater would install to ~/.local/bin, which we can't exec;
 *   updates go through [CliInstaller] instead.
 */
fun cliEnvironment(home: File, tmp: File, bash: File, proxyUrl: String): Map<String, String> = mapOf(
    "HOME" to home.path,
    "TMPDIR" to tmp.path,
    "CLAUDE_CODE_TMPDIR" to tmp.path,
    "SHELL" to bash.path,
    "CLAUDE_CODE_SHELL" to bash.path,
    "HTTPS_PROXY" to proxyUrl,
    "HTTP_PROXY" to proxyUrl,
    "https_proxy" to proxyUrl,
    "http_proxy" to proxyUrl,
    "NO_PROXY" to "localhost,127.0.0.1,::1",
    "no_proxy" to "localhost,127.0.0.1,::1",
    "DISABLE_AUTOUPDATER" to "1",
    "LANG" to "C.UTF-8",
    "TERM" to "dumb",
)
