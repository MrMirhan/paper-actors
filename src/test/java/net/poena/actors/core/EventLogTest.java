package net.poena.actors.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class EventLogTest {
    @Test
    void pollingWithTheLastIdReturnsOnlyNewEvents() {
        EventLog log = new EventLog(10);
        for (int i = 0; i < 3; i++) {
            log.add("hit", new JsonObject());
        }
        JsonObject first = log.since(0, 100);
        assertEquals(3, first.getAsJsonArray("events").size());
        assertEquals(3, first.get("last").getAsLong());
        log.add("done", new JsonObject());
        JsonObject next = log.since(3, 100);
        assertEquals(1, next.getAsJsonArray("events").size());
        assertEquals("done", next.getAsJsonArray("events").get(0).getAsJsonObject().get("ev").getAsString());
    }

    @Test
    void oldestEventsFallOffAndLimitApplies() {
        EventLog log = new EventLog(3);
        for (int i = 0; i < 5; i++) {
            log.add("e", new JsonObject());
        }
        assertEquals(3, log.since(0, 100).getAsJsonArray("events").size());
        assertEquals(3, log.since(0, 100).getAsJsonArray("events").get(0).getAsJsonObject().get("id").getAsLong());
        assertEquals(1, log.since(0, 1).getAsJsonArray("events").size());
    }

    @Test
    void callerDataCannotOverwriteTheEnvelope() {
        EventLog log = new EventLog(3);
        JsonObject d = new JsonObject();
        d.addProperty("ev", "spoof");
        d.addProperty("id", 99);
        log.add("real", d);
        JsonObject e = log.since(0, 1).getAsJsonArray("events").get(0).getAsJsonObject();
        assertEquals("real", e.get("ev").getAsString());
        assertEquals(1, e.get("id").getAsLong());
    }
}
