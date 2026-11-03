package net.poena.actors.paper;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.poena.actors.core.ApiException;
import net.poena.actors.core.Control;
import net.poena.actors.core.EventLog;
import net.poena.actors.core.Gate;
import net.poena.actors.core.Rng;
import net.poena.actors.core.SceneRunner;
import net.poena.actors.core.SkinLibrary;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Owns the actors and the running scene. Everything that touches the game runs on the main thread; the API threads wait for it. */
final class ActorManager implements Control {
    record Settings(int maxActors, List<String> reserved, boolean refusePlayed, List<String> allowPlayed, boolean sceneCommands, List<String> commandRoots,
                    GameMode gamemode, String uuidNamespace, int viewDistance, int spawnsPerTick) {
    }

    /** The most actors one server can be asked for; the config's max-actors sets the real limit. */
    static final int HARD_MAX = 1000;

    private final JavaPlugin plugin;
    private final Settings cfg;
    private final String serverName;
    private final EventLog log = new EventLog(2000);
    private final Map<String, Actor> actors = new LinkedHashMap<>();
    /** Spawns asked for in one call, joined a few per tick so a crowd does not stall the server. */
    private final java.util.ArrayDeque<JsonObject> queue = new java.util.ArrayDeque<>();
    private ScheduledTask queueTask;
    private SceneRunner scene;
    private ScheduledTask sceneTask;
    private boolean tickRateChanged;
    private String currentSeed = "1";

