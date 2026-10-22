package io.github.gaiser147.claudeoverlay.stylus;

/**
 * The stylus service Shizuku runs for the app. It can do exactly one thing: play back pen
 * strokes that the user asked Claude to draw.
 */
interface IStylusService {
    /** Shizuku's reserved transaction code for tearing a user service down. */
    void destroy() = 16777114;

    /**
     * Plays back pen events in order. The arrays are parallel: action (0 down, 1 up, 2 move),
     * x and y (screen pixels), pressure (0..1) and time (ms after the first event).
     * Returns false if Android refused an event.
     */
    boolean inject(in int[] actions, in float[] xs, in float[] ys, in float[] pressures, in long[] times) = 1;
}
