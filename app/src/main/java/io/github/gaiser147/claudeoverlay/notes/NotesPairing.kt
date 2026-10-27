package io.github.gaiser147.claudeoverlay.notes

/** The clone the user paired: its package and the SHA-256 of its signing certificate. */
data class NotesPin(val packageName: String, val certSha256: String) {
    fun encode() = "$packageName|$certSha256"

    companion object {
        fun decode(raw: String?): NotesPin? = raw?.split('|')?.takeIf { it.size == 2 && it.all(String::isNotBlank) }
            ?.let { (p, c) -> NotesPin(p, c) }
    }
}

/**
 * Who may attach a bridge. Any app can bind the exported link service, so a caller is trusted
 * only when it is exactly the clone the user confirmed once (trust on first use): same
 * package, same signing certificate. A reinstall signed with another key needs a new pairing.
 */
object NotesPairing {
    sealed interface Verdict {
        data class Trusted(val pin: NotesPin) : Verdict
        /** Unknown so far: ask the user whether to pair [candidate]. */
        data class Ask(val candidate: NotesPin) : Verdict
        data class Refused(val reason: String) : Verdict
    }

    /**
     * @param callerPackages the packages of the calling uid.
     * @param certOf the SHA-256 of a package's signing certificate, or null if unknown.
     */
    fun decide(callerPackages: List<String>, certOf: (String) -> String?, pinned: NotesPin?): Verdict {
        if (callerPackages.size != 1) return Verdict.Refused("caller must be exactly one package, got $callerPackages")
        val pkg = callerPackages.single()
        if (pkg == OWN_PACKAGE) return Verdict.Refused("not a notes clone")
        val cert = certOf(pkg) ?: return Verdict.Refused("no signing certificate for $pkg")
        val candidate = NotesPin(pkg, cert)
        return when {
            pinned == null -> Verdict.Ask(candidate)
            pinned == candidate -> Verdict.Trusted(candidate)
            pinned.packageName == pkg -> Verdict.Refused("$pkg is signed with a different key than the paired clone")
            else -> Verdict.Ask(candidate)
        }
    }

    /** The certificate fingerprint as the user sees it: AB:CD:… (first bytes suffice to compare). */
    fun shortFingerprint(sha256Hex: String): String =
        sha256Hex.uppercase().chunked(2).take(8).joinToString(":") + "…"

    private const val OWN_PACKAGE = "io.github.gaiser147.claudeoverlay"
}
