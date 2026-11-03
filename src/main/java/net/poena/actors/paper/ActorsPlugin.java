package net.poena.actors.paper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import net.poena.actors.core.Api;
import net.poena.actors.core.Gate;
import net.poena.actors.core.Signer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class ActorsPlugin extends JavaPlugin {
    private ActorManager manager;
    private Api api;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration c = getConfig();

        Path root = getDataFolder().toPath().toAbsolutePath().getParent().getParent();
        String serverName = Gate.readServerName(root.resolve("server.properties"));
        var refusal = Gate.refuseServer(serverName, Bukkit.getPort(), c.getStringList("allowed-servers"));
        if (refusal.isPresent()) {
            getLogger().severe("Not starting: " + refusal.get());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        String secret = c.getString("secret", "");
        if (secret == null || secret.isBlank()) {
            secret = System.getenv("PAPER_ACTORS_SECRET");
        }
        var weak = Gate.refuseSecret(secret);
        if (weak.isPresent()) {
            getLogger().severe("Not starting: " + weak.get());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        GameMode mode;
        try {
            mode = GameMode.valueOf(c.getString("gamemode", "survival").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            mode = GameMode.SURVIVAL;
        }
        List<String> roots = c.getStringList("scene-commands.allowed");
        String host = c.getString("api.host", "127.0.0.1");
        var exposed = Gate.refuseRemote(host, secret);
        if (exposed.isPresent()) {
            getLogger().severe("Not starting: " + exposed.get());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        manager = new ActorManager(this, new ActorManager.Settings(Math.max(1, Math.min(ActorManager.HARD_MAX, c.getInt("max-actors", 8))),
                c.getStringList("reserved"), c.getBoolean("refuse-played-names", true), c.getStringList("allow-played"),
                c.getBoolean("scene-commands.enabled", false), roots, mode, c.getString("uuid-namespace", "PaperActor"),
                Math.max(2, Math.min(32, c.getInt("view-distance", 4))), Math.max(1, Math.min(20, c.getInt("spawns-per-tick", 2)))), serverName);
        getServer().getPluginManager().registerEvents(new ActorEvents(manager, c.getBoolean("quiet", true)), this);

        try {
            api = new Api(java.net.InetAddress.getByName(host), c.getInt("api.port", 4331), new Signer(secret), manager, getLogger()::warning);
            api.start();
        } catch (IOException e) {
            getLogger().severe("Not starting: cannot open the API port: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (!Gate.isLoopback(host)) {
            getLogger().warning("The actor API listens on " + host + ":" + api.port() + ", beyond this machine. Every request is signed; keep the secret private.");
        }
        getLogger().info("Actors ready: API on " + api.address().getHostAddress() + ":" + api.port() + ", server '" + serverName + "' is in allowed-servers.");
    }

    @Override
    public void onDisable() {
        if (api != null) {
            api.close();
        }
        if (manager != null) {
            manager.shutdown();
        }
    }
}
