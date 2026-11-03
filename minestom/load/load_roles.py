"""A stepped load test of minestom-actors with players' work: at each step from 50 to 5000 actors, 30% walk across the area,
10% fight in pairs, 10% place a block beside them and break it again, 5% chat and run a command, and the rest stand and look
around. The server's tick, the actors' own share of it, the JVM's CPU and memory are measured while they do it. One JSON line
per step on stdout, and a table at the end.

    python3 load_roles.py <paper-actors client dir> <secret file>
"""
import json
import os
import random
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, sys.argv[1])
from actors_client import ActorsClient  # noqa: E402

STEPS = [50, 100, 200, 500, 750, 1000, 1500, 2000, 3000, 4000, 5000]
SPREAD = 200
VIEW = 4
ROLES = [("walk", 0.30), ("fight", 0.10), ("build", 0.10), ("talk", 0.05)]
WINDOW = 40           # seconds of measuring at each step
EVERY = 10            # the builders and talkers act again every this many seconds
CLK = os.sysconf("SC_CLK_TCK")

c = ActorsClient("127.0.0.1", 4331, open(sys.argv[2]).read().strip(), timeout=60)
rng = random.Random(11)
pool = ThreadPoolExecutor(16)
failed = []


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


def act(name, action):
    try:
        c.act(name, action)
        return True
    except Exception as e:  # one refused action does not stop the test; it is counted
        failed.append(f"{action.get('do')}: {e}")
        return False


def run(calls):
    """Sends the calls 16 at a time; returns how long that took."""
    t = time.time()
    list(pool.map(lambda nc: act(*nc), calls))
    return time.time() - t


def joined():
    return [a for a in c.state()["actors"] if a.get("task") != "joining"]


rows = []
names = []
for step, target in enumerate(STEPS, 1):
    need = target - len(names)
    prefix = f"R{step}_"
    t0 = time.time()
    c.spawn_crowd(count=need, prefix=prefix, spread=SPREAD, at=[0, 40, 0], view=VIEW, seed=str(step))
    width = len(str(need))
    names += [f"{prefix}{i:0{width}d}" for i in range(1, need + 1)]
    while True:
        here = joined()
        if len(here) >= target:
            break
        if time.time() - t0 > 180:
            print(json.dumps({"step": target, "error": "the crowd did not join in 180 s"}), flush=True)
            sys.exit(1)
        time.sleep(1)
    join_s = time.time() - t0
    where = {a["name"]: a for a in here}

    # This step's roles, drawn again from everyone.
    shuffled = names[:]
    rng.shuffle(shuffled)
    roles, at = {}, 0
    for role, share in ROLES:
        n = int(len(shuffled) * share)
        if role == "fight":
            n -= n % 2
        roles[role] = shuffled[at:at + n]
        at += n
    failed.clear()
    setup = []
    setup += [(n, {"do": "stroll", "x": rng.uniform(-SPREAD, SPREAD), "z": rng.uniform(-SPREAD, SPREAD)}) for n in roles["walk"]]
    fighters = roles["fight"]
    for a, b in zip(fighters[0::2], fighters[1::2]):
        p = where[a]
        setup.append((b, {"do": "teleport", "x": p["x"] + 2, "y": p["y"], "z": p["z"]}))
    setup += [(n, {"do": "equip", "item": "oak_planks", "slot": 0}) for n in roles["build"]]
    setup_s = run(setup)
    time.sleep(1)
    run([(a, {"do": "fight", "target": b, "style": "1.8", "seconds": WINDOW + 10}) for a, b in zip(fighters[0::2], fighters[1::2])]
        + [(b, {"do": "fight", "target": a, "style": "1.8", "seconds": WINDOW + 10}) for a, b in zip(fighters[0::2], fighters[1::2])])

    def work(stop):
        """The builders place a plank beside them and break it; the talkers chat and run a command; every EVERY seconds."""
        k = 0
        while not stop.is_set():
            calls = []
            for n in roles["build"]:
                p = where.get(n)
                if p:
                    x, z = int(p["x"] // 1) + 1, int(p["z"] // 1)
                    calls.append((n, {"do": "place", "x": x, "y": 40, "z": z}))
            for n in roles["talk"]:
                calls.append((n, {"do": "chat", "text": f"hello {k}"}))
                calls.append((n, {"do": "command", "command": "where"}))
            run(calls)
            stop.wait(1)
            run([(n, {"do": "break", "x": int(where[n]["x"] // 1) + 1, "y": 40, "z": int(where[n]["z"] // 1)})
                 for n in roles["build"] if n in where])
            k += 1
            stop.wait(EVERY - 1)

    stop = threading.Event()
    worker = threading.Thread(target=work, args=(stop,))
    worker.start()
    time.sleep(3)
    cpu0, w0 = cpu_seconds(), time.time()
    samples = []
    for _ in range(WINDOW // 5):
        time.sleep(5)
        samples.append(c.metrics())
    cpu = (cpu_seconds() - cpu0) / (time.time() - w0)
    stop.set()
    worker.join()
    events = c.events(0, 500)["events"]
    m = samples[-1]
    avg = lambda key, sub: round(sum(s[key].get(sub, 0) for s in samples) / len(samples), 2)  # noqa: E731
    row = {
        "actors": target,
        "join_s": round(join_s, 1),
        "setup_s": round(setup_s, 1),
        "tps_1m": m["tps"][0],
        "mspt_avg": avg("mspt", "avg"),
        "mspt_p95": max(s["mspt"].get("p95", 0) for s in samples),
        "mspt_max": max(s["mspt"].get("max", 0) for s in samples),
        "actors_mspt": avg("actorsMspt", "avg"),
        "cpu_cores": round(cpu, 2),
        "heap_mb": round(sum(s["heap"]["usedMb"] for s in samples) / len(samples)),
        "rss_mb": rss_mb(),
        "chunks": m["chunks"],
        "hits_in_last_500_events": sum(1 for e in events if e.get("ev") == "hit"),
        "failed_calls": len(failed),
        "failed_kinds": sorted(set(f.split(":")[0] for f in failed))[:5],
    }
    rows.append(row)
    print(json.dumps(row), flush=True)
    if row["mspt_avg"] > 200:
        print(json.dumps({"stop": "the average tick passed 200 ms"}), flush=True)
        break

print("\n| actors | join s | TPS (1 min) | MSPT avg | MSPT p95 | MSPT max | actors' share | CPU cores | heap MB | RSS MB | failed calls |")
print("|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
for r in rows:
    print(f"| {r['actors']} | {r['join_s']} | {r['tps_1m']} | {r['mspt_avg']} | {r['mspt_p95']} | {r['mspt_max']} | {r['actors_mspt']} | "
          f"{r['cpu_cores']} | {r['heap_mb']} | {r['rss_mb']} | {r['failed_calls']} |")
c.despawn()
pool.shutdown()
