package com.crispyraccoon.pastoralcraft.crop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link DebugRingBuffer} string-building and empty-state
 * behavior. The ring's {@code record} path self-gates on {@link DebugGate},
 * which stays disabled when the config is not loaded, so these tests exercise
 * the pure helpers ({@code buildLine}, {@code dump}, {@code clear}, {@code size})
 * without touching the config system.
 */
class DebugRingBufferTest {

    @Test
    void buildLineSubstitutesPlaceholders() {
        String line = DebugRingBuffer.buildLine("GROWTH", "sugar cane grew bottom={} height={}", "pos", 3);
        assertEquals("[GROWTH] sugar cane grew bottom=pos height=3", line);
    }

    @Test
    void buildLineToleratesPercentLiterals() {
        // A literal '%' must not throw (String.format would); manual substitution
        // leaves it untouched.
        String line = DebugRingBuffer.buildLine("PERF", "progress=100% at {}", "pos");
        assertEquals("[PERF] progress=100% at pos", line);
    }

    @Test
    void buildLineLeavesUnmatchedPlaceholders() {
        // More placeholders than args: the leftover '{}' stays as-is.
        String line = DebugRingBuffer.buildLine("DATA", "a={} b={}", "onlyOne");
        assertEquals("[DATA] a=onlyOne b={}", line);
    }

    @Test
    void emptyBufferDumpsEmptyAndClears() {
        DebugRingBuffer.clear();
        assertEquals(0, DebugRingBuffer.size());
        assertEquals("", DebugRingBuffer.dump());
    }

    @Test
    void debugGateDefaultsDisabledWithoutConfig() {
        // Before refreshCache() runs (no config loaded), every module reads off.
        for (DebugGate.DebugModule m : DebugGate.DebugModule.values()) {
            assertFalse(DebugGate.enabled(m));
        }
        assertFalse(DebugGate.growth());
        assertFalse(DebugGate.stem());
        assertFalse(DebugGate.sideEffect());
        assertFalse(DebugGate.catchUp());
        assertFalse(DebugGate.data());
        assertFalse(DebugGate.perf());
        assertFalse(DebugGate.ring());
        assertFalse(DebugGate.commands());
    }

    @Test
    void debugGateEnumHasEightModules() {
        assertEquals(8, DebugGate.DebugModule.values().length);
    }
}
