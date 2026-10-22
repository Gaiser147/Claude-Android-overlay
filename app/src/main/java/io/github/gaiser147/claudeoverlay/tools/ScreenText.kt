package io.github.gaiser147.claudeoverlay.tools

/** A rectangle in screen pixels. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val isEmpty get() = right <= left || bottom <= top
    val center get() = Pt((left + right) / 2f, (top + bottom) / 2f)
}

/** One accessibility node, as much as Claude needs of it. Bounds are screen pixels. */
data class UiNode(
    val text: String? = null,
    val description: String? = null,
    /** A text field's placeholder, when the field is empty. */
    val hint: String? = null,
    /** Short class name, e.g. "Button", "EditText", "Switch". */
    val role: String = "",
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val focused: Boolean = false,
    val enabled: Boolean = true,
    val bounds: Box,
    val children: List<UiNode> = emptyList(),
)

/** The windows of the apps on screen (topmost first) and where the keyboard starts, if open. */
data class ScreenSnapshot(val app: String?, val roots: List<UiNode>, val screen: Box, val keyboardTop: Int? = null)

/** One line Claude reads: what it is, its label and where to touch it (screen pixels). */
data class ScreenElement(val kind: String, val label: String, val center: Pt, val flags: List<String> = emptyList())

/**
 * The screen as text, from the accessibility tree: a few hundred tokens where a screenshot
 * costs one to two thousand, and exact touch targets instead of estimates from pixels. Each
 * element gets a number Claude can tap or type into.
 */
object ScreenText {
    const val MAX_ELEMENTS = 120
    private const val MAX_LABEL = 70
    /** Fewer elements than this: a canvas, game or unlabelled UI that needs a picture. */
    const val SPARSE = 3

    fun elements(snapshot: ScreenSnapshot): List<ScreenElement> {
        val out = mutableListOf<ScreenElement>()
        for (root in snapshot.roots) collect(root, snapshot, out)
        return out.distinct()
    }

    /** Whether [elements] say enough about the screen to work without a picture. */
    fun isSparse(elements: List<ScreenElement>) = elements.count { it.label.isNotEmpty() || it.kind == "field" } < SPARSE

    /**
     * One line per element: `[n] kind "label" @x,y flags`, coordinates in the pixels of the
     * latest screenshot ([geometry]), so they mix freely with what Claude measures in images.
     */
    fun render(snapshot: ScreenSnapshot, elements: List<ScreenElement>, geometry: ShotGeometry): String = buildString {
        append("App: ").append(snapshot.app ?: "unknown")
        snapshot.keyboardTop?.let {
            append(" · keyboard open, covers y ≥ ").append(geometry.toImage(Pt(0f, it.toFloat())).y.toInt())
        }
        append(" · coordinates in a ${geometry.imageWidth}×${geometry.imageHeight} screenshot space\n")
        elements.take(MAX_ELEMENTS).forEachIndexed { i, e ->
            val p = geometry.toImage(e.center)
            append('[').append(i + 1).append("] ")
            if (e.kind.isNotEmpty()) append(e.kind).append(' ')
            if (e.label.isNotEmpty()) append('"').append(e.label).append("\" ")
            append('@').append(p.x.toInt()).append(',').append(p.y.toInt())
            e.flags.forEach { append(' ').append(it) }
            append('\n')
        }
        if (elements.size > MAX_ELEMENTS) append("… ${elements.size - MAX_ELEMENTS} more below; scroll or take a screenshot\n")
        if (elements.isEmpty()) append("(no readable elements; take a screenshot)\n")
    }

    private fun collect(node: UiNode, snapshot: ScreenSnapshot, out: MutableList<ScreenElement>) {
        val visible = intersect(node.bounds, snapshot.screen)
        if (visible == null) return
        // A clickable page or card spanning much of the screen is a container, not a button:
        // reading all its words into one label would hide the content.
        val large = area(visible) > LARGE_SHARE * area(snapshot.screen)
        val actionable = (node.clickable && !large) || node.editable || node.checkable
        val own = label(node)
        when {
            actionable -> {
                // A tappable row usually carries its words in plain child views: read them into
                // the row, so Claude gets one target instead of a container and loose texts.
                val label = own.ifEmpty { descendantText(node) }
                if (label.isNotEmpty() || node.editable) out += element(node, kind(node), label, visible, snapshot)
                node.children.filter { it.hasActionable() }.forEach { collect(it, snapshot, out) }
                return
            }
            node.scrollable -> out += element(node, "scrollable", own, visible, snapshot)
            own.isNotEmpty() -> out += element(node, "", own, visible, snapshot)
        }
        node.children.forEach { collect(it, snapshot, out) }
    }

    private fun element(node: UiNode, kind: String, label: String, visible: Box, snapshot: ScreenSnapshot): ScreenElement {
        val flags = buildList {
            if (node.editable && node.text.isNullOrEmpty() && !node.hint.isNullOrEmpty()) add("(empty, hint \"${clip(node.hint)}\")")
            if (node.focused) add("focused")
            if (node.checkable) add(if (node.checked) "on" else "off")
            if (node.selected) add("selected")
            if (!node.enabled) add("disabled")
            if (snapshot.keyboardTop != null && visible.center.y >= snapshot.keyboardTop) add("under keyboard")
        }
        return ScreenElement(kind, label, visible.center, flags)
    }

    private fun kind(node: UiNode): String = when {
        node.editable -> "field"
        node.checkable -> if (node.role.contains("Switch") || node.role.contains("Toggle")) "switch" else "checkbox"
        node.role.contains("Tab") -> "tab"
        else -> "button"
    }

    private fun label(node: UiNode): String = clip(
        listOfNotNull(node.text, node.description).map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" · ")
    )

    private fun descendantText(node: UiNode): String = clip(
        buildList {
            fun walk(n: UiNode) {
                n.children.forEach { c ->
                    if (c.clickable || c.editable || c.checkable) return@forEach
                    label(c).takeIf { it.isNotEmpty() }?.let(::add)
                    walk(c)
                }
            }
            walk(node)
        }.distinct().joinToString(" · ")
    )

    private fun UiNode.hasActionable(): Boolean =
        clickable || editable || checkable || children.any { it.hasActionable() }

    private fun clip(s: String?): String {
        val one = s.orEmpty().replace(Regex("\\s+"), " ").trim().replace("\"", "'")
        return if (one.length <= MAX_LABEL) one else one.take(MAX_LABEL - 1) + "…"
    }

    private const val LARGE_SHARE = 0.4f

    private fun area(b: Box) = (b.right - b.left).toFloat() * (b.bottom - b.top)

    private fun intersect(a: Box, b: Box): Box? {
        val r = Box(maxOf(a.left, b.left), maxOf(a.top, b.top), minOf(a.right, b.right), minOf(a.bottom, b.bottom))
        return r.takeUnless { it.isEmpty }
    }
}
