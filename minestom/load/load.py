"""A stepped load test of minestom-actors: crowds of 50 up to 5000 on the Minestom test server, with the server's tick, the
actors' own share of it, the JVM's CPU and memory at each step. One JSON line per step on stdout, and a table at the end.

    python3 load.py <paper-actors client dir> <secret file>
"""
import json
import os
import random
import sys
import time

sys.path.insert(0, sys.argv[1])
from actors_client import ActorsClient  # noqa: E402

STEPS = [50, 100, 200, 500, 750, 1000, 1500, 2000, 3000, 4000, 5000]
SPREAD = 200          # blocks around (0, 40, 0): a 400 x 400 area
VIEW = 4              # each actor's view distance, the setting's default
WALKERS = 0.1         # the share of actors sent walking at each step (a long stroll); the rest idle and turn their heads
SAMPLES = 8           # metrics every 5 s after the crowd joined
CLK = os.sysconf("SC_CLK_TCK")

c = ActorsClient("127.0.0.1", 4331, open(sys.argv[2]).read().strip(), timeout=60)
rng = random.Random(7)


def server_pid():
    for pid in os.listdir("/proc"):
        if pid.isdigit():
            try:
                cmd = open(f"/proc/{pid}/cmdline", "rb").read().replace(b"\0", b" ")
            except OSError:
                continue
            if b"java" in cmd and b"net.poena.actors.minestom.TestServer" in cmd:
                return int(pid)
    raise SystemExit("no test server")


PID = server_pid()


def cpu_seconds():
    f = open(f"/proc/{PID}/stat").read().rsplit(")", 1)[1].split()
    return (int(f[11]) + int(f[12])) / CLK


def rss_mb():
    for line in open(f"/proc/{PID}/status"):
        if line.startswith("VmRSS:"):
            return int(line.split()[1]) // 1024
    return 0


def joined_count():
    return sum(1 for a in c.state()["actors"] if a.get("task") != "joining")


rows = []
names = []
for step, target in enumerate(STEPS, 1):
    need = target - len(names)
    prefix = f"S{step}_"
    t0 = time.time()
    c.spawn_crowd(count=need, prefix=prefix, spread=SPREAD, at=[0, 40, 0], view=VIEW, seed=str(step))
    width = len(str(need))
    names += [f"{prefix}{i:0{width}d}" for i in range(1, need + 1)]
    while joined_count() < target:
        if time.time() - t0 > 180:
            print(json.dumps({"step": target, "error": "the crowd did not join in 180 s"}), flush=True)
            sys.exit(1)
        time.sleep(1)
    join_s = time.time() - t0
    # A tenth of everyone walks somewhere in the area; the rest stand and look around.
    def walk(n):
        try:
            c.act(n, {"do": "stroll", "x": rng.uniform(-SPREAD, SPREAD), "z": rng.uniform(-SPREAD, SPREAD)})
        except Exception as e:  # an actor that cannot walk right now does not stop the test
            print(json.dumps({"warn": str(e)}), flush=True)
    from concurrent.futures import ThreadPoolExecutor
    with ThreadPoolExecutor(2) as pool:
        list(pool.map(walk, rng.sample(names, int(len(names) * WALKERS))))
    time.sleep(5)
    cpu0, w0 = cpu_seconds(), time.time()
    samples = []
    for _ in range(SAMPLES):
        time.sleep(5)
        samples.append(c.metrics())
    cpu = (cpu_seconds() - cpu0) / (time.time() - w0)
    m = samples[-1]
    avg = lambda key, sub: round(sum(s[key].get(sub, 0) for s in samples) / len(samples), 2)  # noqa: E731
    row = {
        "actors": target,
        "join_s": round(join_s, 1),
        "tps_1m": m["tps"][0],
        "mspt_avg": avg("mspt", "avg"),
        "mspt_p95": max(s["mspt"].get("p95", 0) for s in samples),
        "mspt_max": max(s["mspt"].get("max", 0) for s in samples),
        "actors_mspt": avg("actorsMspt", "avg"),
        "cpu_cores": round(cpu, 2),
        "heap_mb": round(sum(s["heap"]["usedMb"] for s in samples) / len(samples)),
        "rss_mb": rss_mb(),
        "chunks": m["chunks"],
        "entities": m["entities"],
        "players": m["players"],
    }
    rows.append(row)
    print(json.dumps(row), flush=True)
    if row["mspt_avg"] > 200:
        print(json.dumps({"stop": "the average tick passed 200 ms"}), flush=True)
        break

print("\n| actors | join s | TPS (1 min) | MSPT avg | MSPT p95 | MSPT max | actors' share | CPU cores | heap MB | RSS MB | chunks |")
print("|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
for r in rows:
    print(f"| {r['actors']} | {r['join_s']} | {r['tps_1m']} | {r['mspt_avg']} | {r['mspt_p95']} | {r['mspt_max']} | {r['actors_mspt']} | "
          f"{r['cpu_cores']} | {r['heap_mb']} | {r['rss_mb']} | {r['chunks']} |")
c.despawn()
