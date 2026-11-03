package net.poena.actors.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** The checks that keep this plugin on test and filming servers. Every one fails closed. */
public final class Gate {
    private Gate() {
    }

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    /**
     * A server may run actors only when its identity is listed in allowed-servers. The identity is the {@code server-name} of
     * server.properties, or {@code name:port}. An empty list refuses everything.
     */
    public static Optional<String> refuseServer(String serverName, int port, List<String> allowed) {
        if (allowed == null || allowed.isEmpty()) {
            return Optional.of("allowed-servers is empty: list this server's server-name (server.properties) to enable actors");
        }
        String name = serverName == null ? "" : serverName.trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty() || name.equals("unknown server")) {
            return Optional.of("server-name in server.properties is missing or still the default; give this server a unique name first");
        }
        for (String entry : allowed) {
            String e = entry.trim().toLowerCase(Locale.ROOT);
            if (e.isEmpty() || e.equals("*")) {
                continue;
            }
            if (e.equals(name) || e.equals(name + ":" + port)) {
                return Optional.empty();
            }
        }
        return Optional.of("this server (" + serverName + ":" + port + ") is not in allowed-servers");
    }

    /** server-name from server.properties; empty when the file or key is missing, which refuses the server. */
    public static String readServerName(Path serverProperties) {
        java.util.Properties props = new java.util.Properties();
        try (var in = Files.newBufferedReader(serverProperties, StandardCharsets.UTF_8)) {
            props.load(in);
        } catch (IOException e) {
            return "";
        }
        return props.getProperty("server-name", "").trim();
    }

    /** The control API must be signed, so a secret that is empty or short is a refusal, not a warning. */
    public static Optional<String> refuseSecret(String secret) {
        if (secret == null || secret.trim().length() < 16) {
            return Optional.of("secret must be at least 16 characters (config secret, or PAPER_ACTORS_SECRET)");
        }
        return Optional.empty();
    }

    /** An API reachable from the network needs a long secret: anyone who can reach the port can try it. */
    public static Optional<String> refuseRemote(String host, String secret) {
        if (isLoopback(host)) {
            return Optional.empty();
        }
        if (secret == null || secret.trim().length() < 32) {
            return Optional.of("api.host " + host + " is not loopback: the secret must then be at least 32 characters");
        }
        return Optional.empty();
    }

    public static boolean isLoopback(String host) {
        return host == null || host.isBlank() || host.equals("localhost") || host.equals("::1") || host.startsWith("127.");
    }

    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** reserved: case-insensitive names; a trailing * matches a prefix. */
    public static boolean reserved(String name, List<String> reserved) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String r : reserved) {
            String x = r.trim().toLowerCase(Locale.ROOT);
            if (x.isEmpty()) {
                continue;
            }
            if (x.endsWith("*") ? n.startsWith(x.substring(0, x.length() - 1)) : n.equals(x)) {
                return true;
            }
        }
        return false;
    }

    /** Only the root command is checked: the first word, without the slash, against a lowercase allowlist. */
    public static boolean commandAllowed(String command, List<String> allowedRoots) {
        String c = command.trim();
        if (c.startsWith("/")) {
            c = c.substring(1);
        }
        if (c.isEmpty() || c.contains("\n") || c.contains("\r")) {
            return false;
        }
        String word = c.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        String root = word.substring(word.indexOf(':') + 1);
        return allowedRoots.stream().anyMatch(a -> a.equalsIgnoreCase(root));
    }
}
