package io.github.gaiser147.notesbridge;

import android.app.Application;
import android.content.Context;
import android.util.Log;

/**
 * Entry point inside the Claude Notes clone: patch.sh makes MemoApplication.onCreate() call
 * {@link #start} right after super.onCreate(). Nothing here may ever break Notes itself, so
 * every failure is logged and swallowed.
 */
public final class Bootstrap {
    static final String TAG = "NotesBridge";
    private static NotesBridge bridge;

    private Bootstrap() {}

    public static void start(Context context) {
        try {
            if (bridge != null) return;
            Application app = (Application) context.getApplicationContext();
            // Notes runs some work in extra processes; the editor lives in the main one.
            if (!app.getPackageName().equals(Application.getProcessName())) return;
            bridge = new NotesBridge(app);
            bridge.install();
            Log.i(TAG, "Claude bridge installed (Notes " + Symbols.NOTES_VERSION + " symbols)");
        } catch (Throwable t) {
            Log.w(TAG, "Claude bridge not started", t);
        }
    }
}
