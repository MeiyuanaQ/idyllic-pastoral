package com.crispyraccoon.pastoralcraft.crop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure-function {@link DebugDataHealth#scan} anomaly scan.
 * No Minecraft server or config system is required — the scan only reads an
 * {@code int[]} of planted days.
 */
class DebugDataHealthTest {

    @Test
    void emptyArrayIsOk() {
        DebugDataHealth.HealthReport r = DebugDataHealth.scan(new int[0], 100, 1000);
        assertTrue(r.ok());
        assertEquals(0, r.count());
        assertEquals(DebugDataHealth.Status.OK, r.status());
    }

    @Test
    void normalPlantedDaysAreOk() {
        DebugDataHealth.HealthReport r = DebugDataHealth.scan(new int[]{95, 90, 97}, 100, 1000);
        assertTrue(r.ok());
        assertEquals(3, r.count());
        assertEquals(90, r.min());
        assertEquals(97, r.max());
        assertEquals(0, r.negativeCount());
        assertEquals(10, r.elapsedMax());
    }

    @Test
    void negativePlantedDayIsFlagged() {
        DebugDataHealth.HealthReport r = DebugDataHealth.scan(new int[]{95, -1, 90}, 100, 1000);
        assertEquals(DebugDataHealth.Status.NEGATIVE, r.status());
        assertEquals(1, r.negativeCount());
    }

    @Test
    void overHorizonPlantedDayIsFlagged() {
        // currentDay=1000, plantedDay=0 -> elapsed=1000 > horizon=500
        DebugDataHealth.HealthReport r = DebugDataHealth.scan(new int[]{900, 0, 950}, 1000, 500);
        assertEquals(DebugDataHealth.Status.OVER_HORIZON, r.status());
        assertEquals(1000, r.elapsedMax());
        assertEquals(0, r.negativeCount());
    }

    @Test
    void negativeTakesPriorityOverHorizon() {
        // Both anomalies present: NEGATIVE must win (it is the stricter signal).
        DebugDataHealth.HealthReport r = DebugDataHealth.scan(new int[]{-5, 0}, 1000, 500);
        assertEquals(DebugDataHealth.Status.NEGATIVE, r.status());
    }

    @Test
    void boundaryElapsedEqualToHorizonIsOk() {
        // elapsed == horizon is acceptable (only strictly greater is flagged).
        DebugDataHealth.HealthReport r = DebugDataHealth.scan(new int[]{500}, 1000, 500);
        assertEquals(DebugDataHealth.Status.OK, r.status());
        assertEquals(500, r.elapsedMax());
    }
}
