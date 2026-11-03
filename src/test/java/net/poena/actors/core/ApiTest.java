package net.poena.actors.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiTest {
    private static final String SECRET = "0123456789abcdef-secret";

    private final List<String> calls = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private Api api;
    private Signer client;
    private final HttpClient http = HttpClient.newHttpClient();

    private final Control control = new Control() {
        public JsonObject state() {
            calls.add("state");
            return new JsonObject();
        }

        public JsonObject spawn(JsonObject r) {
            calls.add("spawn " + r.get("name").getAsString());
            return r;
        }

        public JsonObject despawn(JsonObject r) {
            calls.add("despawn");
            return new JsonObject();
        }

        public JsonObject act(JsonObject r) {
            calls.add("act");
            if (r.has("refuse")) {
                throw new ApiException(409, "target is out of reach");
            }
            return new JsonObject();
        }

        public JsonObject scenePlay(JsonObject r) {
            calls.add("scene");
            return new JsonObject();
        }

        public JsonObject sceneStop() {
            calls.add("stop");
            return new JsonObject();
        }

        public JsonObject events(long since, int limit) {
            calls.add("events " + since + " " + limit);
            return new JsonObject();
        }

        public JsonObject actor(String name) {
            calls.add("actor " + name);
            return new JsonObject();
        }

        public JsonObject metrics() {
            calls.add("metrics");
            return new JsonObject();
        }
    };

    @BeforeEach
    void start() throws Exception {
        api = new Api(0, new Signer(SECRET), control, warnings::add);
        api.start();
        client = new Signer(SECRET);
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private HttpResponse<String> send(String method, String path, String body, Signer signer, String nonce, long time) throws Exception {
        byte[] raw = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + path))
                .method(method, raw.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(raw));
        if (signer != null) {
            b.header("X-Actors-Time", Long.toString(time)).header("X-Actors-Nonce", nonce)
                    .header("X-Actors-Signature", signer.sign(method, path, Long.toString(time), nonce, raw));
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> ok(String method, String path, String body) throws Exception {
        return send(method, path, body, client, UUID.randomUUID().toString(), System.currentTimeMillis());
    }

    @Test
    void signedRequestsReachTheControl() throws Exception {
        assertEquals(200, ok("GET", "/v1/state", null).statusCode());
        HttpResponse<String> spawn = ok("POST", "/v1/spawn", "{\"name\":\"Rambox\"}");
        assertEquals(200, spawn.statusCode());
        assertTrue(JsonParser.parseString(spawn.body()).getAsJsonObject().get("ok").getAsBoolean());
        assertEquals(200, ok("GET", "/v1/events?since=5&limit=9", null).statusCode());
        assertEquals(List.of("state", "spawn Rambox", "events 5 9"), calls);
    }

    @Test
    void actorDetailAndMetricsAreSignedGets() throws Exception {
        assertEquals(200, ok("GET", "/v1/actor?name=Bot%5F01", null).statusCode());
        assertEquals(400, ok("GET", "/v1/actor", null).statusCode());
        assertEquals(200, ok("GET", "/v1/metrics", null).statusCode());
        assertEquals(405, ok("POST", "/v1/metrics", "{}").statusCode());
        assertEquals(401, send("GET", "/v1/metrics", null, null, null, 0).statusCode());
        assertEquals(List.of("actor Bot_01", "metrics"), calls);
    }

    @Test
    void unsignedRequestsNeverReachTheControl() throws Exception {
        assertEquals(401, send("GET", "/v1/state", null, null, null, 0).statusCode());
        assertEquals(401, send("POST", "/v1/despawn", "{\"all\":true}", null, null, 0).statusCode());
        assertTrue(calls.isEmpty());
    }

    @Test
    void wrongSecretReplayAndStaleAreRefused() throws Exception {
        assertEquals(401, send("GET", "/v1/state", null, new Signer("a-different-secret-1"), UUID.randomUUID().toString(), System.currentTimeMillis()).statusCode());
        String nonce = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        assertEquals(200, send("GET", "/v1/state", null, client, nonce, now).statusCode());
        assertEquals(401, send("GET", "/v1/state", null, client, nonce, now).statusCode());
        assertEquals(401, send("GET", "/v1/state", null, client, UUID.randomUUID().toString(), now - 120_000).statusCode());
        assertEquals(List.of("state"), calls);
        assertEquals(3, warnings.size());
    }

    @Test
    void theRefusalDoesNotSayWhy() throws Exception {
        HttpResponse<String> r = send("GET", "/v1/state", null, new Signer("a-different-secret-1"), UUID.randomUUID().toString(), System.currentTimeMillis());
        assertEquals("unauthorized", JsonParser.parseString(r.body()).getAsJsonObject().get("error").getAsString());
    }

    @Test
    void controlRefusalsKeepTheirStatus() throws Exception {
        HttpResponse<String> r = ok("POST", "/v1/act", "{\"refuse\":true}");
        assertEquals(409, r.statusCode());
        assertEquals("target is out of reach", JsonParser.parseString(r.body()).getAsJsonObject().get("error").getAsString());
    }

    @Test
    void wrongMethodUnknownRouteAndBadJson() throws Exception {
        assertEquals(405, ok("GET", "/v1/spawn", null).statusCode());
        assertEquals(405, ok("POST", "/v1/state", "{}").statusCode());
        assertEquals(404, ok("GET", "/v1/nope", null).statusCode());
        assertEquals(400, ok("POST", "/v1/act", "{not json").statusCode());
        assertEquals(400, ok("POST", "/v1/act", "[1,2]").statusCode());
        assertTrue(calls.isEmpty());
    }

    @Test
    void oversizedBodyIsRefused() throws Exception {
        String big = "{\"name\":\"" + "x".repeat(Api.MAX_BODY) + "\"}";
        assertEquals(413, ok("POST", "/v1/spawn", big).statusCode());
        assertTrue(calls.isEmpty());
    }

    @Test
    void listensOnLoopbackOnly() {
        assertTrue(api.address().isLoopbackAddress());
    }
}
