package net.poena.actors.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Plays a scene file (the format of tools/scene.py) in game ticks: spawn, setup commands, settle, "ready", the timeline, cleanup, "end".
 * Time is counted in server ticks, so a scene under a lowered tick rate plays in real slow motion and a retake is tick-exact.
 */
public final class SceneRunner {
    public static final Set<String> ACTIONS = Set.of("idle", "look", "watch", "stroll", "fight", "equip", "chat", "stop", "walk", "swing", "attack", "teleport",
            "jump", "sneak", "goto", "select", "use", "use_on", "place", "break", "interact", "drop", "swap_hands", "command", "click", "drag", "close",
            "button", "rename", "trade", "sign");
    public static final int MAX_DURATION_S = 600;

    public interface Sink {
        void spawn(JsonObject actor);

        void act(String actor, JsonObject step);

        void command(String command);

        void despawnAll();

        void emit(String ev, JsonObject data);
    }

    private enum Phase { START, SETTLE, RUN, DONE }

    private record Step(long tick, String actor, JsonObject body) {
    }

    private final JsonObject scene;
    private final Sink sink;
    private final List<Step> steps = new ArrayList<>();
    private final long settleTicks;
    private final long durationTicks;
    private Phase phase = Phase.START;
    private long phaseTick;
    private int next;

    public SceneRunner(JsonObject scene, Sink sink, int maxActors) {
        validate(scene, maxActors);
        this.scene = scene;
        this.sink = sink;
        this.settleTicks = Math.round((scene.has("settle") ? scene.get("settle").getAsDouble() : 1.5) * 20);
        this.durationTicks = Math.round(scene.get("duration").getAsDouble() * 20);
        if (scene.has("timeline")) {
            for (JsonElement e : scene.getAsJsonArray("timeline")) {
                JsonObject s = e.getAsJsonObject();
                steps.add(new Step(Math.round(s.get("t").getAsDouble() * 20), s.get("actor").getAsString(), s));
            }
        }
        steps.sort(Comparator.comparingLong(Step::tick));
    }

    public static void validate(JsonObject scene, int maxActors) {
        if (!scene.has("actors") || !scene.get("actors").isJsonArray() || scene.getAsJsonArray("actors").isEmpty()) {
            throw new ApiException(400, "a scene needs at least one actor");
        }
        JsonArray actors = scene.getAsJsonArray("actors");
        if (actors.size() > maxActors) {
            throw new ApiException(400, "at most " + maxActors + " actors");
        }
        Set<String> names = new java.util.HashSet<>();
        for (JsonElement e : actors) {
            String n = e.isJsonObject() && e.getAsJsonObject().has("name") ? e.getAsJsonObject().get("name").getAsString() : null;
            if (!Gate.validName(n)) {
                throw new ApiException(400, "actor name must be 3-16 letters, digits or _");
            }
            if (!names.add(n)) {
                throw new ApiException(400, "actor " + n + " twice");
            }
        }
        double duration;
        try {
            duration = scene.get("duration").getAsDouble();
        } catch (RuntimeException e) {
            throw new ApiException(400, "duration is missing");
        }
        if (!(duration >= 1 && duration <= MAX_DURATION_S)) {
            throw new ApiException(400, "duration: 1-" + MAX_DURATION_S + " seconds");
        }
        if (scene.has("timeline")) {
            int i = 0;
            for (JsonElement e : scene.getAsJsonArray("timeline")) {
                String where = "timeline[" + i++ + "]";
                if (!e.isJsonObject()) {
                    throw new ApiException(400, where + " must be an object");
                }
                JsonObject s = e.getAsJsonObject();
                if (!s.has("actor") || !names.contains(s.get("actor").getAsString())) {
                    throw new ApiException(400, where + ": actor is not in this scene");
                }
                String action = s.has("do") ? s.get("do").getAsString() : "";
                if (!ACTIONS.contains(action)) {
                    throw new ApiException(400, where + ": the paper body cannot do '" + action + "' (bridge and glide need a mineflayer bot)");
                }
                double t = s.has("t") ? s.get("t").getAsDouble() : -1;
                if (t < 0 || t > duration) {
                    throw new ApiException(400, where + ": t must be between 0 and duration");
                }
            }
        }
    }

    public boolean running() {
        return phase != Phase.DONE;
    }

    /** Call once per server tick. */
    public void tick() {
        switch (phase) {
            case START -> {
                for (JsonElement a : scene.getAsJsonArray("actors")) {
                    sink.spawn(a.getAsJsonObject());
                }
                forEach("setup");
                phase = Phase.SETTLE;
                phaseTick = 0;
            }
            case SETTLE -> {
                if (++phaseTick >= settleTicks) {
                    JsonObject ready = new JsonObject();
                    JsonArray names = new JsonArray();
                    scene.getAsJsonArray("actors").forEach(a -> names.add(a.getAsJsonObject().get("name").getAsString()));
                    ready.add("actors", names);
                    sink.emit("ready", ready);
                    phase = Phase.RUN;
                    phaseTick = 0;
                }
            }
            case RUN -> {
                while (next < steps.size() && steps.get(next).tick() <= phaseTick) {
                    Step s = steps.get(next++);
                    JsonObject data = new JsonObject();
                    data.addProperty("at", s.tick() / 20.0);
                    data.addProperty("actor", s.actor());
                    data.addProperty("do", s.body().get("do").getAsString());
                    sink.emit("step", data);
                    sink.act(s.actor(), s.body());
                }
                if (++phaseTick >= durationTicks) {
                    finish("end");
                }
            }
            case DONE -> {
            }
        }
    }

    /** Stop now: cleanup commands run, actors leave, "end" says why. */
    public void abort() {
        if (phase != Phase.DONE) {
            finish("aborted");
        }
    }

    private void finish(String how) {
        phase = Phase.DONE;
        try {
            forEach("cleanup");
        } finally {
            sink.despawnAll();
            JsonObject end = new JsonObject();
            if (!how.equals("end")) {
                end.addProperty("reason", how);
            }
            sink.emit("end", end);
        }
    }

    private void forEach(String key) {
        if (scene.has(key)) {
            for (JsonElement c : scene.getAsJsonArray(key)) {
                sink.command(c.getAsString());
            }
        }
    }
}
