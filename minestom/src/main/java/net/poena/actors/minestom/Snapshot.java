package net.poena.actors.minestom;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.inventory.AbstractInventory;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.item.ItemStack;
import net.minestom.server.utils.inventory.PlayerInventoryUtils;

/**
 * One actor as a test reads it, in the Paper plugin's fields: where it is, its inventory (numbered as Bukkit numbers it: 0-8 the
 * hotbar, 9-35 the rest, 36-39 armour, 40 the off hand), and the screen it has open with the item on its cursor (numbered as a
 * click takes them: the container first, then the actor's own inventory).
 */
final class Snapshot {
    private Snapshot() {
    }

    static String plain(Component c) {
        return c == null ? "" : PlainTextComponentSerializer.plainText().serialize(c);
    }

    static JsonObject item(ItemStack it) {
        if (it == null || it.isAir()) {
            return null;
        }
        JsonObject o = new JsonObject();
        o.addProperty("item", it.material().name());
        o.addProperty("count", it.amount());
        Component name = it.get(DataComponents.CUSTOM_NAME);
        if (name != null) {
            o.addProperty("name", plain(name));
        }
        List<Component> lore = it.get(DataComponents.LORE);
        if (lore != null && !lore.isEmpty()) {
            JsonArray l = new JsonArray();
            lore.forEach(line -> l.add(plain(line)));
            o.add("lore", l);
        }
        return o;
    }

    private static JsonArray slots(int from, int to, IntFunction<ItemStack> get) {
        JsonArray out = new JsonArray();
        for (int i = from; i < to; i++) {
            JsonObject it = item(get.apply(i));
            if (it != null) {
                it.addProperty("slot", i);
                out.add(it);
            }
        }
        return out;
    }

    static JsonObject of(Actor a) {
        Player p = a.player;
        Pos l = p.getPosition();
        JsonObject o = new JsonObject();
        o.addProperty("name", a.name);
        o.addProperty("world", MinestomActors.worldName(p.getInstance()));
        o.addProperty("x", l.x());
        o.addProperty("y", l.y());
        o.addProperty("z", l.z());
        o.addProperty("health", p.getHealth());
        o.addProperty("food", p.getFood());
        o.addProperty("gamemode", p.getGameMode().name().toLowerCase(Locale.ROOT));
        o.addProperty("level", p.getLevel());
        o.addProperty("sneaking", p.isSneaking());
        o.addProperty("task", a.taskName());
        o.addProperty("heldSlot", p.getHeldSlot());
        var inv = p.getInventory();
        o.add("inventory", slots(0, 41, i -> inv.getItemStack(PlayerInventoryUtils.convertPlayerInventorySlotToMinestomSlot(i))));

        AbstractInventory open = p.getOpenInventory();
        JsonObject screen = new JsonObject();
        if (open == null) {
            screen.addProperty("type", "crafting");
            screen.addProperty("title", "Crafting");
            screen.addProperty("containerSlots", 5);
            screen.add("slots", slots(0, 46, i -> inv.getItemStack(PlayerInventoryUtils.convertWindow0SlotToMinestomSlot(i))));
        } else {
            int size = open.getSize();
            screen.addProperty("type", open instanceof Inventory i ? i.getInventoryType().name().toLowerCase(Locale.ROOT) : "container");
            screen.addProperty("title", open instanceof Inventory i ? plain(i.getTitle()) : "");
            screen.addProperty("containerSlots", size);
            screen.add("slots", slots(0, size + 36, i -> i < size ? open.getItemStack(i)
                    : inv.getItemStack(PlayerInventoryUtils.convertWindowSlotToMinestomSlot(i, size))));
        }
        JsonObject cursor = item(inv.getCursorItem());
        if (cursor != null) {
            screen.add("cursor", cursor);
        }
        o.add("screen", screen);
        return o;
    }
}
