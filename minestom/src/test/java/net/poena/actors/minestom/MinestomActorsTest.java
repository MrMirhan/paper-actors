package net.poena.actors.minestom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerChatEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import net.poena.actors.core.ApiException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The actors on a real Minestom server in this JVM: they join through the server's own steps, stay past the keep-alive kick
 * (shortened here to 2 s), walk with the server's move events, and act through its listeners.
 */
class MinestomActorsTest {
    private static final String SECRET = "test-secret-of-forty-characters-123456789";
    private static InstanceContainer world;
    private static MinestomActors actors;
    private static final AtomicInteger moves = new AtomicInteger();
    private static final Set<String> chat = ConcurrentHashMap.newKeySet();
    private static final Set<String> broken = ConcurrentHashMap.newKeySet();

    @BeforeAll
    static void server() {
        System.setProperty("minestom.keep-alive-delay", "500");
        System.setProperty("minestom.keep-alive-kick", "2000");
        MinecraftServer server = MinecraftServer.init();
        world = MinecraftServer.getInstanceManager().createInstanceContainer();
        world.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.STONE));
        var events = MinecraftServer.getGlobalEventHandler();
        events.addListener(PlayerMoveEvent.class, e -> moves.incrementAndGet());
        events.addListener(PlayerChatEvent.class, e -> chat.add(e.getPlayer().getUsername() + ": " + e.getRawMessage()));
        events.addListener(PlayerBlockBreakEvent.class, e -> broken.add(e.getPlayer().getUsername() + " " + e.getBlockPosition()));
        actors = MinestomActors.start(ActorSettings.of(world, new Pos(0, 40, 0)).serverName("unit-test").allowedServers(List.of("unit-test"))
                .secret(SECRET).api("127.0.0.1", 0).limits(20, 5));
        server.start("127.0.0.1", 0);
    }

    @AfterAll
    static void stop() {
        actors.stop();
        MinecraftServer.stopCleanly();
    }

    private static void waitFor(String what, BooleanSupplier ok) throws InterruptedException {
        long end = System.currentTimeMillis() + 15_000;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(100);
        }
    }

    private static JsonObject json(String text) {
        return com.google.gson.JsonParser.parseString(text).getAsJsonObject();
    }

    private static JsonObject actorOf(String name) {
        for (JsonElement e : actors.state().getAsJsonArray("actors")) {
            if (e.getAsJsonObject().get("name").getAsString().equals(name)) {
                return e.getAsJsonObject();
            }
        }
        return null;
    }

    private static boolean joined(String name) {
        JsonObject a = actorOf(name);
        return a != null && !a.get("task").getAsString().equals("joining");
    }

    @Test
    void refusesAServerItDoesNotList() {
        var cfg = ActorSettings.of(world, new Pos(0, 40, 0)).secret(SECRET).api("127.0.0.1", 0);
        IllegalStateException empty = assertThrows(IllegalStateException.class, () -> MinestomActors.start(cfg.serverName("live-1")));
        assertTrue(empty.getMessage().contains("allowed-servers is empty"), empty.getMessage());
        IllegalStateException other = assertThrows(IllegalStateException.class,
                () -> MinestomActors.start(cfg.serverName("live-1").allowedServers(List.of("unit-test"))));
        assertTrue(other.getMessage().contains("not in allowed-servers"), other.getMessage());
        IllegalStateException weak = assertThrows(IllegalStateException.class,
                () -> MinestomActors.start(cfg.serverName("unit-test").allowedServers(List.of("unit-test")).secret("short")));
        assertTrue(weak.getMessage().contains("at least 16"), weak.getMessage());
        IllegalStateException remote = assertThrows(IllegalStateException.class,
                () -> MinestomActors.start(cfg.serverName("unit-test").allowedServers(List.of("unit-test")).secret("sixteen-chars-ok!").api("0.0.0.0", 0)));
        assertTrue(remote.getMessage().contains("32"), remote.getMessage());
    }

    @Test
    void aCrowdJoinsStaysPastTheKeepAliveKickAndLeaves() throws InterruptedException {
        JsonObject asked = actors.spawn(json("{\"count\": 6, \"prefix\": \"Unit\", \"spread\": 6, \"at\": [0, 40, 0], \"view\": 2}"));
        assertEquals(6, asked.get("queued").getAsInt());
        waitFor("six actors in the world", () -> MinecraftServer.getConnectionManager().getOnlinePlayerCount() == 6
                && List.of("Unit1", "Unit2", "Unit3", "Unit4", "Unit5", "Unit6").stream().allMatch(MinestomActorsTest::joined));
        assertTrue(actors.isActor(MinecraftServer.getConnectionManager().getOnlinePlayerByUsername("Unit1")));
        // The server sends a keep-alive every 0.5 s and kicks after 2 s without an answer: the actors answer.
        Thread.sleep(4_000);
        assertEquals(6, MinecraftServer.getConnectionManager().getOnlinePlayerCount(), "nobody was kicked");
        JsonObject m = actors.metrics();
        assertEquals(6, m.get("actors").getAsInt());
        assertTrue(m.get("chunks").getAsInt() > 0, "the world around them loaded: " + m);
        assertTrue(m.getAsJsonObject("mspt").get("samples").getAsInt() > 0);
        JsonArray tps = m.getAsJsonArray("tps");
        assertEquals(3, tps.size());

        assertEquals(6, actors.despawn(json("{\"prefix\": \"Unit\"}")).get("removed").getAsInt());
        waitFor("everyone gone", () -> MinecraftServer.getConnectionManager().getOnlinePlayerCount() == 0);
        assertFalse(actors.state().getAsJsonArray("actors").iterator().hasNext());
    }

    @Test
    void anActorWalksChatsAndBreaksABlockThroughTheServersListeners() throws InterruptedException {
        actors.spawn(json("{\"name\": \"Walker\", \"at\": [10.5, 40, 10.5]}"));
        waitFor("Walker in the world", () -> joined("Walker"));
        int before = moves.get();
        double x0 = actorOf("Walker").get("x").getAsDouble();
        double z0 = actorOf("Walker").get("z").getAsDouble();
        actors.act(json("{\"name\": \"Walker\", \"do\": \"walk\", \"forward\": 1, \"seconds\": 1}"));
        waitFor("Walker walked", () -> {
            JsonObject a = actorOf("Walker");
            return Math.hypot(a.get("x").getAsDouble() - x0, a.get("z").getAsDouble() - z0) > 2;
        });
        assertTrue(moves.get() > before, "the server saw PlayerMoveEvents");
        assertEquals(40.0, actorOf("Walker").get("y").getAsDouble(), 0.01, "it stays on the ground");

        actors.act(json("{\"name\": \"Walker\", \"do\": \"chat\", \"text\": \"hi\"}"));
        waitFor("the chat event", () -> chat.contains("Walker: hi"));

        actors.act(json("{\"name\": \"Walker\", \"do\": \"teleport\", \"x\": 20.5, \"y\": 40, \"z\": 20.5}"));
        waitFor("the teleport", () -> actorOf("Walker").get("x").getAsDouble() == 20.5);
        actors.act(json("{\"name\": \"Walker\", \"do\": \"break\", \"x\": 21, \"y\": 39, \"z\": 20}"));
        waitFor("the break event", () -> !broken.isEmpty());
        waitFor("the block gone", () -> world.getBlock(21, 39, 20).air());

        ApiException unknown = assertThrows(ApiException.class, () -> actors.act(json("{\"name\": \"Walker\", \"do\": \"fly\"}")));
        assertEquals(400, unknown.status());
        ApiException none = assertThrows(ApiException.class, () -> actors.act(json("{\"name\": \"Nobody\", \"do\": \"jump\"}")));
        assertEquals(404, none.status());
        actors.despawn(json("{\"name\": \"Walker\"}"));
        waitFor("Walker gone", () -> MinecraftServer.getConnectionManager().getOnlinePlayerCount() == 0);
    }
}
