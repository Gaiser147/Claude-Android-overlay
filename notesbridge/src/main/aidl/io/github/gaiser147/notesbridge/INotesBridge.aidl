package io.github.gaiser147.notesbridge;

/**
 * What the bridge inside the Claude Notes clone offers the overlay. Results are small JSON
 * objects as strings, so both sides can change fields without breaking the other.
 */
interface INotesBridge {
    /** {"bridge":1,"notes":"4.4.45.37","editor":true,"noteId":"…","editMode":true} */
    String status();

    /**
     * Adds text (Markdown allowed) to the open note, live in the editor, below what's there.
     * [noteId] null means the open note. Returns {"ok":true} or {"error":"…"}.
     */
    String insertText(String noteId, String text);

    /**
     * The note's title and typed text as last saved (handwriting isn't included). [noteId] null
     * means the open note. Returns {"noteId":"…","title":"…","text":"…"} or {"error":"…"}.
     */
    String readText(String noteId);

    // Protocol 2 ─────────────────────────────────────────────────────────────────────────────

    /**
     * The strokes the user drew since the last call (the first call after a note opens only
     * remembers what is there), in screen pixels:
     * {"strokes":[{"id":7,"points":[[x,y],…],"t":[ms,…]}]} or {"error":"…"}.
     */
    String newStrokes();

    /**
     * Writes strokes into the open note as real pen strokes, one undo step:
     * {"strokes":[{"points":[[x,y],…],"color":-16777216,"width":4.5,"marker":false}]} in screen
     * pixels (width too). Returns {"ok":true,"count":n} or {"error":"…"}.
     */
    String addStrokes(String json);
}
