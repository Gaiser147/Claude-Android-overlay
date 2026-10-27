package io.github.gaiser147.notesbridge;

import io.github.gaiser147.notesbridge.INotesBridge;

/** The overlay's side: the bridge in the clone binds to it and hands over its own binder. */
interface INotesLink {
    /** The clone's bridge is up; [protocol] is the bridge's interface version. */
    void attach(INotesBridge bridge, int protocol);

    /** The note editor came to the front ([open]) or left it; [noteId] may be null. */
    oneway void editorChanged(boolean open, String noteId);
}
