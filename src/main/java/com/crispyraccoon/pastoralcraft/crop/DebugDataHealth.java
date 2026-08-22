package com.crispyraccoon.pastoralcraft.crop;

/**
 * Scans a chunk's tracked {@code plantedDay} values for the two anomaly classes
 * behind the "R1" stall reports: negative planted days and absurdly old planted
 * days (relative to a horizon). Pure-function scan for unit testing, plus a
 * thin live-chunk scanner used by the catch-up entry points.
 */
public final class DebugDataHealth {
    private DebugDataHealth() {
    }

    public enum Status {
        OK,
        NEGATIVE,
        OVER_HORIZON
    }

    /** Aggregated result of a {@code plantedDay} scan. */
    public record HealthReport(Status status, int count, int min, int max,
                               int negativeCount, int elapsedMax) {
        public boolean ok() {
            return status == Status.OK;
        }

        @Override
        public String toString() {
            return "count=" + count + " min=" + min + " max=" + max
                    + " negative=" + negativeCount + " elapsedMax=" + elapsedMax
                    + " status=" + status;
        }
    }

    /** Latest non-OK report, for status/dump output (main-thread written/read). */
    private static volatile String lastNonOk = "(no scan yet)";

    /**
     * Pure-function scan of a chunk's planted days.
     *
     * @param plantedDays the entries' planted days
     * @param currentDay  the current solar day
     * @param horizon     maximum acceptable {@code currentDay - plantedDay}
     * @return the aggregate health report
     */
    public static HealthReport scan(int[] plantedDays, int currentDay, int horizon) {
        if (plantedDays.length == 0) {
            return new HealthReport(Status.OK, 0, 0, 0, 0, 0);
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        int negative = 0;
        int elapsedMax = 0;
        for (int pd : plantedDays) {
            if (pd < 0) negative++;
            if (pd < min) min = pd;
            if (pd > max) max = pd;
            int elapsed = currentDay - pd;
            if (elapsed > elapsedMax) elapsedMax = elapsed;
        }
        Status status = Status.OK;
        if (negative > 0) {
            status = Status.NEGATIVE;
        } else if (elapsedMax > horizon) {
            status = Status.OVER_HORIZON;
        }
        return new HealthReport(status, plantedDays.length, min, max, negative, elapsedMax);
    }

    /**
     * A permissive horizon for "planted day too old": one full year plus a
     * generous multi-stage margin. Deliberately loose to avoid false-positive
     * spam (red line: prefer under-reporting to over-reporting).
     */
    public static int defaultHorizon() {
        int daysPerStage;
        try {
            daysPerStage = CropGrowthConfig.DEFAULT_DAYS_PER_STAGE.get();
        } catch (Exception ignored) {
            daysPerStage = 3;
        }
        int termLength;
        try {
            termLength = CropGrowthConfig.CATCH_UP_SEASON_LENGTH.get();
        } catch (Exception ignored) {
            termLength = 7;
        }
        // maxAge 7 * daysPerStage + one full year (24 terms * termLength).
        return 7 * daysPerStage + termLength * 24;
    }

    /** Record the latest non-OK report for status/dump output (main thread). */
    public static void noteNonOk(HealthReport report, Object chunkPos) {
        lastNonOk = "chunk=" + chunkPos + " " + report;
    }

    /** A short summary line for status/dump output. */
    public static String summary() {
        return "last non-OK: " + lastNonOk;
    }
}
