package net.poena.actors.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SceneRunnerTest {
    private static final String SCENE = """
            {"seed": "take1", "duration": 4, "settle": 0.5,
             "actors": [{"name": "Rambox", "profile": "tryhard", "at": [0,64,0]}, {"name": "Herobrine", "at": [3,64,0]}],
             "setup": ["/time set day"], "cleanup": ["/weather clear"],
             "timeline": [{"t": 2, "actor": "Rambox", "do": "fight", "target": "Herobrine", "style": "1.8", "seconds": 1},
                          {"t": 0, "actor": "Rambox", "do": "watch", "target": "Herobrine", "seconds": 1.5}]}
            """;

    private static JsonObject scene(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static class Recorder implements SceneRunner.Sink {
        final List<String> calls = new ArrayList<>();

        public void spawn(JsonObject actor) {
            calls.add("spawn " + actor.get("name").getAsString());
        }

        public void act(String actor, JsonObject step) {
            calls.add("act " + actor + " " + step.get("do").getAsString());
        }

        public void command(String command) {
            calls.add("cmd " + command);
        }

        public void despawnAll() {
            calls.add("despawn");
        }

        public void emit(String ev, JsonObject data) {
            calls.add("ev " + ev);
        }
    }

    @Test
    void playsInOrderAndCountsGameTicks() {
        Recorder rec = new Recorder();
        SceneRunner run = new SceneRunner(scene(SCENE), rec, 8);
        int ticks = 0;
        while (run.running()) {
            run.tick();
            ticks++;
            assertTrue(ticks < 1000, "scene never ended");
        }
        // 1 spawn tick + 10 settle ticks + 80 run ticks.
        assertEquals(91, ticks);
        assertEquals(List.of("spawn Rambox", "spawn Herobrine", "cmd /time set day", "ev ready", "ev step", "act Rambox watch", "ev step",
                "act Rambox fight", "cmd /weather clear", "despawn", "ev end"), rec.calls);
    }

    @Test
    void stepsSortByTimeNotByFileOrder() {
        Recorder rec = new Recorder();
        SceneRunner run = new SceneRunner(scene(SCENE), rec, 8);
        while (run.running()) {
            run.tick();
        }
        assertTrue(rec.calls.indexOf("act Rambox watch") < rec.calls.indexOf("act Rambox fight"));
    }

    @Test
    void abortCleansUpAndSaysWhy() {
        Recorder rec = new Recorder();
        SceneRunner run = new SceneRunner(scene(SCENE), rec, 8);
        run.tick();
        run.abort();
        assertFalse(run.running());
        assertTrue(rec.calls.containsAll(List.of("cmd /weather clear", "despawn", "ev end")));
        run.abort();
        assertEquals(1, rec.calls.stream().filter("ev end"::equals).count());
    }

    @Test
    void despawnStillHappensWhenACleanupCommandThrows() {
        Recorder rec = new Recorder() {
            @Override
            public void command(String command) {
                if (command.startsWith("/weather")) {
                    throw new IllegalStateException("boom");
                }
                super.command(command);
            }
        };
        SceneRunner run = new SceneRunner(scene(SCENE), rec, 8);
        run.tick();
        assertThrows(IllegalStateException.class, run::abort);
        assertTrue(rec.calls.contains("despawn"));
        assertTrue(rec.calls.contains("ev end"));
    }

    private static void refused(String json, String needle) {
        ApiException e = assertThrows(ApiException.class, () -> SceneRunner.validate(scene(json), 8));
        assertEquals(400, e.status());
        assertTrue(e.getMessage().contains(needle), e.getMessage());
    }

    @Test
    void invalidScenesAreRefusedBeforeAnythingSpawns() {
        refused("{\"duration\": 5, \"actors\": []}", "at least one actor");
        refused("{\"duration\": 0, \"actors\": [{\"name\":\"Rambox\"}]}", "duration");
        refused("{\"duration\": 5, \"actors\": [{\"name\":\"a\"}]}", "3-16");
        refused("{\"duration\": 5, \"actors\": [{\"name\":\"Rambox\"},{\"name\":\"Rambox\"}]}", "twice");
        refused("{\"duration\": 5, \"actors\": [{\"name\":\"Rambox\"}], \"timeline\": [{\"t\":1,\"actor\":\"Ghost\",\"do\":\"idle\"}]}", "not in this scene");
        refused("{\"duration\": 5, \"actors\": [{\"name\":\"Rambox\"}], \"timeline\": [{\"t\":1,\"actor\":\"Rambox\",\"do\":\"bridge\"}]}", "bridge");
        refused("{\"duration\": 5, \"actors\": [{\"name\":\"Rambox\"}], \"timeline\": [{\"t\":9,\"actor\":\"Rambox\",\"do\":\"idle\"}]}", "between 0 and duration");
    }

    @Test
    void tooManyActorsAreRefused() {
        ApiException e = assertThrows(ApiException.class,
                () -> SceneRunner.validate(scene("{\"duration\": 5, \"actors\": [{\"name\":\"Rambox\"},{\"name\":\"Herobrine\"}]}"), 1));
        assertTrue(e.getMessage().contains("at most 1"));
    }

    @Test
    void handActionsAreSceneSteps() {
        for (String action : List.of("goto", "break", "place", "use", "use_on", "interact", "click", "drag", "close", "button", "rename", "trade", "sign",
                "select", "drop", "sneak", "jump", "command", "swap_hands")) {
            SceneRunner.validate(scene("{\"duration\": 5, \"actors\": [{\"name\":\"Rambox\"}], \"timeline\": [{\"t\":1,\"actor\":\"Rambox\",\"do\":\""
                    + action + "\"}]}"), 8);
        }
    }
}
