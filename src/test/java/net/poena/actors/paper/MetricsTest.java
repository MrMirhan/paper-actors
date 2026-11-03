package net.poena.actors.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class MetricsTest {
    @Test
    void tickTimesGiveAverageP95AndMaxInMilliseconds() {
        long[] nanos = new long[100];
        for (int i = 0; i < 100; i++) {
            nanos[i] = (i + 1) * 1_000_000L; // 1..100 ms
        }
        JsonObject o = Metrics.tickTimes(nanos);
        assertEquals(50.5, o.get("avg").getAsDouble());
        assertEquals(95.0, o.get("p95").getAsDouble());
        assertEquals(100.0, o.get("max").getAsDouble());
        assertEquals(100, o.get("samples").getAsInt());
    }

    @Test
    void emptySamplesGiveNothing() {
        assertEquals(0, Metrics.tickTimes(new long[] {0, 0}).size());
    }
}
