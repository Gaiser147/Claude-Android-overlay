package io.github.gaiser147.notesbridge;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * The bridge in the clone's process: binds to the Claude Overlay app while a note editor is
 * in front, and does what Notes' own assistant integrations do — add text to the open note
 * live, read a note's text — through Notes' in-process classes ({@link Symbols}).
 */
final class NotesBridge extends INotesBridge.Stub
        implements Application.ActivityLifecycleCallbacks, ServiceConnection {
    static final int PROTOCOL = 1;
    private static final long MAIN_TIMEOUT_MS = 3000;

    private final Application app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WeakReference<Activity> composer = new WeakReference<>(null);
    private volatile INotesLink link;
    private boolean bound;
    private volatile boolean editorOpen;

    NotesBridge(Application app) {
        this.app = app;
    }

    void install() {
        app.registerActivityLifecycleCallbacks(this);
    }

    // ── editor tracking and the link to the overlay ────────────────────────────────────────

    @Override
    public void onActivityResumed(Activity activity) {
        if (!isEditor(activity)) return;
        composer = new WeakReference<>(activity);
        editorOpen = true;
        connect();
        report();
    }

    @Override
    public void onActivityPaused(Activity activity) {
        if (composer.get() != activity) return;
        editorOpen = false;
        report();
    }

    private boolean isEditor(Activity activity) {
        if (activity.getClass().getName().equals(Symbols.COMPOSER_ACTIVITY)) return true;
        // Tablets show the editor as a pane next to the list, inside another activity.
        Object focused = call(composerManager(), "isFocusOnComposer");
        return Boolean.TRUE.equals(focused);
    }

    /** Binds once; Android reconnects by itself when the overlay app restarts. */
    private void connect() {
        if (bound) return;
        Intent intent = new Intent().setClassName(Symbols.OVERLAY_PACKAGE, Symbols.LINK_SERVICE);
        try {
            bound = app.bindService(intent, this, Context.BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            Log.w(Bootstrap.TAG, "can't bind to Claude Overlay", e);
        }
        if (!bound) Log.i(Bootstrap.TAG, "Claude Overlay not installed or not visible");
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        INotesLink l = INotesLink.Stub.asInterface(service);
        try {
            l.attach(this, PROTOCOL);
            link = l;
            report();
        } catch (RemoteException | SecurityException e) {
            Log.w(Bootstrap.TAG, "Claude Overlay refused the bridge", e);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        link = null;
    }

    @Override
    public void onBindingDied(ComponentName name) {
        // The overlay was updated or reinstalled: bind afresh the next time an editor opens.
        link = null;
        bound = false;
        try {
            app.unbindService(this);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void report() {
        INotesLink l = link;
        if (l == null) return;
        try {
            l.editorChanged(editorOpen, editorOpen ? openNoteId() : null);
        } catch (RemoteException e) {
            link = null;
        }
    }

    // ── INotesBridge ───────────────────────────────────────────────────────────────────────

    @Override
    public String status() {
        checkCaller();
        JSONObject o = new JSONObject();
        try {
            o.put("bridge", PROTOCOL);
            o.put("notes", Symbols.NOTES_VERSION);
            o.put("editor", editorOpen);
            String id = openNoteId();
            if (id != null) o.put("noteId", id);
            Object edit = call(composerManager(), "isRunningOnEditMode");
            if (edit instanceof Boolean) o.put("editMode", edit);
        } catch (JSONException ignored) {
        }
        return o.toString();
    }

    @Override
    public String insertText(String noteId, String text) {
        checkCaller();
        if (text == null || text.isEmpty()) return error("Nothing to insert.");
        return onMain(() -> {
            Object manager = composerManager();
            Object target = call(manager, "getComposerToAddContent", 0);
            Activity editor = target instanceof Activity ? (Activity) target : composer.get();
            if (editor == null || editor.isFinishing()) return error("No note is open in Claude Notes.");
            String id = noteId != null ? noteId : openNoteId();
            Intent intent = new Intent(Intent.ACTION_SEND)
                    .setClassName(app.getPackageName(), Symbols.COMPOSER_ACTIVITY)
                    .setType("text/plain")
                    .putExtra(Symbols.EXTRA_BIXBY_ACTION, Symbols.BIXBY_ADD_CONTENT)
                    .putExtra(Symbols.EXTRA_AI_UPDATE, true)
                    .putExtra(Intent.EXTRA_TEXT, text);
            if (id != null) intent.putExtra(Symbols.EXTRA_NOTE_ID, id);
            // Into the editor's own task, as Notes does it: singleTop hands it to onNewIntent.
            ActivityManager am = app.getSystemService(ActivityManager.class);
            for (ActivityManager.AppTask task : am.getAppTasks()) {
                if (task.getTaskInfo().taskId == editor.getTaskId()) {
                    task.startActivity(app, intent, null);
                    return ok();
                }
            }
            editor.startActivity(intent);
            return ok();
        });
    }

    @Override
    public String readText(String noteId) {
        checkCaller();
        String id = noteId != null ? noteId : openNoteId();
        if (id == null) return error("No note is open in Claude Notes.");
        try {
            ClassLoader loader = app.getClassLoader();
            Class<?> factoryClass = Class.forName(Symbols.REPOSITORY_FACTORY, true, loader);
            Object factory = factoryClass.getMethod("newInstance", Context.class).invoke(null, app);
            Object repository = factory.getClass().getMethod("createSearchRepository").invoke(factory);
            Method get = repository.getClass().getMethod("getDocumentTextData", Collection.class);
            List<?> found = (List<?>) get.invoke(repository, Collections.singletonList(id));
            if (found == null || found.isEmpty()) return error("Note " + id + " isn't saved yet.");
            Object doc = found.get(0);
            JSONObject o = new JSONObject();
            o.put("noteId", id);
            o.put("title", String.valueOf(call(doc, "getTitle")));
            Object content = call(doc, "getStrippedContent");
            o.put("text", content == null ? "" : content.toString());
            return o.toString();
        } catch (Throwable t) {
            Log.w(Bootstrap.TAG, "readText failed", t);
            return error("Couldn't read the note: " + t);
        }
    }

    /** Only the overlay app (which got this binder through attach) may call the bridge. */
    private void checkCaller() {
        String[] packages = app.getPackageManager().getPackagesForUid(Binder.getCallingUid());
        if (packages != null) {
            for (String p : packages) {
                if (p.equals(Symbols.OVERLAY_PACKAGE) || p.equals(app.getPackageName())) return;
            }
        }
        throw new SecurityException("not the Claude Overlay app");
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────

    private String openNoteId() {
        Object id = call(composerManager(), "getLatestComposerUuid");
        return id instanceof String && !((String) id).isEmpty() ? (String) id : null;
    }

    private Object composerManager() {
        try {
            return Class.forName(Symbols.COMPOSER_MANAGER, true, app.getClassLoader())
                    .getMethod("getInstance").invoke(null);
        } catch (Throwable t) {
            Log.w(Bootstrap.TAG, "ComposerManager unavailable", t);
            return null;
        }
    }

    /** Calls a public method by name; int and no-argument methods are all the bridge needs. */
    private static Object call(Object target, String method, Object... args) {
        if (target == null) return null;
        try {
            Class<?>[] types = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) types[i] = args[i] instanceof Integer ? int.class : args[i].getClass();
            return target.getClass().getMethod(method, types).invoke(target, args);
        } catch (Throwable t) {
            Log.w(Bootstrap.TAG, method + " failed", t);
            return null;
        }
    }

    private String onMain(Callable<String> work) {
        FutureTask<String> task = new FutureTask<>(work);
        main.post(task);
        try {
            return task.get(MAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            Log.w(Bootstrap.TAG, "editor call failed", t);
            return error("The editor didn't respond: " + t);
        }
    }

    private static String ok() {
        return "{\"ok\":true}";
    }

    private static String error(String message) {
        return "{\"error\":" + JSONObject.quote(message) + "}";
    }

    // Unused lifecycle callbacks.
    @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}
