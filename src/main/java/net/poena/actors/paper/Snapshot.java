package net.poena.actors.paper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** One actor as a test reads it: where it is, its inventory, and the screen it has open with the item on its cursor. */
final class Snapshot {
    private Snapshot() {
    }

    static String plain(Component c) {
        return c == null ? "" : PlainTextComponentSerializer.plainText().serialize(c);
    }

    static JsonObject item(ItemStack it) {
        if (it == null || it.getType().isAir()) {
            return null;
        }
        JsonObject o = new JsonObject();
        o.addProperty("item", it.getType().getKey().toString());
        o.addProperty("count", it.getAmount());
        ItemMeta meta = it.hasItemMeta() ? it.getItemMeta() : null;
        if (meta != null && meta.hasCustomName()) {
            o.addProperty("name", plain(meta.customName()));
        }
        if (meta != null && meta.hasLore() && meta.lore() != null) {
            JsonArray lore = new JsonArray();
            meta.lore().forEach(line -> lore.add(plain(line)));
            o.add("lore", lore);
        }
        return o;
    }

    private static JsonArray slots(int from, int to, java.util.function.IntFunction<ItemStack> get) {
        JsonArray out = new JsonArray();
        for (int i = from; i < to; i++) {
            ItemStack stack;
            try {
                stack = get.apply(i);
            } catch (IndexOutOfBoundsException e) {
                // The player's own view counts a slot it does not hand out; stop at the last one it does.
                break;
            }
            JsonObject it = item(stack);
            if (it != null) {
                it.addProperty("slot", i);
                out.add(it);
            }
        }
        return out;
    }

    static JsonObject of(Actor a) {
        Player p = a.bukkit();
        Location l = p.getLocation();
        JsonObject o = new JsonObject();
        o.addProperty("name", a.name);
        o.addProperty("world", l.getWorld().getName());
        o.addProperty("x", l.getX());
        o.addProperty("y", l.getY());
        o.addProperty("z", l.getZ());
        o.addProperty("health", p.getHealth());
        o.addProperty("food", p.getFoodLevel());
        o.addProperty("gamemode", p.getGameMode().name().toLowerCase(java.util.Locale.ROOT));
        o.addProperty("level", p.getLevel());
        o.addProperty("sneaking", p.isSneaking());
        o.addProperty("task", a.taskName());
        o.addProperty("heldSlot", p.getInventory().getHeldItemSlot());
        o.add("inventory", slots(0, p.getInventory().getSize(), i -> p.getInventory().getItem(i)));

        InventoryView view = p.getOpenInventory();
        JsonObject screen = new JsonObject();
        screen.addProperty("type", view.getType().name().toLowerCase(java.util.Locale.ROOT));
        screen.addProperty("title", plain(view.title()));
        int top = view.getTopInventory().getSize();
        screen.addProperty("containerSlots", top);
        // The slot numbers a click takes: the open container first, then the actor's own inventory (view numbering).
        screen.add("slots", slots(0, view.countSlots(), view::getItem));
        JsonObject cursor = item(view.getCursor());
        if (cursor != null) {
            screen.add("cursor", cursor);
        }
        o.add("screen", screen);
        return o;
    }
}
