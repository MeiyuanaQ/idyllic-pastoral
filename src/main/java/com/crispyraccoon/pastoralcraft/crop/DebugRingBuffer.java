package com.crispyraccoon.pastoralcraft.crop;

/**
 * Fixed-capacity in-memory ring buffer capturing a chronological trace of
 * crop-growth events. Because it only holds strings (never live world objects)
 * and lives in a fixed array, it is safe against the "global unbounded map"
 * red line and never pins chunks or levels.
 *
 * <p>All record sites run on the server main thread; dump/clear are also called
 * from the server main thread (commands / server-stopping events), so no lock
 * is required.</p>
 *
 * <p>Recording self-gates on {@link DebugGate.DebugModule#RING} and never throws —
 * a formatting error must not take down the tick loop (red line 5.1.16 spirit).</p>
 */
public final class DebugRingBuffer {
    private DebugRingBuffer() {
    }

    private static final int DEFAULT_CAPACITY = 2048;

    private static String[] events = new String[DEFAULT_CAPACITY];
    private static int head;
    private static int count;

    /** Cached configured capacity; refreshed on config reload. */
    private static volatile int capacity = DEFAULT_CAPACITY;

    /**
     * Re-read the configured ring capacity. Called from {@link DebugGate#refreshCache()}.
     */
    static void refreshCapacity() {
        try {
            capacity = CropGrowthConfig.DEBUG_RING_SIZE.get();
        } catch (Exception ignored) {
            capacity = DEFAULT_CAPACITY;
        }
    }

    /**
     * Record one event line. No-op when the ring module is disabled.
     * Never throws.
     */
    public static void record(String category, String fmt, Object... args) {
        if (!DebugGate.enabled(DebugGate.DebugModule.RING)) return;
        try {
            int cap = capacity;
            if (events.length != cap) {
                resize(cap);
            }
            events[head] = buildLine(category, fmt, args);
            head = (head + 1) % events.length;
            if (count < events.length) count++;
        } catch (Exception ignored) {
            // A single malformed record must never crash the tick loop.
        }
    }

    /** Dump the buffer in chronological order (oldest first). */
    public static String dump() {
        StringBuilder sb = new StringBuilder(count * 64 + 32);
        for (int i = 0; i < count; i++) {
            sb.append(events[(head - count + i + events.length) % events.length]).append('\n');
        }
        return sb.toString();
    }

    public static int size() {
        return count;
    }

    public static void clear() {
        head = 0;
        count = 0;
    }

    /**
     * Rebuild the backing array to the new capacity, preserving the most recent
     * {@code min(count, cap)} events in chronological order.
     */
    private static void resize(int cap) {
        int n = Math.min(count, cap);
        String[] next = new String[cap];
        for (int i = 0; i < n; i++) {
            next[i] = events[(head - count + i + events.length) % events.length];
        }
        events = next;
        head = n % cap;
        count = n;
    }

    /**
     * Build a human-readable line by substituting {@code {}} placeholders with
     * the given arguments. Uses manual substitution (not {@code String.format})
     * so literal {@code %} in messages can never throw.
     */
    static String buildLine(String category, String fmt, Object... args) {
        StringBuilder sb = new StringBuilder(fmt.length() + 16);
        sb.append('[').append(category).append("] ");
        int arg = 0;
        for (int i = 0; i < fmt.length(); i++) {
            char c = fmt.charAt(i);
            if (c == '{' && i + 1 < fmt.length() && fmt.charAt(i + 1) == '}' && arg < args.length) {
                sb.append(args[arg++]);
                i++;
            } else {
                sb.append(c);
            }
        }
        // Append any extra args that had no placeholder.
        while (arg < args.length) {
            sb.append(' ').append(args[arg++]);
        }
        return sb.toString();
    }
}
