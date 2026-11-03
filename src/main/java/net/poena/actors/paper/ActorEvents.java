package net.poena.actors.paper;

import com.google.gson.JsonObject;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import net.kyori.adventure.text.Component;

final class ActorEvents implements Listener {
    private final ActorManager manager;
    private final boolean quiet;

    ActorEvents(ActorManager manager, boolean quiet) {
        this.manager = manager;
        this.quiet = quiet;
    }

    /** A real login may not take the name of a running actor. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (manager.isActorName(e.getName())) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text("That name is used by an actor."));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent e) {
        if (quiet && manager.isActorName(e.getPlayer().getName())) {
            e.joinMessage(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent e) {
        String name = e.getPlayer().getName();
        if (quiet && manager.isActorName(name)) {
            e.quitMessage(null);
        }
        manager.onQuit(name);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onHit(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && manager.isActorName(p.getName())) {
            JsonObject o = new JsonObject();
            o.addProperty("name", p.getName());
            o.addProperty("target", e.getEntity().getName());
            o.addProperty("damage", e.getFinalDamage());
            o.addProperty("hits", manager.countHit(p.getName()));
            manager.log().add("hit", o);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        if (manager.isActorName(e.getPlayer().getName())) {
            JsonObject o = new JsonObject();
            o.addProperty("name", e.getPlayer().getName());
            manager.log().add("died", o);
        }
    }
}
