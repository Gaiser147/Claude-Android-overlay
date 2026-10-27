.class public Lio/github/gaiser147/notesbridge/Bootstrap;
.super Ljava/lang/Object;
.source "Bootstrap.smali"

# Injected into the patched Samsung Notes clone. MemoApplication.onCreate() calls
# start() right after super.onCreate(). This stub only logs, to prove the hook
# compiles and fires; the real in-process bridge to the Claude overlay replaces it
# (see scripts/notes-clone/README.md, "In-process bridge").

.method public static start(Landroid/content/Context;)V
    .locals 2

    const-string v0, "NotesBridge"

    const-string v1, "Claude bridge bootstrap: onCreate hook fired"

    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I

    return-void
.end method
