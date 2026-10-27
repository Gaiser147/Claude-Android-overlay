package io.github.gaiser147.notesbridge;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.PointF;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    static final int PROTOCOL = 2;
    /** At most this many new strokes per newStrokes() call (a scroll can reveal thousands). */
    private static final int MAX_NEW_STROKES = 300;
    private static final long MAIN_TIMEOUT_MS = 3000;
    /** Pen pressure of Claude's strokes: a steady, medium hand. */
    private static final float PRESSURE = 0.7f;
    /** Points per stroke sent to the overlay; recognition needs no more. */
    private static final int MAX_POINTS = 120;

    private final Application app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WeakReference<Activity> composer = new WeakReference<>(null);
    private volatile INotesLink link;
    private boolean bound;
    private volatile boolean editorOpen;
    /** The user's strokes already reported (runtime handles), for the note in [seenNote]. */
    private final Set<Integer> seen = new HashSet<>();
    private final Set<Integer> seenPages = new HashSet<>();
    private String seenNote;
    /** The pen the user last wrote with: Claude writes with the same one. */
    private String lastPen;

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

    @Override
    public String newStrokes() {
        checkCaller();
        return onMain(() -> {
            Editor editor = Editor.find(composerManager(), composer.get());
            if (editor == null) return error("No note editor is open.");
            String id = openNoteId();
            if (seenNote == null || !seenNote.equals(id)) {
                seen.clear();
                seenPages.clear();
                seenNote = id;
            }
            JSONArray out = new JSONArray();
            // Only the pages on screen: the user writes where they look, and a long note has
            // thousands of strokes. A page seen for the first time only sets what's old.
            int[] range = editor.visiblePages();
            for (int p = range[0]; p <= range[1] && out.length() < MAX_NEW_STROKES; p++) {
                boolean baseline = seenPages.add(p);
                for (Object o : editor.objects(p)) {
                    if (!Editor.isStroke(o)) continue;
                    int handle = (Integer) Editor.invoke(o, "getRuntimeHandle");
                    if (!seen.add(handle) || baseline || isClaudes(o)) continue;
                    out.put(strokeJson(editor, p, o, handle));
                    if (out.length() >= MAX_NEW_STROKES) break;
                }
            }
            return new JSONObject().put("strokes", out).toString();
        });
    }

    @Override
    public String addStrokes(String json) {
        checkCaller();
        return onMain(() -> {
            Editor editor = Editor.find(composerManager(), composer.get());
            if (editor == null) return error("No note editor is open.");
            JSONArray strokes = new JSONObject(json).getJSONArray("strokes");
            if (strokes.length() == 0) return error("No strokes.");
            JSONArray first = strokes.getJSONObject(0).getJSONArray("points").getJSONArray(0);
            Editor.PagePoint anchor = editor.toPage((float) first.getDouble(0), (float) first.getDouble(1));
            if (anchor == null) return error("Couldn't place the strokes on the page.");
            float scale = editor.scale();
            ArrayList<Object> made = new ArrayList<>();
            int time = 0;
            for (int i = 0; i < strokes.length(); i++) {
                JSONObject s = strokes.getJSONObject(i);
                JSONArray pts = s.getJSONArray("points");
                if (pts.length() == 0) continue;
                PointF[] points = new PointF[pts.length()];
                float[] pressures = new float[pts.length()];
                int[] times = new int[pts.length()];
                for (int k = 0; k < pts.length(); k++) {
                    JSONArray xy = pts.getJSONArray(k);
                    Editor.PagePoint pp = editor.toPageOf(anchor.page, (float) xy.getDouble(0), (float) xy.getDouble(1));
                    points[k] = new PointF(pp.x, pp.y);
                    pressures[k] = PRESSURE;
                    times[k] = time += 8;
                }
                boolean marker = s.optBoolean("marker", false);
                String pen = marker ? Symbols.HIGHLIGHTER_PEN : (lastPen != null ? lastPen : Symbols.DEFAULT_PEN);
                Object stroke = Editor.newStroke(app.getClassLoader(), pen, points, pressures, times);
                Editor.invoke(stroke, "setColor", s.optInt("color", 0xFF000000));
                Editor.invoke(stroke, "setPenSize", (float) (s.optDouble("width", 4.0) / scale));
                Editor.invoke(stroke, "setExtraDataInt", Symbols.CLAUDE_MARK, 1);
                made.add(stroke);
            }
            Object page = editor.page(anchor.page);
            // One undo step for the whole answer, as Notes groups its own insertions.
            boolean group = !Boolean.TRUE.equals(Editor.invoke(editor.note, "isGroupingHistory"));
            if (group) Editor.invoke(editor.note, "beginHistoryGroup");
            try {
                Editor.invoke(page, "appendObjectList", made);
            } finally {
                if (group) Editor.invoke(editor.note, "endHistoryGroup");
            }
            for (Object o : made) seen.add((Integer) Editor.invoke(o, "getRuntimeHandle"));
            editor.redraw();
            return new JSONObject().put("ok", true).put("count", made.size()).toString();
        });
    }

    private JSONObject strokeJson(Editor editor, int page, Object stroke, int handle) throws Exception {
        PointF[] points = (PointF[]) Editor.invoke(stroke, "getPoints");
        int[] times = (int[]) Editor.invoke(stroke, "getTimeStamps");
        Object pen = Editor.invoke(stroke, "getPenName");
        if (pen instanceof String && !((String) pen).contains("Highlighter") && !((String) pen).contains("Eraser")) {
            lastPen = (String) pen;
        }
        JSONArray pts = new JSONArray();
        JSONArray ts = new JSONArray();
        int step = Math.max(1, (points == null ? 0 : points.length) / MAX_POINTS);
        for (int i = 0; points != null && i < points.length; i += step) {
            PointF s = editor.toScreen(page, points[i].x, points[i].y);
            if (s == null) continue;
            pts.put(new JSONArray().put(Math.round(s.x)).put(Math.round(s.y)));
            ts.put(times != null && i < times.length ? times[i] : i * 8);
        }
        return new JSONObject().put("id", handle).put("points", pts).put("t", ts);
    }

    private static boolean isClaudes(Object o) {
        try {
            return Boolean.TRUE.equals(Editor.invoke(o, "hasExtraDataInt", Symbols.CLAUDE_MARK));
        } catch (ReflectiveOperationException e) {
            return false;
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