    ActorManager(JavaPlugin plugin, Settings cfg, String serverName) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.serverName = serverName;
    }

    EventLog log() {
        return log;
    }

    // ---- thread hop

    private <T> T onMain(Supplier<T> work) {
        if (Bukkit.isPrimaryThread()) {
            return work.get();
        }
        CompletableFuture<T> done = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().run(plugin, t -> {
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
            plugin.getLogger().warning("request failed: " + e.getCause());
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
        return onMain(() -> {
            JsonObject o = new JsonObject();
            o.addProperty("plugin", plugin.getName() + " " + plugin.getPluginMeta().getVersion());
            o.addProperty("server", Bukkit.getName() + " " + Bukkit.getMinecraftVersion() + " (" + serverName + ")");
            o.addProperty("scene", scene != null && scene.running());
            JsonArray list = new JsonArray();
            for (Actor a : actors.values()) {
                Player p = a.bukkit();
                Location l = p.getLocation();
                JsonObject x = new JsonObject();
                x.addProperty("name", a.name);
                x.addProperty("uuid", a.uuid.toString());
                x.addProperty("world", l.getWorld().getName());
                x.addProperty("x", l.getX());
                x.addProperty("y", l.getY());
                x.addProperty("z", l.getZ());
                x.addProperty("yaw", l.getYaw());
                x.addProperty("pitch", l.getPitch());
                x.addProperty("health", p.getHealth());
                x.addProperty("dead", p.isDead());
                x.addProperty("hits", a.hits);
                x.addProperty("task", a.taskName());
                x.addProperty("profile", a.profileName);
                list.add(x);
            }
            o.add("actors", list);
            o.addProperty("max", cfg.maxActors());
            return o;
        });
    }

    @Override
    public JsonObject spawn(JsonObject req) {
        if (req.has("names") || req.has("count")) {
            return onMain(() -> enqueue(req));
        }
        return onMain(() -> {
            Actor a = spawnNow(req);
            JsonObject o = new JsonObject();
            o.addProperty("name", a.name);
            o.addProperty("uuid", a.uuid.toString());
            return o;
        });
    }

    /**
     * A crowd: {names: [...]} or {prefix: "Bot", count: N}, with the fields of one spawn for all of them, and spread: R to place
     * each at a random point within R blocks of `at` (or the world spawn), on the highest block.
     */
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
        if (actors.size() + queue.size() + names.size() > cfg.maxActors()) {
            throw new ApiException(409, "max-actors (" + cfg.maxActors() + ") would be passed: " + actors.size() + " spawned, " + queue.size()
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
        if (queueTask == null) {
            queueTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> drainQueue(), 1L, 1L);
        }
        JsonObject o = new JsonObject();
        o.addProperty("queued", names.size());
        o.addProperty("perTick", cfg.spawnsPerTick());
        return o;
    }

    private void drainQueue() {
        for (int i = 0; i < cfg.spawnsPerTick() && !queue.isEmpty(); i++) {
            JsonObject req = queue.poll();
            try {
                spawnNow(req);
            } catch (ApiException e) {
                log.add("error", json("name", req.get("name").getAsString(), "error", e.getMessage()));
            }
        }
        if (queue.isEmpty() && queueTask != null) {
            queueTask.cancel();
            queueTask = null;
            log.add("crowd", json("state", "spawned", "actors", String.valueOf(actors.size())));
        }
    }

    @Override
    public JsonObject actor(String name) {
        return onMain(() -> {
            Actor a = actors.get(name);
            if (a == null) {
                throw new ApiException(404, "no such actor");
            }
            return Snapshot.of(a);
        });
    }

    @Override
    public JsonObject metrics() {
        return onMain(() -> {
            JsonObject o = Metrics.sample();
            o.addProperty("actors", actors.size());
            o.addProperty("waiting", queue.size());
            o.addProperty("maxActors", cfg.maxActors());
            return o;
        });
    }

    @Override
    public JsonObject despawn(JsonObject req) {
        return onMain(() -> {
            if (req.has("all") && req.get("all").getAsBoolean()) {
                int n = actors.size();
                queue.clear();
                despawnAll();
                JsonObject o = new JsonObject();
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
                JsonObject o = new JsonObject();
                o.addProperty("removed", n);
                return o;
            }
            Actor a = actors.get(requireName(req));
            if (a == null) {
                throw new ApiException(404, "no such actor");
            }
            despawnNow(a);
            JsonObject o = new JsonObject();
            o.addProperty("removed", 1);
            return o;
        });
    }

    @Override
    public JsonObject act(JsonObject req) {
        return onMain(() -> {
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
        return onMain(() -> {
            if (scene != null && scene.running()) {
                throw new ApiException(409, "a scene is already running");
            }
            JsonObject sceneJson = (req.has("scene") && req.get("scene").isJsonObject() ? req.getAsJsonObject("scene") : req).deepCopy();
            currentSeed = sceneJson.has("seed") && sceneJson.get("seed").isJsonPrimitive() ? sceneJson.get("seed").getAsString() : "1";
            if (sceneJson.has("tickRate")) {
                int rate = sceneJson.get("tickRate").getAsInt();
                if (rate < 1 || rate > 20) {
                    throw new ApiException(400, "tickRate is 1-20");
                }
                JsonArray setup = sceneJson.has("setup") ? sceneJson.getAsJsonArray("setup") : new JsonArray();
                setup.add("tick rate " + rate);
                sceneJson.add("setup", setup);
            }
            scene = new SceneRunner(sceneJson, new SceneSink(), cfg.maxActors());
            log.add("scene", json("state", "start"));
            sceneTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> {
                scene.tick();
                if (!scene.running()) {
                    t.cancel();
                }
            }, 1L, 1L);
            return new JsonObject();
        });
    }

    @Override
    public JsonObject sceneStop() {
        return onMain(() -> {
            if (scene == null || !scene.running()) {
                throw new ApiException(409, "no scene is running");
            }
            scene.abort();
            if (sceneTask != null) {
                sceneTask.cancel();
            }
            return new JsonObject();
        });
    }

    @Override
    public JsonObject events(long since, int limit) {
        return log.since(since, limit);
    }

    // ---- spawn / despawn (main thread)

    private static String requireName(JsonObject req) {
        String name = req.has("name") && req.get("name").isJsonPrimitive() ? req.get("name").getAsString() : null;
        if (!Gate.validName(name)) {
            throw new ApiException(400, "name must be 3-16 letters, digits or _");
        }
        return name;
    }

    UUID uuidFor(String name) {
        return Gate.actorUuid(cfg.uuidNamespace(), name);
    }

    private Actor spawnNow(JsonObject req) {
        String name = requireName(req);
        if (actors.containsKey(name)) {
            throw new ApiException(409, name + " is already spawned");
        }
        if (actors.size() >= cfg.maxActors()) {
            throw new ApiException(409, "max-actors (" + cfg.maxActors() + ") reached");
        }
        refuseName(name);

        World world = req.has("world") ? Bukkit.getWorld(req.get("world").getAsString()) : Bukkit.getWorlds().get(0);
        if (world == null) {
            throw new ApiException(404, "no such world");
        }
        Location at = world.getSpawnLocation();
        if (req.has("at") && req.get("at").isJsonArray() && req.getAsJsonArray("at").size() == 3) {
            JsonArray a = req.getAsJsonArray("at");
            at = new Location(world, a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
        }
        if (req.has("spreadX")) {
            // A crowd member: a random point around `at`, standing on the highest block there (loaded chunks only).
            int x = (int) Math.floor(at.getX() + req.get("spreadX").getAsDouble());
            int z = (int) Math.floor(at.getZ() + req.get("spreadZ").getAsDouble());
            if (world.isChunkLoaded(x >> 4, z >> 4)) {
                at = new Location(world, x + 0.5, world.getHighestBlockYAt(x, z) + 1, z + 0.5);
            }
        }
        if (req.has("yaw")) {
            at.setYaw(req.get("yaw").getAsFloat());
        }
        if (req.has("pitch")) {
            at.setPitch(req.get("pitch").getAsFloat());
        }

        UUID uuid = uuidFor(name);
        GameProfile profile = new GameProfile(uuid, name, skin(req));
        MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
        ServerLevel level = ((CraftWorld) world).getHandle();
        ClientInformation d = ClientInformation.createDefault();
        // 0x7f shows every skin layer. A small view distance keeps the chunk work of an actor low: most of an actor's cost is the
        // world it keeps loaded around it, not the actor itself.
        int view = req.has("view") ? Math.max(2, Math.min(32, req.get("view").getAsInt())) : cfg.viewDistance();
        ClientInformation info = new ClientInformation(d.language(), view, d.chatVisibility(), d.chatColors(), 0x7f, d.mainHand(), d.textFilteringEnabled(),
                d.allowsListing(), d.particleStatus());

        ActorPlayer player = new ActorPlayer(server, level, profile, info);
        player.absSnapTo(at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch());
        FakeNet net = FakeNet.open();
        CommonListenerCookie base = CommonListenerCookie.createInitial(profile, false);
        CommonListenerCookie cookie = new CommonListenerCookie(profile, 0, info, false, base.clientBrand(), base.channels(), base.keepAlive());
        // Marks the name before the join events fire, so the listeners can tell an actor from a person.
        pending.add(name);
        try {
            server.getPlayerList().placeNewPlayer(net.connection, player, cookie);
        } finally {
            pending.remove(name);
        }
        ActorListener listener = new ActorListener(server, net.connection, player, cookie);

        String profileName = req.has("profile") ? req.get("profile").getAsString() : "mid";
        Rng rng = seeded(req, name);
        Actor actor = new Actor(name, uuid, player, listener, net, profileName, rng, (ev, data) -> log.add(ev, data));
        actors.put(name, actor);

        Player bukkit = player.getBukkitEntity();
        bukkit.setGameMode(cfg.gamemode());
        bukkit.teleportAsync(at);
        bukkit.getScheduler().runAtFixedRate(plugin, t -> {
            if (actor.retired()) {
                t.cancel();
                return;
            }
            actor.tick();
        }, null, 1L, 1L);
        log.add("spawned", json("name", name));
        return actor;
    }

    private Rng seeded(JsonObject req, String name) {
        // Same scene seed and same name give the same moves; two actors with one seed still differ.
        String base = req.has("seed") && req.get("seed").isJsonPrimitive() ? req.get("seed").getAsString() : "1";
        return new Rng(base + ":" + name);
    }

    private final Set<String> pending = java.util.concurrent.ConcurrentHashMap.newKeySet();

    boolean isActorName(String name) {
        return pending.contains(name) || actors.containsKey(name);
    }

    private void refuseName(String name) {
        if (Gate.reserved(name, cfg.reserved())) {
            throw new ApiException(403, name + " is a reserved name");
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            throw new ApiException(409, name + " is a real player who is online");
        }
        if (cfg.refusePlayed() && cfg.allowPlayed().stream().noneMatch(n -> n.equalsIgnoreCase(name))) {
            var known = Bukkit.getOfflinePlayerIfCached(name);
            if (known != null && known.hasPlayedBefore() && !uuidFor(name).equals(known.getUniqueId())) {
                throw new ApiException(403, name + " belongs to a real account that has played here (add it to allow-played to use it anyway)");
            }
        }
    }

    private static final int MAX_SKIN_VALUE = 16 * 1024;

    private PropertyMap skin(JsonObject req) {
        if (!req.has("skin")) {
            return PropertyMap.EMPTY;
        }
        String value;
        String signature = null;
        if (req.get("skin").isJsonObject()) {
            JsonObject s = req.getAsJsonObject("skin");
            value = s.has("value") ? s.get("value").getAsString() : null;
            signature = s.has("signature") ? s.get("signature").getAsString() : null;
        } else {
            JsonObject lib = SkinLibrary.load(plugin.getDataFolder().toPath().resolve("skins"), req.get("skin").getAsString());
            value = lib.get("value").getAsString();
            signature = lib.has("signature") ? lib.get("signature").getAsString() : null;
        }
        if (value == null || value.isBlank() || value.length() > MAX_SKIN_VALUE || (signature != null && signature.length() > MAX_SKIN_VALUE)) {
            throw new ApiException(400, "skin needs a textures value (up to 16 KB)");
        }
        Multimap<String, Property> props = LinkedHashMultimap.create();
        props.put("textures", signature == null ? new Property("textures", value) : new Property("textures", value, signature));
        return new PropertyMap(props);
    }

    private void despawnNow(Actor a) {
        actors.remove(a.name);
        a.retire();
        try {
            a.listener.onDisconnect(new DisconnectionDetails(Component.literal("actor removed")));
        } finally {
            a.net.close();
            purgeData(a.uuid);
        }
        log.add("despawned", json("name", a.name));
    }

    /** A quit that did not come from despawn (a kick by another plugin): forget the actor so nothing keeps ticking a removed player. */
    void onQuit(String name) {
        Actor a = actors.remove(name);
        if (a != null) {
            a.retire();
            a.net.close();
            purgeData(a.uuid);
            log.add("removed", json("name", name));
        }
    }

    int countHit(String name) {
        Actor a = actors.get(name);
        return a == null ? 0 : ++a.hits;
    }

    void despawnAll() {
        for (Actor a : new ArrayList<>(actors.values())) {
            try {
                despawnNow(a);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("could not remove " + a.name + ": " + e);
            }
        }
    }

    /** The server saves player data on quit. Actors are throwaway, so their files (named by their own UUID only) go. */
    private void purgeData(UUID uuid) {
        Set<String> names = Set.of(uuid + ".dat", uuid + ".dat_old", uuid + ".json");
        Set<String> skip = Set.of("region", "entities", "poi", "DIM-1", "DIM1", "generated");
        try {
            Files.walkFileTree(Bukkit.getWorldContainer().toPath(), Set.of(), 6, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return skip.contains(dir.getFileName() == null ? "" : dir.getFileName().toString()) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (names.contains(file.getFileName().toString())) {
                        Files.deleteIfExists(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            plugin.getLogger().warning("could not clean player data of " + uuid + ": " + e.getMessage());
        }
    }

    void shutdown() {
        queue.clear();
        if (queueTask != null) {
            queueTask.cancel();
        }
        if (sceneTask != null) {
            sceneTask.cancel();
        }
        if (scene != null && scene.running()) {
            scene.abort();
        }
        despawnAll();
        restoreTickRate();
    }

    // ---- scene sink

    private void restoreTickRate() {
        if (tickRateChanged) {
            tickRateChanged = false;
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tick rate 20");
        }
    }

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
            if (!cfg.sceneCommands()) {
                emit("error", json("error", "scene commands are disabled (scene-commands.enabled)"));
                return;
            }
            if (!Gate.commandAllowed(command, cfg.commandRoots())) {
                emit("error", json("error", "command refused: " + command));
                return;
            }
            String c = command.startsWith("/") ? command.substring(1) : command;
            if (c.toLowerCase(Locale.ROOT).startsWith("tick rate")) {
                tickRateChanged = true;
            }
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c);
        }

        @Override
        public void despawnAll() {
            ActorManager.this.despawnAll();
            restoreTickRate();
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
