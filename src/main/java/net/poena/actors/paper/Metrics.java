package net.poena.actors.paper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Arrays;
import org.bukkit.Bukkit;
import org.bukkit.World;

/** How the server holds up, from Paper's own counters: TPS, tick times (average, p95, max), and what keeps it busy. */
final class Metrics {
    private Metrics() {
    }

    private static double ms(double nanos) {
        return Math.round(nanos / 10_000.0) / 100.0;
    }

    /** The tick times of the last 100 ticks, in milliseconds: average, p95 and max. */
    static JsonObject tickTimes(long[] nanos) {
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

    static JsonObject sample() {
        JsonObject o = new JsonObject();
        double[] tps = Bukkit.getTPS();
        JsonArray t = new JsonArray();
        for (double v : tps) {
            t.add(Math.round(Math.min(20, v) * 100) / 100.0);
        }
        o.add("tps", t);
        o.add("mspt", tickTimes(Bukkit.getServer().getTickTimes()));
        int chunks = 0;
        int entities = 0;
        JsonArray worlds = new JsonArray();
        for (World w : Bukkit.getWorlds()) {
            JsonObject x = new JsonObject();
            int c = w.getLoadedChunks().length;
            int e = w.getEntityCount();
            x.addProperty("name", w.getName());
            x.addProperty("chunks", c);
            x.addProperty("entities", e);
            x.addProperty("players", w.getPlayerCount());
            worlds.add(x);
            chunks += c;
            entities += e;
        }
        o.addProperty("chunks", chunks);
        o.addProperty("entities", entities);
        o.addProperty("players", Bukkit.getOnlinePlayers().size());
        o.add("worlds", worlds);
        Runtime r = Runtime.getRuntime();
        JsonObject heap = new JsonObject();
        heap.addProperty("usedMb", (r.totalMemory() - r.freeMemory()) / (1024 * 1024));
        heap.addProperty("maxMb", r.maxMemory() / (1024 * 1024));
        o.add("heap", heap);
        o.addProperty("at", System.currentTimeMillis());
        return o;
    }
}
