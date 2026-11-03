package net.poena.actors.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class TickStatsTest {
    @Test
    void tickTimesGiveAverageP95AndMaxInMilliseconds() {
        long[] nanos = new long[100];
        for (int i = 0; i < 100; i++) {
            nanos[i] = (i + 1) * 1_000_000L; // 1..100 ms
        }
        JsonObject o = TickStats.summary(nanos);
        assertEquals(50.5, o.get("avg").getAsDouble());
        assertEquals(95.0, o.get("p95").getAsDouble());
        assertEquals(100.0, o.get("max").getAsDouble());
        assertEquals(100, o.get("samples").getAsInt());
    }

    @Test
    void emptySamplesGiveNothing() {
        assertEquals(0, TickStats.summary(new long[] {0, 0}).size());
    }

    @Test
    void recordedTicksGiveTheLastHundredAndTicksPerSecond() {
        long start = 1_000_000_000L;
        TickStats t = new TickStats(start);
        // 120 seconds: the first 60 at 20 ticks a second, the last 60 at 10.
        for (int s = 0; s < 120; s++) {
            int ticks = s < 60 ? 20 : 10;
            for (int k = 0; k < ticks; k++) {
                t.record((s < 60 ? 2 : 8) * 1_000_000L, start + s * 1000L + k * (1000L / ticks));
            }
        }
        JsonArray tps = t.tps(start + 120_000);
        assertEquals(10.0, tps.get(0).getAsDouble(), "the last minute");
        assertEquals(15.0, tps.get(1).getAsDouble(), "five minutes, of which two have passed");
        assertEquals(15.0, tps.get(2).getAsDouble());
        assertEquals(8.0, t.mspt().get("avg").getAsDouble(), "the last 100 ticks were all 8 ms");
        assertEquals(100, t.mspt().get("samples").getAsInt());
    }

    @Test
    void ticksPerSecondNeverPassTwenty() {
        TickStats t = new TickStats(0);
        for (int k = 0; k < 50; k++) {
            t.record(1_000_000L, 500);
        }
        assertEquals(20.0, t.tps(1000).get(0).getAsDouble());
    }
}
