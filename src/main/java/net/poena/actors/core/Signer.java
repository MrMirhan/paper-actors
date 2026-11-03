package net.poena.actors.core;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Request signing for the control API. A request carries three headers: X-Actors-Time (epoch ms), X-Actors-Nonce (random, 16-64
 * chars of [0-9a-zA-Z-]) and X-Actors-Signature = hex(HMAC-SHA256(secret, canonical)) where canonical is
 * {@code METHOD \n path-with-query \n time \n nonce \n sha256-hex(body)}.
 */
public final class Signer {
    public static final long MAX_SKEW_MS = 30_000;

    private final byte[] secret;
    private final ReplayGuard guard = new ReplayGuard(4096);

    /** The signing key is derived from the shared secret, so the AgentGate handshake and this API never sign with the same key. */
    public Signer(String secret) {
        this.secret = hmac(secret.getBytes(StandardCharsets.UTF_8), "paper-actors/v1".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String canonical(String method, String pathAndQuery, String time, String nonce, byte[] body) {
        return method.toUpperCase(Locale.ROOT) + "\n" + pathAndQuery + "\n" + time + "\n" + nonce + "\n" + sha256Hex(body);
    }

    public static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String sign(String method, String pathAndQuery, String time, String nonce, byte[] body) {
        return HexFormat.of().formatHex(hmac(secret, canonical(method, pathAndQuery, time, nonce, body).getBytes(StandardCharsets.UTF_8)));
    }

    /** Returns null when the request is authentic and fresh, otherwise a short reason. The nonce is remembered only after the signature checks out. */
    public String verify(String method, String pathAndQuery, String time, String nonce, String signature, byte[] body, long nowMs) {
        if (time == null || nonce == null || signature == null) {
            return "missing signature headers";
        }
        if (!nonce.matches("[0-9a-zA-Z-]{16,64}")) {
            return "bad nonce";
        }
        long t;
        try {
            t = Long.parseLong(time);
        } catch (NumberFormatException e) {
            return "bad time";
        }
        if (Math.abs(nowMs - t) > MAX_SKEW_MS) {
            return "stale request";
        }
        byte[] expected = sign(method, pathAndQuery, time, nonce, body).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, signature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))) {
            return "bad signature";
        }
        if (!guard.firstSeen(nonce)) {
            return "replayed request";
        }
        return null;
    }

    /** Bounded memory of recent nonces. Anything older than MAX_SKEW_MS is rejected by the time check anyway, so the oldest can be forgotten. */
    static final class ReplayGuard {
        private final int capacity;
        private final Set<String> seen = new HashSet<>();
        private final ArrayDeque<String> order = new ArrayDeque<>();

        ReplayGuard(int capacity) {
            this.capacity = capacity;
        }

        synchronized boolean firstSeen(String nonce) {
            if (!seen.add(nonce)) {
                return false;
            }
            order.addLast(nonce);
            while (order.size() > capacity) {
                seen.remove(order.pollFirst());
            }
            return true;
        }
    }
}
