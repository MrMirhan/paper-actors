# paper-actors

Fake players on a Paper or a Minestom test server, controlled remotely through a signed HTTP API.

The actors are server-side players. They have no client and no network connection, so a server can keep hundreds of them.
Every action goes through the server's own packet handlers. Thus other plugins see the same events that a real player
causes: block break and place, interact, inventory click, chat command, sign change.

- **Paper:** a plugin (`paper-actors-<version>.jar`).
- **Minestom:** a library that the test server calls (`minestom-actors-<version>.jar`). See [Minestom](#minestom).

Both have the same API, so the same clients drive them.

Use it on a **test or filming server only**. The actors switch themselves off on a server that is not in the
`allowed-servers` list.

## Uses

- **Plugin tests.** Open a menu, click a slot, buy from a shop, break a block, run a command, and read the result.
- **Load tests.** Spawn a crowd and read TPS, tick times, chunks and heap before and after.
- **Filmed scenes.** Play a timed scene: actors walk, fight, chat and build while a camera records them.

## Requirements

- Paper 26.2 (the plugin uses server internals; other versions are not tested), or Minestom `2026.10.07-26.2`
- Java 25

## Download

Get `paper-actors-<version>.jar` (Paper), `minestom-actors-<version>.jar` (Minestom) and `actors_client.py` from the
[releases](https://github.com/MrMirhan/paper-actors/releases). `SHA256SUMS.txt` has the checksums.

## Build

```bash
./gradlew build
```

The Paper jar is in `build/libs/paper-actors-<version>.jar` (do not use the `-dev` jar), and the Minestom jar is in
`minestom/build/libs/minestom-actors-<version>.jar`.

## Setup

1. Set a unique `server-name` in `server.properties`, for example `film-test`.
2. Put the jar in `plugins/` and start the server once. The plugin writes `plugins/PaperActors/config.yml`.
3. In `config.yml`, add the server name to `allowed-servers` and set `secret` (16 characters or more), or set the
   `PAPER_ACTORS_SECRET` environment variable.
4. Restart the server.

The API listens on `127.0.0.1:4331`. To reach it from a different machine, use an SSH tunnel. If you cannot use a tunnel,
set `api.host` to a private address. The secret must then be 32 characters or more. Never put the API on a public address
without a firewall.

See [`src/main/resources/config.yml`](src/main/resources/config.yml) for all settings: actor limit (up to 1000), spawns per
tick, view distance, game mode, UUID namespace, reserved names and scene commands.

## Minestom

Minestom has no plugins, so `minestom-actors` is a library. The test server starts it in its `main`, after
`MinecraftServer.init()`:

```java
MinestomActors actors = MinestomActors.start(ActorSettings.of(world, new Pos(0, 41, 0))
        .allowedServers(List.of("shipyard-test"))   // this build may run actors only on these servers
        .limits(200, 4)                             // at most 200 actors; a crowd joins 4 a tick
        .viewDistance(4));
server.start("0.0.0.0", 25565);
```

- **The server's name.** Minestom has no `server.properties`. The name comes from `ACTORS_SERVER_NAME` in the environment
  (or `serverName(...)`), and `allowedServers` must list it. Keep the list in the test server's code, and set the name on the
  test machine only. The same build started anywhere else then refuses to run actors.
- **The secret.** It comes from `PAPER_ACTORS_SECRET` (or `secret(...)`), with the same rules as on Paper.
- **The dependency.** Add the jar to the test server's build, for example
  `implementation(files("libs/minestom-actors-0.2.0.jar"))`. The jar does not contain Minestom: the server supplies it.
- **Your own data.** `actors.isActor(player)` says whether a player is an actor, so a game that saves its players' data can
  leave actors out.
- **Try it.** `./gradlew :minestom:runTestServer` starts a small flat test server with the actors on 127.0.0.1:4331.
- **Threads.** Minestom ticks the world on one thread unless it is told otherwise. For a crowd, start the server with
  `-Dminestom.dispatcher-threads=<cores>` (see [Load](#load)).
- **Metrics.** `/v1/metrics` also gives `actorsMspt`: the part of each tick that driving the actors took. The rest of
  `mspt` is the server's and the game's own work.

How an actor works on Minestom:

- **It joins as a player does.** It goes through the server's own configuration and join steps, so the game's
  `AsyncPlayerConfigurationEvent` and spawn listeners see it. Minestom's own test players join the same way. It skips the
  known-packs exchange, so the server sends it no registry data.
- **It answers like a client.** It answers keep-alives, so the server does not kick it. It acknowledges chunk batches, so the
  world around it keeps loading.
- **It moves itself.** Minestom does not move players: a client says where it is. So the actor walks with Minestom's own
  collision code (gravity, step-up by a jump, walls) and sends its position as a client does: `PlayerMoveEvent`.
- **Its actions are a client's packets.** They go into the player's packet queue, and the server handles them with its own
  listeners. The game sees `PlayerBlockInteractEvent`, `PlayerBlockPlaceEvent`, `PlayerBlockBreakEvent` (after the
  server's own break time), `InventoryPreClickEvent`, `PlayerEntityInteractEvent`, `EntityAttackEvent`, `PlayerChatEvent`
  and commands.
- **Minestom applies no damage.** An attack fires `EntityAttackEvent` (a `hit` in the event log), and the game's combat
  code decides the damage.
- **Scene commands** run with the server's console sender; Minestom has only the commands the game registers. A scene's
  `tickRate` is refused, because Minestom has no tick rate command.
- **What a crowd measures.** A crowd measures what the game costs per player in a tick: movement, chunks, entities and
  events. Packet writing, compression and the network are not measured, because actors have no socket.

Where it differs from Paper:

- **Joining.** An actor takes a tick or two to join. Until it is in, `state` gives its name, UUID and `task: "joining"`
  only, and `act` and `actor` answer 409.
- **Screens.** `trade` and `rename` answer 409 when their screen (trading, anvil) is not open. Minestom has no trading of its
  own: a trade reaches the game only if the game handles `ClientSelectTradePacket`.
- **Fighting.** `fight` with `style: "1.9"` waits out a 625 ms cooldown between swings, but Minestom keeps no attack strength
  to check.
- **Names and skins.** Minestom keeps no player data, so it does not refuse names that real players used. It refuses
  reserved names and real players who are online. A skin is `{value, signature}`, or a name from the `skins(folder)` setting.

Checked against Minestom 2026.10.07-26.2 through the minecraft MCP server's `actors` tools, with the test server's own
listeners reporting each event:

- A crowd of 50 (view 3) joined. On a flat world the average tick went from 0.36 ms to 0.68 ms, with 144 chunks loaded.
- Movement: `goto` around a wall, `teleport`, `walk`, `stroll`, `jump` and `sneak`.
- Screens: a chest menu opened with `use_on`, and a left click put its item on the cursor. A left `drag` split 61 planks over
  three slots. `trade` and a screen `button` reached the game's handlers. `rename` worked on an anvil.
- Hands: `equip`, `select`, `place`, `break` after the break time, `use`, `swap_hands` and `drop`.
- The rest: `interact` with a villager, `sign`, `chat`, a command, `attack` and `fight` (26 hits in 4 s), and a scene with
  `walk` and `chat`.

## Load

Minestom 2026.10.07-26.2 on an i9-11900F (8 cores), `-Xmx6g`, with other work on the machine. The test server was a flat world
with actors spread over 400 x 400 blocks at view distance 4. At each step the test measured the server for 40 seconds.

- **Idle:** the actors stood and turned their heads, a client's packet every tick each, and 10% walked across the area.
- **Busy:** 30% walked, 10% fought in pairs, 10% placed a block beside them and broke it, 5% chatted and ran a command, and the
  rest stood. In the whole run, the game's own listeners counted 17.8 million moves, 5,322 breaks, 5,722 places, 3,445 chats,
  3,445 commands and 264,326 attacks.

| Actors | Idle, 1 thread: MSPT / TPS | Idle, 8 threads: MSPT / TPS | Busy, 8 threads: MSPT avg / p95 / TPS | Busy: actors' share | Busy: CPU cores | Busy: RSS |
|---:|---:|---:|---:|---:|---:|---:|
| 100 | 2.7 / 20 | 1.2 / 20 | 1.3 / 3.4 / 20 | 0.25 ms | 0.12 | 466 MB |
| 500 | 4.3 / 20 | 3.2 / 20 | 3.9 / 13.1 / 20 | 1.0 ms | 0.30 | 486 MB |
| 1000 | 7.3 / 20 | 7.0 / 20 | 6.8 / 13.9 / 20 | 1.9 ms | 0.54 | 512 MB |
| 1500 | 13.0 / 20 | 8.8 / 20 | 10.5 / 18.2 / 20 | 2.7 ms | 0.87 | 520 MB |
| 2000 | 22.9 / 20 | 13.0 / 20 | 16.4 / 50.8 / 20 | 3.9 ms | 1.24 | 651 MB |
| 3000 | 27.6 / 20 | 26.2 / 20 | 25.9 / 54.4 / 20 | 5.6 ms | 2.01 | 841 MB |
| 4000 | 52.4 / 17.1 | 34.3 / 19.8 | 37.6 / 66.3 / 19.3 | 7.3 ms | 3.04 | 1104 MB |
| 5000 | 68.3 / 15.0 | 41.8 / 19.1 | 64.7 / 127.5 / 15.5 | 10.3 ms | 3.64 | 1109 MB |

- On one 400 x 400 area, the server kept 20 TPS up to about 4000 busy actors. Players spread over several areas or instances
  let more threads work.
- Eight dispatcher threads made the tick 30-40% shorter from 2000 actors on.
- An actor costs little memory: 5000 took 1.1 GB of RSS.
- Driving the actors (`actorsMspt`) was about a sixth of the tick. Most of the rest was the players seeing each other: their
  moves sent to everyone near.

The test is `minestom/load/load_roles.py`.

## API

Every request has three headers:

| Header | Value |
|---|---|
| `X-Actors-Time` | the time in epoch milliseconds (30 s skew is the maximum) |
| `X-Actors-Nonce` | 16-64 random characters of `[0-9a-zA-Z-]`, used one time only |
| `X-Actors-Signature` | `hex(HMAC-SHA256(key, canonical))` |

`key = HMAC-SHA256(secret, "paper-actors/v1")`, and `canonical` is these lines joined with `\n`:
the method, the path with its query, the time, the nonce, and `hex(SHA-256(body))`.

| Method | Path | Does |
|---|---|---|
| GET | `/v1/state` | the actors and the scene status |
| GET | `/v1/actor?name=` | one actor: position, health, food, inventory, open screen, cursor item |
| GET | `/v1/metrics` | TPS, tick times (avg, p95, max), chunks, entities, players, heap |
| GET | `/v1/events?since=&limit=` | the event log: hits, deaths, chat, errors, task results |
| POST | `/v1/spawn` | one actor (`name`), or a crowd (`count` + `prefix`, or `names`) with `at`, `spread`, `skin`, `profile`, `seed`, `view` |
| POST | `/v1/despawn` | by `name`, by `prefix`, or all |
| POST | `/v1/act` | one action for one actor: `{"name": "...", "action": {"do": "...", ...}}` |
| POST | `/v1/scene/play` | a timed scene: actors, steps at `t` seconds, optional setup and cleanup commands |
| POST | `/v1/scene/stop` | stop the scene and remove its actors |

### Actions

| Action | Fields | Does |
|---|---|---|
| `goto` | `x`, `y`, `z`, `within`, `sprint` | walks a path that it finds: around walls, up one-block steps, down drops of up to 3 blocks |
| `walk`, `stroll`, `look`, `watch`, `jump`, `sneak`, `teleport` | | movement and head turns |
| `fight`, `attack`, `swing` | `target`, `style` (`1.8` or `1.9`), `seconds` | combat that looks like a person: reaction delay, clicks per second, aim wander |
| `select`, `use`, `equip`, `drop`, `swap_hands` | `slot`, `item`, `stack` | hotbar and held items |
| `use_on` | `x`, `y`, `z`, `face` | right-clicks a block: opens a chest or a menu block, pushes a button |
| `place`, `break` | `x`, `y`, `z` | places the held block; digs for the survival time |
| `interact` | `target` | right-clicks a player, or the nearest entity of a type (`villager`) |
| `click` | `slot`, `type`, `hotbar` | `left`, `right`, `shift`, `shift_right`, `number`, `offhand`, `middle`, `drop`, `drop_stack`, `double`, `pickup_all`, `outside_left`, `outside_right` |
| `drag`, `close`, `button` | `slots`, `button` / - / `id` | drags the cursor stack; closes the screen; enchanting, stonecutter, loom and lectern buttons |
| `rename`, `trade`, `sign` | `text` / `index` / `x`, `y`, `z`, `lines`, `front` | anvil name (`text`, since `name` is the actor's), villager offer, sign text |
| `command`, `chat` | `command` / `text` (256 characters or fewer) | as the actor, with its own permissions |
| `idle`, `stop` | `seconds` | wait; cancel the current task |

Profiles (`rookie`, `mid`, `tryhard`) set turn speed, clicks per second and aim. The same `seed` gives the same moves.

## Clients

- **Python:** [`client/actors_client.py`](client/actors_client.py), standard library only.

  ```python
  from actors_client import ActorsClient
  c = ActorsClient("127.0.0.1", 4331, secret)
  c.spawn_crowd(count=50, prefix="Crowd", spread=24, at=[0, 64, 0], view=2)
  c.act("Crowd1", {"do": "goto", "x": 10, "y": 64, "z": 5})
  print(c.metrics())
  ```

- **MCP:** the `actors` tool set of [minecraft-mcp-server](https://github.com/MrMirhan/minecraft-mcp-server) lets an AI
  assistant use the actors. Set `PAPER_ACTORS_URL` and `PAPER_ACTORS_SECRET`.

## Safety

- The plugin refuses servers that are not in `allowed-servers`.
- Actor UUIDs come from `uuid-namespace` and the name, so an actor never gets the data of a real account with that name.
- By default, the plugin refuses a name that a real player used on this server, and names in `reserved`.
- Scene console commands are off by default. When you turn them on, only the commands in the allowed list run.
- Actors do not need passwords and do not register accounts.

## Tests

```bash
./gradlew test        # the core, the Paper plugin, and the Minestom actors on a real Minestom server in the test JVM
python3 -m unittest discover -s client
```

## Credits

Some ideas come from [CraftWire](https://github.com/UXPLIMA/craftwire) (MIT).

## License

[MIT](LICENSE)
