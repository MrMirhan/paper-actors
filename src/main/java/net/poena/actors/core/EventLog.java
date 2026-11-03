package net.poena.actors.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;

/** Bounded, ordered log of what the actors did. Callers poll with the last id they saw. */
public final class EventLog {
    private final int capacity;
    private final ArrayDeque<JsonObject> events = new ArrayDeque<>();
    private long lastId;

    public EventLog(int capacity) {
        this.capacity = capacity;
    }

    public synchronized long add(String ev, JsonObject data) {
        JsonObject e = new JsonObject();
        e.addProperty("id", ++lastId);
        e.addProperty("ev", ev);
        e.addProperty("ts", System.currentTimeMillis());
        for (var entry : data.entrySet()) {
            if (!e.has(entry.getKey())) {
                e.add(entry.getKey(), entry.getValue());
            }
        }
        events.addLast(e);
        while (events.size() > capacity) {
            events.pollFirst();
        }
        return lastId;
    }

    public synchronized JsonObject since(long after, int limit) {
        JsonArray out = new JsonArray();
        for (JsonObject e : events) {
            if (e.get("id").getAsLong() > after && out.size() < limit) {
                out.add(e);
            }
        }
        JsonObject res = new JsonObject();
        res.add("events", out);
        res.addProperty("last", lastId);
        return res;
    }
}
