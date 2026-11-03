package net.poena.actors.minestom;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.instance.Instance;

/**
 * How the actors run on a Minestom server: the same settings as the Paper plugin's config.yml, given in code.
 *
 * <p>The safety gate is the same: the actors start only when this server's name is in {@code allowedServers}. Minestom has no
 * server.properties, so the name comes from {@code serverName}, or from the {@code ACTORS_SERVER_NAME} environment variable when
 * that is empty. Keep the allowed list in the test server's code and give the name in the test machine's environment: the same
 * build started anywhere else then refuses to run actors.
 */
public final class ActorSettings {
    final Instance instance;
    final Pos spawn;
    String serverName = "";
    List<String> allowedServers = List.of();
    String secret = "";
    String host = "127.0.0.1";
    int port = 4331;
    int serverPort = 25565;
    int maxActors = 8;
    int spawnsPerTick = 2;
    int viewDistance = 4;
    GameMode gameMode = GameMode.SURVIVAL;
    String uuidNamespace = "PaperActor";
    List<String> reserved = List.of();
    boolean sceneCommands;
    List<String> commandRoots = List.of("time", "weather", "gamemode", "give", "effect", "clear", "difficulty", "setblock", "fill", "summon",
            "tp", "teleport");
    Path skins;

    private ActorSettings(Instance instance, Pos spawn) {
        this.instance = Objects.requireNonNull(instance, "instance");
        this.spawn = Objects.requireNonNull(spawn, "spawn");
    }

    /** Actors join `instance` at `spawn` unless a spawn call says where. */
    public static ActorSettings of(Instance instance, Pos spawn) {
        return new ActorSettings(instance, spawn);
    }

    /** This server's name, which allowedServers must list; empty takes ACTORS_SERVER_NAME from the environment. */
    public ActorSettings serverName(String name) {
        this.serverName = name == null ? "" : name;
        return this;
    }

    /** The server names (or name:port) the actors may run on. Empty refuses every server. */
    public ActorSettings allowedServers(List<String> names) {
        this.allowedServers = List.copyOf(names);
        return this;
    }

    /** Signs every API request: 16 characters or more (32 when the API is not on loopback). Empty takes PAPER_ACTORS_SECRET. */
    public ActorSettings secret(String secret) {
        this.secret = secret == null ? "" : secret;
        return this;
    }

    /** Where the API listens: 127.0.0.1:4331 by default. Another host than loopback needs a 32-character secret. */
    public ActorSettings api(String host, int port) {
        this.host = host;
        this.port = port;
        return this;
    }

    /** The game port, for allowedServers entries written as name:port. */
    public ActorSettings serverPort(int port) {
        this.serverPort = port;
        return this;
    }

    /** How many actors at once (1-1000), and how many of a crowd join each tick (1-20). */
    public ActorSettings limits(int maxActors, int spawnsPerTick) {
        this.maxActors = Math.max(1, Math.min(MinestomActors.HARD_MAX, maxActors));
        this.spawnsPerTick = Math.max(1, Math.min(20, spawnsPerTick));
        return this;
    }

    /** The view distance each actor asks for (2-32): most of an actor's cost is the world kept loaded around it. */
    public ActorSettings viewDistance(int chunks) {
        this.viewDistance = Math.max(2, Math.min(32, chunks));
        return this;
    }

    public ActorSettings gameMode(GameMode mode) {
        this.gameMode = Objects.requireNonNull(mode);
        return this;
    }

    /** Actor UUIDs come from this and the name, so an actor never gets the data of the real account with that name. */
    public ActorSettings uuidNamespace(String namespace) {
        this.uuidNamespace = Objects.requireNonNull(namespace);
        return this;
    }

    /** Names an actor can never take; a trailing * matches a prefix. */
    public ActorSettings reserved(List<String> names) {
        this.reserved = List.copyOf(names);
        return this;
    }

    /** A scene's setup and cleanup console commands: off by default; only commands whose first word is in `roots` run. */
    public ActorSettings sceneCommands(boolean enabled, List<String> roots) {
        this.sceneCommands = enabled;
        this.commandRoots = List.copyOf(roots);
        return this;
    }

    /** A folder of named skins (name.json with value and signature), as the Paper plugin's skins folder. */
    public ActorSettings skins(Path folder) {
        this.skins = folder;
        return this;
    }

    String effectiveServerName() {
        if (!serverName.isBlank()) {
            return serverName;
        }
        String env = System.getenv("ACTORS_SERVER_NAME");
        return env == null ? "" : env.trim();
    }

    String effectiveSecret() {
        if (!secret.isBlank()) {
            return secret;
        }
        String env = System.getenv("PAPER_ACTORS_SECRET");
        return env == null ? "" : env;
    }
}
