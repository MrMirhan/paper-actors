package net.poena.actors.minestom;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerChatEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.LightingChunk;
import net.minestom.server.instance.block.Block;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.ConnectionState;

/**
 * A flat Minestom test server with the actors, to try them by hand or from an MCP client. A wall of stone stands at x = 5 from
 * z = -3 to 3, a chest at (2, 40, 2) opens a small menu, and the server counts what the actors do in its log. The API is on
 * 127.0.0.1:4331 with PAPER_ACTORS_SECRET; the server is named "minestom-test" (ACTORS_SERVER_NAME overrides it).
 */
public final class TestServer {
    private TestServer() {
    }

    private static int intEnv(String name, int fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim());
    }

    public static void main(String[] args) {
        MinecraftServer server = MinecraftServer.init();
        InstanceContainer world = MinecraftServer.getInstanceManager().createInstanceContainer();
        world.setChunkSupplier(LightingChunk::new);
        world.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.STONE));
        for (int z = -3; z <= 3; z++) {
            world.setBlock(5, 40, z, Block.STONE);
            world.setBlock(5, 41, z, Block.STONE);
        }
        world.setBlock(2, 40, 2, Block.CHEST);

        var events = MinecraftServer.getGlobalEventHandler();
        events.addListener(AsyncPlayerConfigurationEvent.class, e -> {
            e.setSpawningInstance(world);
            e.getPlayer().setRespawnPoint(new Pos(0, 40, 0));
        });
        // What the game sees the actors do, as a game's own listeners would. ACTORS_QUIET=1 keeps a load test's console quiet.
        boolean quiet = "1".equals(System.getenv("ACTORS_QUIET"));
        java.util.function.Consumer<String> say = line -> {
            if (!quiet) {
                System.out.println(line);
            }
        };
        int[] moves = new int[1];
        // What the actors did, counted for the console line every 10 s: breaks, places, chats, commands, attacks.
        java.util.concurrent.atomic.AtomicLongArray counts = new java.util.concurrent.atomic.AtomicLongArray(5);
        events.addListener(PlayerMoveEvent.class, e -> moves[0]++);
        events.addListener(PlayerBlockBreakEvent.class, e -> counts.incrementAndGet(0));
        events.addListener(net.minestom.server.event.player.PlayerBlockPlaceEvent.class, e -> counts.incrementAndGet(1));
        events.addListener(PlayerChatEvent.class, e -> counts.incrementAndGet(2));
        events.addListener(net.minestom.server.event.player.PlayerCommandEvent.class, e -> counts.incrementAndGet(3));
        events.addListener(net.minestom.server.event.entity.EntityAttackEvent.class, e -> counts.incrementAndGet(4));
        events.addListener(PlayerBlockBreakEvent.class, e -> say.accept("[game] " + e.getPlayer().getUsername() + " broke " + e.getBlock().name()));
        events.addListener(PlayerChatEvent.class, e -> say.accept("[game] chat " + e.getPlayer().getUsername() + ": " + e.getRawMessage()));
        events.addListener(PlayerBlockInteractEvent.class, e -> {
            if (e.getBlock().compare(Block.ANVIL)) {
                e.setBlockingItemUse(true);
                e.getPlayer().openInventory(new Inventory(InventoryType.ANVIL, Component.text("Repair")));
            }
            if (e.getBlock().compare(Block.CHEST)) {
                e.setBlockingItemUse(true);
                Inventory menu = new Inventory(InventoryType.CHEST_1_ROW, Component.text("Shop"));
                menu.setItemStack(4, ItemStack.of(Material.DIAMOND).with(net.minestom.server.component.DataComponents.CUSTOM_NAME, Component.text("Buy")));
                e.getPlayer().openInventory(menu);
                say.accept("[game] " + e.getPlayer().getUsername() + " opened the shop");
            }
        });
        events.addListener(InventoryPreClickEvent.class, e -> say.accept("[game] " + e.getPlayer().getUsername() + " clicked " + e.getClick()));
        events.addListener(net.minestom.server.event.player.PlayerBlockPlaceEvent.class,
                e -> say.accept("[game] " + e.getPlayer().getUsername() + " placed " + e.getBlock().name() + " at " + e.getBlockPosition()));
        events.addListener(net.minestom.server.event.player.PlayerChangeHeldSlotEvent.class,
                e -> say.accept("[game] " + e.getPlayer().getUsername() + " held slot " + e.getNewSlot()));
        events.addListener(net.minestom.server.event.player.PlayerSwapItemEvent.class, e -> say.accept("[game] " + e.getPlayer().getUsername() + " swapped hands"));
        events.addListener(net.minestom.server.event.item.ItemDropEvent.class, e -> say.accept("[game] " + e.getPlayer().getUsername() + " dropped " + e.getItemStack().material().name()));
        events.addListener(net.minestom.server.event.player.PlayerUseItemEvent.class, e -> say.accept("[game] " + e.getPlayer().getUsername() + " used " + e.getItemStack().material().name()));
        events.addListener(net.minestom.server.event.player.PlayerEditSignEvent.class, e -> say.accept("[game] " + e.getPlayer().getUsername() + " wrote a sign: " + e.getLines()));
        events.addListener(net.minestom.server.event.player.PlayerAnvilInputEvent.class, e -> say.accept("[game] " + e.getPlayer().getUsername() + " named " + e.getInput()));
        events.addListener(net.minestom.server.event.inventory.InventoryButtonClickEvent.class, e -> say.accept("[game] " + e.getPlayer().getUsername() + " pressed button " + e.getButtonId()));
        events.addListener(net.minestom.server.event.player.PlayerInputEvent.class, e -> {
            if (e.getPlayer().isSneaking()) {
                say.accept("[game] " + e.getPlayer().getUsername() + " sneaks");
            }
        });
        // A villager: a right click opens its trades; the game handles the trade packet itself (Minestom has no trading).
        var villager = new net.minestom.server.entity.EntityCreature(net.minestom.server.entity.EntityType.VILLAGER);
        villager.setInstance(world, new Pos(-4, 40, 0));
        events.addListener(net.minestom.server.event.player.PlayerEntityInteractEvent.class, e -> {
            say.accept("[game] " + e.getPlayer().getUsername() + " interacted with " + e.getTarget().getEntityType().name());
            if (e.getTarget() == villager) {
                e.getPlayer().openInventory(new Inventory(InventoryType.MERCHANT, Component.text("Trades")));
            }
        });
        MinecraftServer.getPacketListenerManager().setPlayListener(net.minestom.server.network.packet.client.play.ClientSelectTradePacket.class,
                (packet, player) -> say.accept("[game] " + player.getUsername() + " picked trade " + packet.selectedSlot()));
        // An anvil opens the anvil screen; a sign is there to write on.
        world.setBlock(-2, 40, 2, Block.ANVIL);
        world.setBlock(-2, 40, -2, Block.OAK_SIGN);
        Command where = new Command("where");
        where.setDefaultExecutor((sender, context) -> {
            if (sender instanceof Player p) {
                say.accept("[game] /where by " + p.getUsername() + " at " + p.getPosition());
            }
        });
        MinecraftServer.getCommandManager().register(where);
        MinecraftServer.getSchedulerManager().buildTask(() -> System.out.println("[game] moves so far: " + moves[0] + ", breaks " + counts.get(0)
                        + ", places " + counts.get(1) + ", chats " + counts.get(2) + ", commands " + counts.get(3) + ", attacks " + counts.get(4)))
                .repeat(net.minestom.server.timer.TaskSchedule.seconds(10)).schedule();

        String name = System.getenv("ACTORS_SERVER_NAME");
        MinestomActors.start(ActorSettings.of(world, new Pos(0, 40, 0))
                .serverName(name == null || name.isBlank() ? "minestom-test" : name)
                .allowedServers(List.of("minestom-test"))
                .limits(intEnv("ACTORS_MAX", 200), intEnv("ACTORS_PER_TICK", 4))
                .viewDistance(4));
        server.start("0.0.0.0", 25565);
        System.out.println("[game] ready " + ConnectionState.PLAY);
    }
}
