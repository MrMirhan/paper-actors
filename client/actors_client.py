"""Signed client for the paper-actors control API (https://github.com/MrMirhan/paper-actors).

    from actors_client import ActorsClient
    c = ActorsClient("127.0.0.1", 4331, secret)       # secret: POENA_AGENT_SECRET
    c.spawn("Rambox", at=[0, 64, 0], skin="rambox", profile="tryhard", seed="take1")
    c.act("Rambox", {"do": "fight", "target": "Herobrine", "style": "1.9", "seconds": 8})
    c.events(since=0)

Standard library only. The API listens on 127.0.0.1; reach a remote server through an SSH tunnel.
"""
import hashlib
import hmac
import http.client
import json
import secrets
import time

KEY_LABEL = b"paper-actors/v1"


def signing_key(secret):
    return hmac.new(secret.encode("utf-8"), KEY_LABEL, hashlib.sha256).digest()


def canonical(method, path_and_query, timestamp, nonce, body):
    return "\n".join([method.upper(), path_and_query, str(timestamp), nonce, hashlib.sha256(body).hexdigest()])


def sign(secret, method, path_and_query, timestamp, nonce, body=b""):
    return hmac.new(signing_key(secret), canonical(method, path_and_query, timestamp, nonce, body).encode("utf-8"), hashlib.sha256).hexdigest()


class ActorsError(RuntimeError):
    def __init__(self, status, message):
        super().__init__(f"{status}: {message}")
        self.status = status


class ActorsClient:
    def __init__(self, host, port, secret, timeout=15):
        if len(secret or "") < 16:
            raise ValueError("secret must be at least 16 characters")
        self.host, self.port, self.secret, self.timeout = host, port, secret, timeout

    def request(self, method, path, body=None):
        raw = json.dumps(body).encode("utf-8") if body is not None else b""
        ts, nonce = int(time.time() * 1000), secrets.token_hex(16)
        headers = {
            "X-Actors-Time": str(ts), "X-Actors-Nonce": nonce,
            "X-Actors-Signature": sign(self.secret, method, path, ts, nonce, raw),
            "Content-Type": "application/json",
        }
        conn = http.client.HTTPConnection(self.host, self.port, timeout=self.timeout)
        try:
            conn.request(method, path, body=raw or None, headers=headers)
            res = conn.getresponse()
            data = json.loads(res.read() or b"{}")
        finally:
            conn.close()
        if res.status != 200:
            raise ActorsError(res.status, data.get("error", "request failed"))
        return data

    def state(self):
        return self.request("GET", "/v1/state")

    def events(self, since=0, limit=200):
        return self.request("GET", f"/v1/events?since={int(since)}&limit={int(limit)}")

    def spawn(self, name, **fields):
        return self.request("POST", "/v1/spawn", {"name": name, **fields})

    def spawn_crowd(self, count=None, prefix="Actor", names=None, **fields):
        """Queues many actors at once ({prefix, count} or {names}); they join spawns-per-tick each tick. Fields apply to all,
        spread=R places each within R blocks of `at` (or the world spawn)."""
        body = {"names": list(names)} if names else {"prefix": prefix, "count": int(count)}
        return self.request("POST", "/v1/spawn", {**body, **fields})

    def despawn(self, name=None, prefix=None):
        if prefix is not None:
            return self.request("POST", "/v1/despawn", {"prefix": prefix})
        return self.request("POST", "/v1/despawn", {"all": True} if name is None else {"name": name})

    def actor(self, name):
        """One actor in detail: position, health, inventory, the open screen with its slots and the cursor item."""
        from urllib.parse import quote
        return self.request("GET", f"/v1/actor?name={quote(name)}")

    def metrics(self):
        """TPS, tick times (avg, p95, max ms), loaded chunks, entities, players, heap, actors."""
        return self.request("GET", "/v1/metrics")

    def act(self, name, action):
        return self.request("POST", "/v1/act", {"name": name, **action})

    def scene_play(self, scene):
        return self.request("POST", "/v1/scene/play", {"scene": scene})

    def scene_stop(self):
        return self.request("POST", "/v1/scene/stop", {})
