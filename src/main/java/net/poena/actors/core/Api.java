package net.poena.actors.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Signed JSON over HTTP, on the loopback interface unless the plugin is told another address (with a longer secret). */
public final class Api implements AutoCloseable {
    public static final int MAX_BODY = 64 * 1024;
    private static final Gson GSON = new Gson();

    private final HttpServer http;
    /** Calls answered at once: each waits for the server's next tick, so a crowd's commands need several in flight. */
    public static final int THREADS = 16;

    private final ExecutorService pool = Executors.newFixedThreadPool(THREADS, r -> {
        Thread t = new Thread(r, "paper-actors-api");
        t.setDaemon(true);
        return t;
    });
    private final Signer signer;
    private final Control control;
    private final Consumer<String> warn;

    public Api(int port, Signer signer, Control control, Consumer<String> warn) throws IOException {
        this(InetAddress.getLoopbackAddress(), port, signer, control, warn);
    }

    /** On another address than loopback every request still needs the signature; the plugin asks for a longer secret there. */
    public Api(InetAddress host, int port, Signer signer, Control control, Consumer<String> warn) throws IOException {
        this.signer = signer;
        this.control = control;
        this.warn = warn;
        this.http = HttpServer.create(new InetSocketAddress(host, port), 16);
        this.http.setExecutor(pool);
        this.http.createContext("/", this::handle);
    }

    public void start() {
        http.start();
    }

    public int port() {
        return http.getAddress().getPort();
    }

    public InetAddress address() {
        return http.getAddress().getAddress();
    }

    @Override
    public void close() {
        http.stop(0);
        pool.shutdownNow();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            byte[] body = ex.getRequestBody().readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) {
                reply(ex, 413, error("body too large"));
                return;
            }
            String method = ex.getRequestMethod();
            String target = ex.getRequestURI().getRawPath() + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery());
            String why = signer.verify(method, target, ex.getRequestHeaders().getFirst("X-Actors-Time"), ex.getRequestHeaders().getFirst("X-Actors-Nonce"),
                    ex.getRequestHeaders().getFirst("X-Actors-Signature"), body, System.currentTimeMillis());
            if (why != null) {
                warn.accept("refused " + method + " " + ex.getRequestURI().getRawPath() + ": " + why);
                reply(ex, 401, error("unauthorized"));
                return;
            }
            JsonObject out = route(method, ex.getRequestURI().getPath(), ex.getRequestURI().getRawQuery(), body);
            if (!out.has("ok")) {
                out.addProperty("ok", true);
            }
            reply(ex, 200, out);
        } catch (ApiException e) {
            reply(ex, e.status(), error(e.getMessage()));
        } catch (JsonParseException e) {
            reply(ex, 400, error("body is not valid JSON"));
        } catch (RuntimeException e) {
            warn.accept("handler failed: " + e);
            reply(ex, 500, error("internal error"));
        } finally {
            ex.close();
        }
    }

    private JsonObject route(String method, String path, String query, byte[] body) {
        boolean get = method.equals("GET");
        boolean post = method.equals("POST");
        return switch (path) {
            case "/v1/state" -> only(get, control::state);
            case "/v1/events" -> only(get, () -> control.events(longParam(query, "since", 0), (int) Math.min(500, longParam(query, "limit", 200))));
            case "/v1/actor" -> only(get, () -> control.actor(stringParam(query, "name")));
            case "/v1/metrics" -> only(get, control::metrics);
            case "/v1/spawn" -> only(post, () -> control.spawn(json(body)));
            case "/v1/despawn" -> only(post, () -> control.despawn(json(body)));
            case "/v1/act" -> only(post, () -> control.act(json(body)));
            case "/v1/scene/play" -> only(post, () -> control.scenePlay(json(body)));
            case "/v1/scene/stop" -> only(post, control::sceneStop);
            default -> throw new ApiException(404, "no such route");
        };
    }

    private static JsonObject only(boolean methodOk, java.util.function.Supplier<JsonObject> run) {
        if (!methodOk) {
            throw new ApiException(405, "method not allowed");
        }
        return run.get();
    }

    private static JsonObject json(byte[] body) {
        if (body.length == 0) {
            return new JsonObject();
        }
        try {
            JsonObject o = GSON.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
            if (o == null) {
                throw new ApiException(400, "body must be a JSON object");
            }
            return o;
        } catch (JsonSyntaxException | ClassCastException e) {
            throw new ApiException(400, "body must be a JSON object");
        }
    }

    private static String stringParam(String query, String key) {
        if (query != null) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).equals(key)) {
                    return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
        }
        throw new ApiException(400, key + " is required");
    }

    private static long longParam(String query, String key, long fallback) {
        if (query == null) {
            return fallback;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                try {
                    return Long.parseLong(pair.substring(eq + 1));
                } catch (NumberFormatException e) {
                    throw new ApiException(400, key + " must be a number");
                }
            }
        }
        return fallback;
    }

    private static JsonObject error(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", message);
        return o;
    }

    private static void reply(HttpExchange ex, int status, JsonObject body) throws IOException {
        byte[] out = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
    }
}
