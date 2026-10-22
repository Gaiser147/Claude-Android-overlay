package io.github.gaiser147.claudeoverlay.voice

/**
 * Turns a streamed Markdown reply into sentences worth reading aloud, as they complete, so
 * speech can start while Claude is still writing. Code blocks are skipped (nobody wants
 * Kotlin read to them), and Markdown punctuation is stripped from what's left.
 */
class SentenceChunker {
    private val buffer = StringBuilder()
    private var inFence = false

    /** Adds streamed text; returns the sentences it completed, ready to speak. */
    fun feed(text: String): List<String> {
        buffer.append(text)
        return drain(final = false)
    }

    /** End of the reply: whatever is left counts as a sentence. */
    fun flush(): List<String> = drain(final = true).also {
        buffer.clear()
        inFence = false
    }

    private fun drain(final: Boolean): List<String> {
        val out = mutableListOf<String>()
        while (true) {
            if (inFence) {
                val close = buffer.indexOf(FENCE)
                if (close < 0) {
                    // Keep a possible partial closing fence; drop the code before it.
                    val keep = trailingBackticks()
                    buffer.delete(0, buffer.length - keep)
                    break
                }
                buffer.delete(0, close + FENCE.length)
                // The rest of the fence line is the end of the block, not prose.
                skipRestOfLine()
                inFence = false
                continue
            }
            val fence = buffer.indexOf(FENCE)
            val end = sentenceEnd(if (fence < 0) buffer.length else fence)
            when {
                end >= 0 -> {
                    speakable(buffer.substring(0, end))?.let(out::add)
                    buffer.delete(0, end)
                }
                fence >= 0 -> {
                    speakable(buffer.substring(0, fence))?.let(out::add)
                    buffer.delete(0, fence + FENCE.length)
                    inFence = true
                }
                final -> {
                    speakable(buffer.toString())?.let(out::add)
                    buffer.clear()
                    break
                }
                else -> break
            }
        }
        return out
    }

    /**
     * Index just past the first sentence end before [limit]: . ! ? … followed by whitespace,
     * or a line break. "3.5" never qualifies (no whitespace after the dot), and neither does
     * a dot that ends an abbreviation ("z.B. ", "bzw. ").
     */
    private fun sentenceEnd(limit: Int): Int {
        var i = 0
        while (i < limit) {
            val ch = buffer[i]
            if (ch == '\n') return i + 1
            if (ch in ENDERS) {
                var j = i + 1
                while (j < limit && buffer[j] in ENDERS) j++
                if (j < limit && buffer[j].isWhitespace() && !(ch == '.' && endsAbbreviation(i))) return j + 1
            }
            i++
        }
        return -1
    }

    /** Whether the word ending at the dot at [dot] is an abbreviation like "z.B" or "bzw". */
    private fun endsAbbreviation(dot: Int): Boolean {
        var start = dot
        while (start > 0 && !buffer[start - 1].isWhitespace()) start--
        val word = buffer.substring(start, dot)
        return '.' in word || word.lowercase() in ABBREVIATIONS
    }

    private fun trailingBackticks(): Int {
        var n = 0
        while (n < 2 && n < buffer.length && buffer[buffer.length - 1 - n] == '`') n++
        return n
    }

    private fun skipRestOfLine() {
        val nl = buffer.indexOf("\n")
        if (nl >= 0) buffer.delete(0, nl + 1)
    }

    companion object {
        private const val FENCE = "```"
        private const val ENDERS = ".!?…"
        private val ABBREVIATIONS = setOf(
            "bzw", "ca", "dr", "nr", "usw", "vgl", "etc", "inkl", "evtl", "ggf", "max", "min", "mr", "mrs", "vs",
        )

        private val link = Regex("""!?\[([^\]]*)]\([^)]*\)""")
        private val emphasis = Regex("""(\*\*|__|\*|_|~~)(?=\S)(.+?)(?<=\S)\1""")
        private val heading = Regex("""^\s{0,3}#{1,6}\s+""")
        private val listMarker = Regex("""^\s*(?:[-*+]|\d+[.)])\s+""")
        private val quote = Regex("""^\s*>\s?""")
        private val url = Regex("""https?://\S+""")

        /** Markdown → plain words; null if nothing speakable is left. */
        fun speakable(markdown: String): String? {
            var t = markdown
            t = heading.replace(t, "")
            t = quote.replace(t, "")
            t = listMarker.replace(t, "")
            t = link.replace(t) { it.groupValues[1] }
            t = url.replace(t, "")
            repeat(2) { t = emphasis.replace(t) { m -> m.groupValues[2] } }
            t = t.replace("`", "").replace("|", " ")
            t = t.replace(Regex("\\s+"), " ").trim()
            return t.takeIf { s -> s.any(Char::isLetterOrDigit) }
        }
    }
}
