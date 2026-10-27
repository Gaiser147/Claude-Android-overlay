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
}
