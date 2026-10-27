package io.github.gaiser147.notesbridge;

/**
 * Every name the bridge uses from inside Samsung Notes, in one place: a new Notes version may
 * rename or move them, and then only this file changes. Checked against Samsung Notes
 * 4.4.45.37 (see docs/samsung-notes-plugin.md).
 */
final class Symbols {
    private Symbols() {}

    /** The Notes version these names were taken from. */
    static final String NOTES_VERSION = "4.4.45.37";

    /** Tracks the open note editors: getInstance(), getLatestComposerUuid(), … */
    static final String COMPOSER_MANAGER = "com.samsung.android.support.senl.nt.base.common.ComposerManager";
    /** The note editor (launchMode singleTop, so an intent to it reaches onNewIntent). */
    static final String COMPOSER_ACTIVITY = "com.samsung.android.support.senl.nt.app.nativecomposer.ComposerActivity";
    /** newInstance(Context).createSearchRepository().getDocumentTextData(Collection<String>) */
    static final String REPOSITORY_FACTORY = "com.samsung.android.support.senl.nt.data.repository.NotesDataRepositoryFactory";

    /** The note editor fragment; it holds the editor view (a field of type COMPOSER_VIEW). */
    static final String BASE_COMPOSER_FRAGMENT = "com.samsung.android.support.senl.nt.composer.main.base.view.BaseComposerFragment";
    /** getComposerView() → SpenComposer (getNoteZoomScroller()); getPresenter() → getMSpenWNote(). */
    static final String COMPOSER_VIEW = "com.samsung.android.support.senl.nt.composer.main.base.view.composer.ComposerView";
    /** A pen stroke: new SpenObjectStroke(penName, PointF[], float[] pressures, int[] timestamps). */
    static final String SPEN_STROKE = "com.samsung.android.sdk.pen.document.SpenObjectStroke";
    /** Pens Claude writes with when the user hasn't drawn anything to copy the pen from. */
    static final String DEFAULT_PEN = "com.samsung.android.sdk.pen.pen.preload.FountainPen";
    static final String HIGHLIGHTER_PEN = "com.samsung.android.sdk.pen.pen.preload.StraightHighlighter";
    /** Marks Claude's own strokes (an object's extra data), so they never count as the user's. */
    static final String CLAUDE_MARK = "io.github.gaiser147.claude";

    // The intent Notes sends itself to add content to the note being edited (Bixby's
    // UpdateAsync and the updateNote AppFunction), handled by ActionSendHelperAppFunction.
    static final String EXTRA_BIXBY_ACTION = "BixbyAction";
    static final String BIXBY_ADD_CONTENT = "AddContentToEditingNote";
    static final String EXTRA_AI_UPDATE = "AI_ACTION_UPDATE_EDITING_NOTE";
    static final String EXTRA_NOTE_ID = "noteId";

    /** The Claude Overlay app and the service the bridge binds to there. */
    static final String OVERLAY_PACKAGE = "io.github.gaiser147.claudeoverlay";
    static final String LINK_SERVICE = "io.github.gaiser147.claudeoverlay.notes.NotesLinkService";
}
