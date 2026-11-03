package net.poena.actors.minestom;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.MainHand;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.entity.EntityAttackEvent;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.AsyncPlayerPreLoginEvent;
import net.minestom.server.event.player.PlayerDeathEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.server.ServerTickMonitorEvent;
import net.minestom.server.event.Event;
import net.minestom.server.instance.Instance;
import net.minestom.server.message.ChatMessageType;
import net.minestom.server.network.ConnectionManager;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.player.ClientSettings;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.timer.Task;
import net.minestom.server.timer.TaskSchedule;
import net.poena.actors.core.Api;
import net.poena.actors.core.ApiException;
import net.poena.actors.core.Control;
import net.poena.actors.core.EventLog;
import net.poena.actors.core.Gate;
import net.poena.actors.core.Rng;
import net.poena.actors.core.SceneRunner;
import net.poena.actors.core.Signer;
import net.poena.actors.core.SkinLibrary;
import net.poena.actors.core.TickStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * paper-actors on Minestom: fake players in a Minestom test server, behind the same signed HTTP API as the Paper plugin, so the
 * same clients (actors_client.py, the minecraft MCP server's actors tools) drive them. Start it from the test server's main once
 * MinecraftServer.init() has run:
 *
 * <pre>{@code
 * MinestomActors actors = MinestomActors.start(ActorSettings.of(world, new Pos(0, 41, 0))
 *         .allowedServers(List.of("shipyard-test"))
 *         .limits(200, 4));
 * }</pre>
 *
 * <p>An actor is a Player with a connection that has no socket (Minestom's own test players are made the same way). It joins
 * through the server's configuration and join steps, so the server's own AsyncPlayerConfigurationEvent and spawn listeners see it.
 * Everything it does goes to the server as a client's packets, and the game sees a person's events. The actors' work, and every
 * API call, runs at the start of a tick, before the instances tick.
 */
public final class MinestomActors implements Control {
    /**
     * The most actors one server can be asked for; the settings' limit is the real one. Higher than Paper's 1000: a Minestom actor
     * costs far less (README, "Load").
     */
    public static final int HARD_MAX = 10_000;
    private static final Logger LOG = LoggerFactory.getLogger(MinestomActors.class);

    private final ActorSettings cfg;
    private final String serverName;
    private final EventLog log = new EventLog(2000);
    private final TickStats ticks = new TickStats(System.currentTimeMillis());
    /** The actors' own share of each tick: the API calls, the crowd joining, the scene and every actor's brain and walk. */
    private final TickStats actorTicks = new TickStats(System.currentTimeMillis());
    private final Map<String, Actor> actors = new LinkedHashMap<>();
    private final Set<UUID> actorIds = ConcurrentHashMap.newKeySet();
    private final Set<String> actorNames = ConcurrentHashMap.newKeySet();
    private final ArrayDeque<JsonObject> queue = new ArrayDeque<>();
    private final ConcurrentLinkedQueue<Runnable> jobs = new ConcurrentLinkedQueue<>();
    private final EventNode<Event> events = EventNode.all("paper-actors");
    private Task tickTask;
    private Api api;
    private SceneRunner scene;
    private String currentSeed = "1";

    private MinestomActors(ActorSettings cfg, String serverName) {
        this.cfg = cfg;
        this.serverName = serverName;
    }

    /**
     * Starts the actors and their API, or throws IllegalStateException with the reason it refuses: this server is not in
     * allowedServers, the secret is too short, or the API is on another address than loopback with a secret under 32 characters.
     */
    public static MinestomActors start(ActorSettings cfg) {
        String serverName = cfg.effectiveServerName();
        String secret = cfg.effectiveSecret();
        Gate.refuseServer(serverName, cfg.serverPort, cfg.allowedServers).ifPresent(why -> {
            throw new IllegalStateException("paper-actors not starting: " + why);
        });
        Gate.refuseSecret(secret).ifPresent(why -> {
            throw new IllegalStateException("paper-actors not starting: " + why);
        });
        Gate.refuseRemote(cfg.host, secret).ifPresent(why -> {
            throw new IllegalStateException("paper-actors not starting: " + why);
        });
        MinestomActors actors = new MinestomActors(cfg, serverName);
        actors.listen();
        try {
            actors.api = new Api(InetAddress.getByName(cfg.host), cfg.port, new Signer(secret), actors, LOG::warn);
        } catch (IOException e) {
            actors.unlisten();
            throw new IllegalStateException("paper-actors not starting: cannot open the API port: " + e.getMessage(), e);
        }
        actors.api.start();
        if (!Gate.isLoopback(cfg.host)) {
            LOG.warn("The actor API listens on {}:{}, beyond this machine. Every request is signed; keep the secret private.", cfg.host, actors.api.port());
        }
        LOG.info("Actors ready: API on {}:{}, server '{}' is in allowedServers.", actors.api.address().getHostAddress(), actors.api.port(), serverName);
        return actors;
    }

    /** The API's port (useful when the settings asked for port 0). */
    public int port() {
        return api.port();
    }

    /** Whether `player` is an actor: a game that saves its players' data can leave actors out. */
    public boolean isActor(Player player) {
        return actorIds.contains(player.getUuid());
    }

    /** Removes every actor, closes the API and stops listening. */
    public void stop() {
        if (api != null) {
            api.close();
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        jobs.add(() -> {
            queue.clear();
            if (scene != null && scene.running()) {
                scene.abort();
            }
            despawnAll();
            done.complete(null);
        });
        try {
            done.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOG.warn("the actors were not all removed: {}", e.toString());
        }
        unlisten();
    }

    private void listen() {
        events.addListener(ServerTickMonitorEvent.class, e -> ticks.record((long) (e.getTickMonitor().getTickTime() * 1_000_000), System.currentTimeMillis()));
        // A real login may not take the name of a running actor.
        events.addListener(AsyncPlayerPreLoginEvent.class, e -> {
            if (actorNames.contains(e.getUsername().toLowerCase(Locale.ROOT))) {
                e.getConnection().kick(Component.text("That name is used by an actor."));
            }
        });
        events.addListener(PlayerDisconnectEvent.class, e -> {
            if (isActor(e.getPlayer())) {
                String name = e.getPlayer().getUsername();
                jobs.add(() -> onQuit(name));
            }
        });
        events.addListener(EntityAttackEvent.class, e -> {
            if (e.getEntity() instanceof Player p && isActor(p)) {
                Actor a = findActor(p.getUsername());
                JsonObject o = new JsonObject();
                o.addProperty("name", p.getUsername());
                o.addProperty("target", e.getTarget() instanceof Player t ? t.getUsername() : e.getTarget().getEntityType().name());
                o.addProperty("hits", a == null ? 0 : a.hits.incrementAndGet());
                log.add("hit", o);
            }
        });
        events.addListener(PlayerDeathEvent.class, e -> {
            if (isActor(e.getPlayer())) {
                JsonObject o = new JsonObject();
                o.addProperty("name", e.getPlayer().getUsername());
                log.add("died", o);
            }
        });
        MinecraftServer.getGlobalEventHandler().addChild(events);
        tickTask = MinecraftServer.getSchedulerManager().buildTask(this::tick).repeat(TaskSchedule.nextTick()).schedule();
    }

    private void unlisten() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        MinecraftServer.getGlobalEventHandler().removeChild(events);
    }

    private Actor findActor(String name) {
        synchronized (actors) {
            return actors.get(name);
        }
    }

    // ---- the tick: API calls, crowds joining, the scene, then each actor

    private void tick() {
        long start = System.nanoTime();
        try {
            tickActors();
        } finally {
            actorTicks.record(System.nanoTime() - start, System.currentTimeMillis());
        }
    }

    private void tickActors() {
        for (Runnable job; (job = jobs.poll()) != null; ) {
            job.run();
        }
        for (int i = 0; i < cfg.spawnsPerTick && !queue.isEmpty(); i++) {
            JsonObject req = queue.poll();
            try {
                spawnNow(req);
            } catch (ApiException e) {
                log.add("error", json("name", req.get("name").getAsString(), "error", e.getMessage()));
            }
            if (queue.isEmpty()) {
                log.add("crowd", json("state", "spawned", "actors", String.valueOf(actors.size())));
            }
        }
        if (scene != null && scene.running()) {
            scene.tick();
        }
        List<Actor> gone = new ArrayList<>();
        for (Actor a : actors.values()) {
            boolean alive;
            try {
                alive = a.tick();
            } catch (RuntimeException e) {
                LOG.warn("actor {} failed: {}", a.name, e.toString());
                alive = true;
            }
            if (!alive) {
                gone.add(a);
            }
        }
        for (Actor a : gone) {
            forget(a, "removed");
        }
    }

    private <T> T onTick(Supplier<T> work) {
        CompletableFuture<T> done = new CompletableFuture<>();
        jobs.add(() -> {
            try {
                done.complete(work.get());
            } catch (Throwable e) {
                done.completeExceptionally(e);
            }
        });
        try {
            return done.get(8, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ApiException a) {
                throw a;
            }
            LOG.warn("request failed: {}", e.getCause().toString());
            throw new ApiException(500, "internal error");
        } catch (TimeoutException e) {
            throw new ApiException(503, "server did not answer in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "interrupted");
        }
    }

    // ---- Control

    @Override
    public JsonObject state() {
        return onTick(() -> {
            JsonObject o = new JsonObject();
            String version = MinestomActors.class.getPackage().getImplementationVersion();
            o.addProperty("plugin", "minestom-actors " + (version == null ? "dev" : version));
            o.addProperty("server", "Minestom " + MinecraftServer.VERSION_NAME + " (" + serverName + ")");
            o.addProperty("scene", scene != null && scene.running());
            JsonArray list = new JsonArray();
            for (Actor a : actors.values()) {
                JsonObject x = new JsonObject();
                x.addProperty("name", a.name);
                x.addProperty("uuid", a.uuid.toString());
                Player p = a.player;
                if (a.joined() && p != null) {
                    Pos l = p.getPosition();
                    x.addProperty("world", worldName(p.getInstance()));
                    x.addProperty("x", l.x());
                    x.addProperty("y", l.y());
                    x.addProperty("z", l.z());
                    x.addProperty("yaw", l.yaw());
                    x.addProperty("pitch", l.pitch());
                    x.addProperty("health", p.getHealth());
                    x.addProperty("dead", p.isDead());
                }
                x.addProperty("hits", a.hits.get());
                x.addProperty("task", a.taskName());
                x.addProperty("profile", a.profileName);
                list.add(x);
            }
            o.add("actors", list);
            o.addProperty("max", cfg.maxActors);
            return o;
        });
    }

    @Override
    public JsonObject spawn(JsonObject req) {
        if (req.has("names") || req.has("count")) {
            return onTick(() -> enqueue(req));
        }
        return onTick(() -> {
            Actor a = spawnNow(req);
            JsonObject o = new JsonObject();
            o.addProperty("name", a.name);
            o.addProperty("uuid", a.uuid.toString());
            return o;
        });
    }

    /** A crowd: {names: [...]} or {prefix: "Bot", count: N}, with the fields of one spawn for all; spread: R places each within R blocks. */
    private JsonObject enqueue(JsonObject req) {
        List<String> names = new ArrayList<>();
        if (req.has("names") && req.get("names").isJsonArray()) {
            req.getAsJsonArray("names").forEach(e -> names.add(e.getAsString()));
        } else {
            String prefix = req.has("prefix") ? req.get("prefix").getAsString() : "Actor";
            int count = req.get("count").getAsInt();
            if (count < 1 || count > HARD_MAX) {
                throw new ApiException(400, "count is 1-" + HARD_MAX);
            }
            int width = String.valueOf(count).length();
            for (int i = 1; names.size() < count && i <= count * 4; i++) {
                String name = prefix + String.format(Locale.ROOT, "%0" + width + "d", i);
                if (!actors.containsKey(name)) {
                    names.add(name);
                }
            }
        }
        if (actors.size() + queue.size() + names.size() > cfg.maxActors) {
            throw new ApiException(409, "max-actors (" + cfg.maxActors + ") would be passed: " + actors.size() + " spawned, " + queue.size()
                    + " waiting, " + names.size() + " asked");
        }
        for (String name : names) {
            if (!Gate.validName(name)) {
                throw new ApiException(400, "'" + name + "' is not a valid name (3-16 letters, digits or _)");
            }
        }
        double spread = req.has("spread") ? req.get("spread").getAsDouble() : 0;
        Rng rng = new Rng((req.has("seed") ? req.get("seed").getAsString() : "1") + ":crowd");
        for (String name : names) {
            JsonObject one = req.deepCopy();
            one.remove("names");
            one.remove("count");
            one.remove("prefix");
            one.addProperty("name", name);
            if (spread > 0) {
                one.addProperty("spreadX", rng.range(-spread, spread));
                one.addProperty("spreadZ", rng.range(-spread, spread));
            }
            queue.add(one);
        }
        JsonObject o = new JsonObject();
        o.addProperty("queued", names.size());
        o.addProperty("perTick", cfg.spawnsPerTick);
        return o;
    }

    @Override
    public JsonObject actor(String name) {
        return onTick(() -> {
            Actor a = actors.get(name);
            if (a == null) {
                throw new ApiException(404, "no such actor");
            }
            if (!a.joined()) {
                throw new ApiException(409, name + " is still joining");
            }
            return Snapshot.of(a);
        });
    }

    @Override
    public JsonObject metrics() {
        return onTick(() -> {
            long now = System.currentTimeMillis();
            JsonObject o = new JsonObject();
            o.add("tps", ticks.tps(now));
            o.add("mspt", ticks.mspt());
            // Of each tick, what driving the actors took (the rest is the server's and the game's own work).
            o.add("actorsMspt", actorTicks.mspt());
            int chunks = 0;
            int entities = 0;
            JsonArray worlds = new JsonArray();
            for (Instance i : MinecraftServer.getInstanceManager().getInstances()) {
                int c = i.getChunks().size();
                int e = i.getEntities().size();
                JsonObject x = new JsonObject();
                x.addProperty("name", worldName(i));
                x.addProperty("chunks", c);
                x.addProperty("entities", e);
                x.addProperty("players", i.getPlayers().size());
                worlds.add(x);
                chunks += c;
                entities += e;
            }
            o.addProperty("chunks", chunks);
            o.addProperty("entities", entities);
            o.addProperty("players", MinecraftServer.getConnectionManager().getOnlinePlayerCount());
            o.add("worlds", worlds);
            Runtime r = Runtime.getRuntime();
            JsonObject heap = new JsonObject();
            heap.addProperty("usedMb", (r.totalMemory() - r.freeMemory()) / (1024 * 1024));
            heap.addProperty("maxMb", r.maxMemory() / (1024 * 1024));
            o.add("heap", heap);
            o.addProperty("at", now);
            o.addProperty("actors", actors.size());
            o.addProperty("waiting", queue.size());
            o.addProperty("maxActors", cfg.maxActors);
            return o;
        });
    }

    @Override
    public JsonObject despawn(JsonObject req) {
        return onTick(() -> {
            JsonObject o = new JsonObject();
            if (req.has("all") && req.get("all").getAsBoolean()) {
                int n = actors.size();
                queue.clear();
                despawnAll();
                o.addProperty("removed", n);
                return o;
            }
            if (req.has("prefix")) {
                String prefix = req.get("prefix").getAsString();
                int n = 0;
                for (Actor a : new ArrayList<>(actors.values())) {
                    if (a.name.startsWith(prefix)) {
                        despawnNow(a);
                        n++;
                    }
                }
                queue.removeIf(q -> q.get("name").getAsString().startsWith(prefix));
                o.addProperty("removed", n);
                return o;
            }
            Actor a = actors.get(requireName(req));
            if (a == null) {
                throw new ApiException(404, "no such actor");
            }
            despawnNow(a);
            o.addProperty("removed", 1);
            return o;
        });
    }

    @Override
    public JsonObject act(JsonObject req) {
        return onTick(() -> {
            Actor a = actors.get(requireName(req));
            if (a == null) {
                throw new ApiException(404, "no such actor");
            }
            a.act(req);
            return new JsonObject();
        });
    }

    @Override
    public JsonObject scenePlay(JsonObject req) {
        return onTick(() -> {
            if (scene != null && scene.running()) {
                throw new ApiException(409, "a scene is already running");
            }
            JsonObject sceneJson = (req.has("scene") && req.get("scene").isJsonObject() ? req.getAsJsonObject("scene") : req).deepCopy();
            if (sceneJson.has("tickRate")) {
                throw new ApiException(400, "tickRate is a Paper command; Minestom has no tick rate to change");
            }
            currentSeed = sceneJson.has("seed") && sceneJson.get("seed").isJsonPrimitive() ? sceneJson.get("seed").getAsString() : "1";
            scene = new SceneRunner(sceneJson, new SceneSink(), cfg.maxActors);
            log.add("scene", json("state", "start"));
            return new JsonObject();
        });
    }

    @Override
    public JsonObject sceneStop() {
        return onTick(() -> {
            if (scene == null || !scene.running()) {
                throw new ApiException(409, "no scene is running");
            }
            scene.abort();
            return new JsonObject();
        });
    }

    @Override
    public JsonObject events(long since, int limit) {
        return log.since(since, limit);
    }

    // ---- spawn and despawn (on the tick)

    private static String requireName(JsonObject req) {
        String name = req.has("name") && req.get("name").isJsonPrimitive() ? req.get("name").getAsString() : null;
        if (!Gate.validName(name)) {
            throw new ApiException(400, "name must be 3-16 letters, digits or _");
        }
        return name;
    }

    /** The name the API gives a world: "world" for the actors' own instance, else its dimension and the start of its UUID. */
    static String worldName(Instance i) {
        if (i == null) {
            return "";
        }
        return i.getDimensionType().key().value() + "-" + i.getUuid().toString().substring(0, 8);
    }

    private String worldOf(Instance i) {
        return i == cfg.instance ? "world" : worldName(i);
    }

    private Actor spawnNow(JsonObject req) {
        String name = requireName(req);
        if (actors.containsKey(name)) {
            throw new ApiException(409, name + " is already spawned");
        }
        if (actors.size() >= cfg.maxActors) {
            throw new ApiException(409, "max-actors (" + cfg.maxActors + ") reached");
        }
        if (Gate.reserved(name, cfg.reserved)) {
            throw new ApiException(403, name + " is a reserved name");
        }
        ConnectionManager cm = MinecraftServer.getConnectionManager();
        Player online = cm.getOnlinePlayerByUsername(name);
        if (online != null && !isActor(online)) {
            throw new ApiException(409, name + " is a real player who is online");
        }

        Instance instance = cfg.instance;
        if (req.has("world")) {
            String w = req.get("world").getAsString();
            instance = MinecraftServer.getInstanceManager().getInstances().stream().filter(i -> worldOf(i).equals(w)).findFirst()
                    .orElseThrow(() -> new ApiException(404, "no such world"));
        }
        Pos at = instance == cfg.instance ? cfg.spawn : new Pos(0, 64, 0);
        if (req.has("at") && req.get("at").isJsonArray() && req.getAsJsonArray("at").size() == 3) {
            JsonArray a = req.getAsJsonArray("at");
            at = new Pos(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
        }
        if (req.has("spreadX")) {
            // A crowd member: a random point around `at`, on the highest block there when its chunk is loaded.
            int x = (int) Math.floor(at.x() + req.get("spreadX").getAsDouble());
            int z = (int) Math.floor(at.z() + req.get("spreadZ").getAsDouble());
            at = new Pos(x + 0.5, at.y(), z + 0.5);
            if (instance.isChunkLoaded(x >> 4, z >> 4)) {
                int top = highestSolid(instance, x, z);
                if (top != Integer.MIN_VALUE) {
                    at = at.withY(top + 1);
                }
            }
        }
        at = at.withView(req.has("yaw") ? req.get("yaw").getAsFloat() : at.yaw(), req.has("pitch") ? req.get("pitch").getAsFloat() : at.pitch());

        UUID uuid = Gate.actorUuid(cfg.uuidNamespace, name);
        GameProfile profile = new GameProfile(uuid, name, skin(req));
        int view = req.has("view") ? Math.max(2, Math.min(32, req.get("view").getAsInt())) : cfg.viewDistance;
        String profileName = req.has("profile") ? req.get("profile").getAsString() : "mid";
        String seed = req.has("seed") && req.get("seed").isJsonPrimitive() ? req.get("seed").getAsString() : "1";
        ActorConnection connection = new ActorConnection();
        Pos spawnAt = at;
        Instance spawnIn = instance;
        Actor actor = new Actor(name, uuid, connection, profileName, new Rng(seed + ":" + name), log::add, p -> joined(p, spawnAt));
        synchronized (actors) {
            actors.put(name, actor);
        }
        actorIds.add(uuid);
        actorNames.add(name.toLowerCase(Locale.ROOT));

        // Minestom's own test players join the same way: configuration and the move to play on a virtual thread, without the
        // known-packs exchange (no client answers it). The server lets the player in on its next tick.
        Thread.startVirtualThread(() -> {
            try {
                Player p = cm.createPlayer(connection, profile);
                p.refreshSettings(new ClientSettings(Locale.US, (byte) view, ChatMessageType.FULL, true, (byte) 0x7f, MainHand.RIGHT, false, true,
                        ClientSettings.ParticleSetting.ALL));
                p.eventNode().addListener(AsyncPlayerConfigurationEvent.class, e -> {
                    e.setSpawningInstance(spawnIn);
                    e.getPlayer().setRespawnPoint(spawnAt);
                });
                actor.player = p;
                cm.doConfiguration(p, false);
                cm.transitionConfigToPlay(p);
                connection.setClientState(ConnectionState.PLAY);
                connection.setServerState(ConnectionState.PLAY);
                if (actor.retired()) {
                    p.kick(Component.text("actor removed"));
                }
            } catch (Throwable t) {
                LOG.warn("actor {} could not join: {}", name, t.toString());
                log.add("error", json("name", name, "error", "could not join: " + t.getMessage()));
                jobs.add(() -> forget(actor, "removed"));
            }
        });
        return actor;
    }

    /** An actor is in the world: the game mode, and its place if the game's own join listeners put it elsewhere. */
    private void joined(Player p, Pos at) {
        p.setGameMode(cfg.gameMode);
        if (p.getInstance() == cfg.instance && !p.getPosition().samePoint(at)) {
            p.teleport(at);
        }
        log.add("spawned", json("name", p.getUsername()));
    }

    private static int highestSolid(Instance instance, int x, int z) {
        int min = instance.getCachedDimensionType().minY();
        for (int y = instance.getCachedDimensionType().maxY() - 1; y >= min; y--) {
            if (instance.getBlock(x, y, z).blocksMotion()) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static final int MAX_SKIN_VALUE = 16 * 1024;

    private List<GameProfile.Property> skin(JsonObject req) {
        if (!req.has("skin")) {
            return List.of();
        }
        String value;
        String signature = null;
        if (req.get("skin").isJsonObject()) {
            JsonObject s = req.getAsJsonObject("skin");
            value = s.has("value") ? s.get("value").getAsString() : null;
            signature = s.has("signature") ? s.get("signature").getAsString() : null;
        } else {
            if (cfg.skins == null) {
                throw new ApiException(400, "this server has no skins folder: give the skin as {value, signature}");
            }
            JsonObject lib = SkinLibrary.load(cfg.skins, req.get("skin").getAsString());
            value = lib.get("value").getAsString();
            signature = lib.has("signature") ? lib.get("signature").getAsString() : null;
        }
        if (value == null || value.isBlank() || value.length() > MAX_SKIN_VALUE || (signature != null && signature.length() > MAX_SKIN_VALUE)) {
            throw new ApiException(400, "skin needs a textures value (up to 16 KB)");
        }
        return List.of(new GameProfile.Property("textures", value, signature));
    }

    private void despawnNow(Actor a) {
        a.retire();
        Player p = a.player;
        if (p != null && p.isOnline()) {
            p.kick(Component.text("actor removed"));
        }
        forget(a, "despawned");
    }

    private void forget(Actor a, String ev) {
        synchronized (actors) {
            if (actors.get(a.name) != a) {
                return;
            }
            actors.remove(a.name);
        }
        a.retire();
        actorNames.remove(a.name.toLowerCase(Locale.ROOT));
        // The UUID stays known until the player is gone, so its disconnect is still an actor's.
        Player p = a.player;
        if (p == null || p.isRemoved()) {
            actorIds.remove(a.uuid);
        } else {
            p.scheduleNextTick(e -> actorIds.remove(a.uuid));
        }
        log.add(ev, json("name", a.name));
    }

    /** A quit that did not come from despawn (a kick by the game): forget the actor so nothing keeps driving a removed player. */
    private void onQuit(String name) {
        Actor a = actors.get(name);
        if (a != null) {
            forget(a, "removed");
        }
    }

    private void despawnAll() {
        for (Actor a : new ArrayList<>(actors.values())) {
            try {
                despawnNow(a);
            } catch (RuntimeException e) {
                LOG.warn("could not remove {}: {}", a.name, e.toString());
            }
        }
    }

    // ---- scene sink

    private final class SceneSink implements SceneRunner.Sink {
        @Override
        public void spawn(JsonObject actor) {
            try {
                JsonObject req = actor.deepCopy();
                req.addProperty("seed", currentSeed);
                spawnNow(req);
            } catch (ApiException e) {
                emit("error", json("name", actor.get("name").getAsString(), "error", e.getMessage()));
            }
        }

        @Override
        public void act(String actor, JsonObject step) {
            Actor a = actors.get(actor);
            if (a == null) {
                return;
            }
            try {
                a.act(step);
            } catch (ApiException e) {
                emit("error", json("name", actor, "error", e.getMessage()));
            }
        }

        @Override
        public void command(String command) {
            if (!cfg.sceneCommands) {
                emit("error", json("error", "scene commands are disabled (sceneCommands)"));
                return;
            }
            if (!Gate.commandAllowed(command, cfg.commandRoots)) {
                emit("error", json("error", "command refused: " + command));
                return;
            }
            var commands = MinecraftServer.getCommandManager();
            commands.execute(commands.getConsoleSender(), command.startsWith("/") ? command.substring(1) : command);
        }

        @Override
        public void despawnAll() {
            MinestomActors.this.despawnAll();
        }

        @Override
        public void emit(String ev, JsonObject data) {
            log.add(ev, data);
        }
    }

    private static JsonObject json(String... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            o.addProperty(kv[i], kv[i + 1]);
        }
        return o;
    }
}
