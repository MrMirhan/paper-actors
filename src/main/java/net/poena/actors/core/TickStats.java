package net.poena.actors.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Arrays;

/**
 * Tick times as the metrics give them: the last 100 ticks' average, p95 and max in milliseconds, and the ticks per second over
 * the last 1, 5 and 15 minutes. Paper counts these itself; a server that does not (Minestom) records each tick here.
 */
public final class TickStats {
    public static final int RECENT = 100;
    private static final int WINDOW_S = 15 * 60;

    private final long[] recent = new long[RECENT];
    private int recentAt;
    /** Ticks finished in each second of the last 15 minutes, by epoch second. */
    private final int[] perSecond = new int[WINDOW_S];
    private final long[] secondOf = new long[WINDOW_S];
    private final long startedSecond;

    public TickStats(long nowMillis) {
        this.startedSecond = nowMillis / 1000;
    }

    private static double ms(double nanos) {
        return Math.round(nanos / 10_000.0) / 100.0;
    }

    /** Average, p95 and max in milliseconds of the non-zero tick times given in nanoseconds; empty when there are none. */
    public static JsonObject summary(long[] nanos) {
        long[] sorted = Arrays.stream(nanos).filter(n -> n > 0).sorted().toArray();
        JsonObject o = new JsonObject();
        if (sorted.length == 0) {
            return o;
        }
        o.addProperty("avg", ms(Arrays.stream(sorted).average().orElse(0)));
        o.addProperty("p95", ms(sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * 0.95) - 1)]));
        o.addProperty("max", ms(sorted[sorted.length - 1]));
        o.addProperty("samples", sorted.length);
        return o;
    }

    /** One tick that took `nanos`, finished at `nowMillis`. */
    public synchronized void record(long nanos, long nowMillis) {
        recent[recentAt] = nanos;
        recentAt = (recentAt + 1) % RECENT;
        long second = nowMillis / 1000;
        int i = (int) (second % WINDOW_S);
        if (secondOf[i] != second) {
            secondOf[i] = second;
            perSecond[i] = 0;
        }
        perSecond[i]++;
    }

    /** The tick times of the last 100 ticks. */
    public synchronized JsonObject mspt() {
        return summary(recent.clone());
    }

    /**
     * Ticks per second over the last 1, 5 and 15 minutes (at most 20), from the whole seconds before `nowMillis`. A window longer
     * than the time since the start counts only that time.
     */
    public synchronized JsonArray tps(long nowMillis) {
        long now = nowMillis / 1000;
        JsonArray out = new JsonArray();
        for (int minutes : new int[] {1, 5, 15}) {
            long span = Math.min(minutes * 60L, Math.max(1, now - startedSecond));
            long ticks = 0;
            for (long s = now - span; s < now; s++) {
                int i = (int) (Math.floorMod(s, WINDOW_S));
                if (secondOf[i] == s) {
                    ticks += perSecond[i];
                }
            }
            out.add(Math.round(Math.min(20, (double) ticks / span) * 100) / 100.0);
        }
        return out;
    }
}
