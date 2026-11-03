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
        // What the game sees the actors do, as a game's own listeners would.
        int[] moves = new int[1];
        events.addListener(PlayerMoveEvent.class, e -> moves[0]++);
        events.addListener(PlayerBlockBreakEvent.class, e -> System.out.println("[game] " + e.getPlayer().getUsername() + " broke " + e.getBlock().name()));
        events.addListener(PlayerChatEvent.class, e -> System.out.println("[game] chat " + e.getPlayer().getUsername() + ": " + e.getRawMessage()));
        events.addListener(PlayerBlockInteractEvent.class, e -> {
            if (e.getBlock().compare(Block.CHEST)) {
                Inventory menu = new Inventory(InventoryType.CHEST_1_ROW, Component.text("Shop"));
                menu.setItemStack(4, ItemStack.of(Material.DIAMOND).with(net.minestom.server.component.DataComponents.CUSTOM_NAME, Component.text("Buy")));
                e.getPlayer().openInventory(menu);
                System.out.println("[game] " + e.getPlayer().getUsername() + " opened the shop");
            }
        });
        events.addListener(InventoryPreClickEvent.class, e -> System.out.println("[game] " + e.getPlayer().getUsername() + " clicked " + e.getClick()));
        Command where = new Command("where");
        where.setDefaultExecutor((sender, context) -> {
            if (sender instanceof Player p) {
                System.out.println("[game] /where by " + p.getUsername() + " at " + p.getPosition());
            }
        });
        MinecraftServer.getCommandManager().register(where);
        MinecraftServer.getSchedulerManager().buildTask(() -> System.out.println("[game] moves so far: " + moves[0]))
                .repeat(net.minestom.server.timer.TaskSchedule.seconds(10)).schedule();

        String name = System.getenv("ACTORS_SERVER_NAME");
        MinestomActors.start(ActorSettings.of(world, new Pos(0, 40, 0))
                .serverName(name == null || name.isBlank() ? "minestom-test" : name)
                .allowedServers(List.of("minestom-test"))
                .limits(200, 4)
                .viewDistance(4));
        server.start("0.0.0.0", 25565);
        System.out.println("[game] ready " + ConnectionState.PLAY);
    }
}
