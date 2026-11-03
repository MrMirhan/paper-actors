package net.poena.actors.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SignerTest {
    private static final String SECRET = "0123456789abcdef-secret";
    private static final byte[] BODY = "{\"name\":\"Rambox\"}".getBytes(StandardCharsets.UTF_8);
    private static final long NOW = 1_700_000_000_000L;
    private static final String NONCE = "n0nce0123456789ab";

    @Test
    void goldenVectorMatchesThePythonClient() {
        // The same vector is asserted in client/test_actors_client.py.
        assertEquals("5c92a983d94b7677edaf95049b67e8c7f27d92c352ce282ff7fca9451ac122e5",
                new Signer(SECRET).sign("POST", "/v1/act", Long.toString(NOW), NONCE, BODY));
    }

    private String sig(Signer s, String method, String path, long time, String nonce, byte[] body) {
        return s.sign(method, path, Long.toString(time), nonce, body);
    }

    @Test
    void validRequestPasses() {
        Signer s = new Signer(SECRET);
        assertNull(s.verify("POST", "/v1/act", Long.toString(NOW), NONCE, sig(s, "POST", "/v1/act", NOW, NONCE, BODY), BODY, NOW + 1000));
    }

    @Test
    void replayIsRefused() {
        Signer s = new Signer(SECRET);
        String sig = sig(s, "POST", "/v1/act", NOW, NONCE, BODY);
        assertNull(s.verify("POST", "/v1/act", Long.toString(NOW), NONCE, sig, BODY, NOW));
        assertEquals("replayed request", s.verify("POST", "/v1/act", Long.toString(NOW), NONCE, sig, BODY, NOW));
    }

    @Test
    void tamperedBodyPathOrMethodFails() {
        Signer s = new Signer(SECRET);
        String sig = sig(s, "POST", "/v1/act", NOW, NONCE, BODY);
        byte[] other = "{\"name\":\"Herobrine\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals("bad signature", s.verify("POST", "/v1/act", Long.toString(NOW), NONCE, sig, other, NOW));
        assertEquals("bad signature", s.verify("POST", "/v1/spawn", Long.toString(NOW), NONCE, sig, BODY, NOW));
        assertEquals("bad signature", s.verify("GET", "/v1/act", Long.toString(NOW), NONCE, sig, BODY, NOW));
    }

    @Test
    void wrongSecretFails() {
        Signer attacker = new Signer("another-secret-0000000");
        Signer s = new Signer(SECRET);
        assertEquals("bad signature", s.verify("POST", "/v1/act", Long.toString(NOW), NONCE, sig(attacker, "POST", "/v1/act", NOW, NONCE, BODY), BODY, NOW));
    }

    @Test
    void staleAndFutureRequestsFail() {
        Signer s = new Signer(SECRET);
        String sig = sig(s, "POST", "/v1/act", NOW, NONCE, BODY);
        assertEquals("stale request", s.verify("POST", "/v1/act", Long.toString(NOW), NONCE, sig, BODY, NOW + Signer.MAX_SKEW_MS + 1));
        assertEquals("stale request", s.verify("POST", "/v1/act", Long.toString(NOW), NONCE, sig, BODY, NOW - Signer.MAX_SKEW_MS - 1));
    }

    @Test
    void malformedHeadersFail() {
        Signer s = new Signer(SECRET);
        assertNotNull(s.verify("POST", "/v1/act", null, NONCE, "x", BODY, NOW));
        assertEquals("bad nonce", s.verify("POST", "/v1/act", "1", "short", "x", BODY, NOW));
        assertEquals("bad time", s.verify("POST", "/v1/act", "soon", NONCE, "x", BODY, NOW));
    }

    @Test
    void aFailedAttemptDoesNotBurnTheNonce() {
        Signer s = new Signer(SECRET);
        String good = sig(s, "POST", "/v1/act", NOW, NONCE, BODY);
        assertEquals("bad signature", s.verify("POST", "/v1/act", Long.toString(NOW), NONCE, "deadbeef", BODY, NOW));
        assertNull(s.verify("POST", "/v1/act", Long.toString(NOW), NONCE, good, BODY, NOW));
    }
}
