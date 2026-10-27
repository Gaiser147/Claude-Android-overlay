package io.github.gaiser147.notesbridge;

import android.app.Activity;
import android.graphics.Point;
import android.graphics.PointF;
import android.view.View;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * The open note editor as the bridge reaches it, through the classes Notes itself uses: the
 * note document (SpenWNote with its pages), the editor view (SpenComposer) and its zoom
 * scroller, which converts between view pixels and the document's content coordinates. Every
 * call is reflection on {@link Symbols}; call on the main thread.
 */
final class Editor {
    final Object note;
    private final View view;
    private final Object scroller;

    private Editor(Object note, View view, Object scroller) {
        this.note = note;
        this.view = view;
        this.scroller = scroller;
    }

    /** The editor in front, or null when there is none (or Notes changed its classes). */
    static Editor find(Object composerManager, Activity composer) throws ReflectiveOperationException {
        Object fragment = composerManager == null ? null : invoke(composerManager, "getFocusedComposerFragment");
        if (!isComposerFragment(fragment) && composer != null) fragment = composerFragmentOf(composer);
        if (!isComposerFragment(fragment)) return null;
        Object composerView = fieldOfType(fragment, Symbols.COMPOSER_VIEW);
        if (composerView == null) return null;
        View spen = (View) invoke(composerView, "getComposerView");
        Object presenter = invoke(composerView, "getPresenter");
        if (spen == null || presenter == null) return null;
        Object note = invoke(presenter, "getMSpenWNote");
        Object scroller = invoke(spen, "getNoteZoomScroller");
        return note == null || scroller == null ? null : new Editor(note, spen, scroller);
    }

    // ── coordinates ────────────────────────────────────────────────────────────────────────

    /** A point on the screen → page index and the point in that page's own coordinates. */
    PagePoint toPage(float screenX, float screenY) throws ReflectiveOperationException {
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        PointF content = (PointF) invoke(scroller, "convertViewPointIntoContentPoint",
                new PointF(screenX - loc[0], screenY - loc[1]));
        if (content == null) return null;
        int count = pageCount();
        int best = -1;
        for (int i = 0; i < count; i++) {
            Point off = offset(i);
            if (off == null) continue;
            // Pages stack down the content; the last one starting above the point holds it.
            if (content.y >= off.y && (best < 0 || off.y >= offset(best).y)) best = i;
        }
        if (best < 0) best = 0;
        Point off = offset(best);
        return new PagePoint(best, content.x - off.x, content.y - off.y);
    }

    /** First and last page the editor view shows right now. */
    int[] visiblePages() throws ReflectiveOperationException {
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        PagePoint top = toPage(loc[0] + view.getWidth() / 2f, loc[1]);
        PagePoint bottom = toPage(loc[0] + view.getWidth() / 2f, loc[1] + view.getHeight());
        int first = top == null ? 0 : top.page;
        int last = bottom == null ? pageCount() - 1 : bottom.page;
        return new int[] {Math.min(first, last), Math.max(first, last)};
    }

    /** A point on the screen in the coordinates of page [page] (which may not contain it). */
    PagePoint toPageOf(int page, float screenX, float screenY) throws ReflectiveOperationException {
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        PointF content = (PointF) invoke(scroller, "convertViewPointIntoContentPoint",
                new PointF(screenX - loc[0], screenY - loc[1]));
        Point off = offset(page);
        return new PagePoint(page, content.x - off.x, content.y - off.y);
    }

    /** A point of page [page] → the screen. */
    PointF toScreen(int page, float x, float y) throws ReflectiveOperationException {
        Point off = offset(page);
        PointF v = (PointF) invoke(scroller, "convertContentPointIntoViewPoint", new PointF(x + off.x, y + off.y));
        if (v == null) return null;
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        return new PointF(v.x + loc[0], v.y + loc[1]);
    }

    /** Screen pixels per content unit at the current zoom. */
    float scale() throws ReflectiveOperationException {
        PointF a = (PointF) invoke(scroller, "convertContentPointIntoViewPoint", new PointF(0, 0));
        PointF b = (PointF) invoke(scroller, "convertContentPointIntoViewPoint", new PointF(100, 0));
        return a == null || b == null ? 1f : Math.abs(b.x - a.x) / 100f;
    }

    void redraw() {
        view.invalidate();
    }

    // ── document ───────────────────────────────────────────────────────────────────────────

    int pageCount() throws ReflectiveOperationException {
        return (Integer) invoke(note, "getPageCount");
    }

    Object page(int index) throws ReflectiveOperationException {
        return invoke(note, "getPage", index);
    }

    Point offset(int index) throws ReflectiveOperationException {
        Object page = page(index);
        return page == null ? null : (Point) invoke(page, "getOffset");
    }

    List<?> objects(int index) throws ReflectiveOperationException {
        Object page = page(index);
        Object list = page == null ? null : invoke(page, "getObjectList");
        return list instanceof List ? (List<?>) list : new ArrayList<>();
    }

    static boolean isStroke(Object o) {
        return o != null && o.getClass().getName().equals(Symbols.SPEN_STROKE);
    }

    /** A new stroke object (not yet on a page). */
    static Object newStroke(ClassLoader loader, String pen, PointF[] points, float[] pressures, int[] times)
            throws ReflectiveOperationException {
        Class<?> c = Class.forName(Symbols.SPEN_STROKE, true, loader);
        Constructor<?> make = c.getConstructor(String.class, PointF[].class, float[].class, int[].class);
        return make.newInstance(pen, points, pressures, times);
    }

    // ── reflection ─────────────────────────────────────────────────────────────────────────

    static Object invoke(Object target, String name, Object... args) throws ReflectiveOperationException {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            types[i] = a instanceof Integer ? int.class
                    : a instanceof Float ? float.class
                    : a instanceof Boolean ? boolean.class
                    : a.getClass();
        }
        Method m = findMethod(target.getClass(), name, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    private static Method findMethod(Class<?> c, String name, Class<?>[] types) throws NoSuchMethodException {
        try {
            return c.getMethod(name, types);
        } catch (NoSuchMethodException e) {
            for (Class<?> k = c; k != null; k = k.getSuperclass()) {
                for (Method m : k.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterTypes().length == types.length) return m;
                }
            }
            throw e;
        }
    }

    private static boolean isComposerFragment(Object o) {
        for (Class<?> k = o == null ? null : o.getClass(); k != null; k = k.getSuperclass()) {
            if (k.getName().equals(Symbols.BASE_COMPOSER_FRAGMENT)) return true;
        }
        return false;
    }

    /** The composer fragment inside an editor activity (phones: one fragment per activity). */
    private static Object composerFragmentOf(Activity activity) {
        try {
            Object fm = invoke(activity, "getSupportFragmentManager");
            Object list = invoke(fm, "getFragments");
            if (list instanceof List) {
                for (Object f : (List<?>) list) if (isComposerFragment(f)) return f;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return null;
    }

    /** A field by its declared type's name, anywhere in the class hierarchy (names may be minified). */
    private static Object fieldOfType(Object target, String typeName) throws IllegalAccessException {
        for (Class<?> k = target.getClass(); k != null; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getType().getName().equals(typeName)) {
                    f.setAccessible(true);
                    return f.get(target);
                }
            }
        }
        return null;
    }

    static final class PagePoint {
        final int page;
        final float x;
        final float y;

        PagePoint(int page, float x, float y) {
            this.page = page;
            this.x = x;
            this.y = y;
        }
    }
}
