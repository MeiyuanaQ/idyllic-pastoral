package com.crispyraccoon.pastoralcraft.crop;

/**
 * Gate-independent last-known-alive watchdog. A hard freeze or native crash never
 * fires ServerStopping, so the on-shutdown dump needs an always-on trace of where
 * the server main thread last made progress. Server main thread writes only.
 */
public final class DebugWatchdog {
    private DebugWatchdog() {
    }

    private static volatile int lastServerTick;
    private static volatile long lastHeartbeatMillis = System.currentTimeMillis();
    private static volatile String lastLevelState = "(none)";
    private static volatile String lastCatchUp = "(none)";

    /** Called once per ServerTickEvent.Post on the server main thread. */
    public static void heartbeat(int serverTick) {
        lastServerTick = serverTick;
        lastHeartbeatMillis = System.currentTimeMillis();
    }

    /** Called once per periodic catch-up cycle per level. */
    public static void levelState(String state) {
        lastLevelState = state;
    }

    /**
     * Records the last catch-up progress point (chunk / entry index). Gate-independent
     * and always on: a freeze inside catch-up never fires ServerStopping, so the
     * shutdown dump needs this to show which chunk/entry was last being processed.
     */
    public static void catchUpProgress(String state) {
        lastCatchUp = state;
    }

    public static String summary() {
        return "serverTick=" + lastServerTick
                + " lastHeartbeatMsAgo=" + (System.currentTimeMillis() - lastHeartbeatMillis)
                + " level=" + lastLevelState
                + " catchup=" + lastCatchUp;
    }
}
