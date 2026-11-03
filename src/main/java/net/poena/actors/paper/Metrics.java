package net.poena.actors.paper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.poena.actors.core.TickStats;
import org.bukkit.Bukkit;
import org.bukkit.World;

/** How the server holds up, from Paper's own counters: TPS, tick times (average, p95, max), and what keeps it busy. */
final class Metrics {
    private Metrics() {
    }

    static JsonObject sample() {
        JsonObject o = new JsonObject();
        double[] tps = Bukkit.getTPS();
        JsonArray t = new JsonArray();
        for (double v : tps) {
            t.add(Math.round(Math.min(20, v) * 100) / 100.0);
        }
        o.add("tps", t);
        o.add("mspt", TickStats.summary(Bukkit.getServer().getTickTimes()));
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
