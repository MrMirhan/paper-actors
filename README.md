# paper-actors

A Paper plugin that adds fake players and lets you control them remotely through a signed HTTP API.

The actors are server-side players. They have no client and no network connection, so a server can keep hundreds of them.
Every action goes through the server's own packet handlers. Thus other plugins see the same events that a real player
causes: block break and place, interact, inventory click, chat command, sign change.

Use it on a **test or filming server only**. The plugin switches itself off on a server that is not in its
`allowed-servers` list.

## Uses

- **Plugin tests.** Open a menu, click a slot, buy from a shop, break a block, run a command, and read the result.
- **Load tests.** Spawn a crowd and read TPS, tick times, chunks and heap before and after.
- **Filmed scenes.** Play a timed scene: actors walk, fight, chat and build while a camera records them.

## Requirements

- Paper 26.2 (the plugin uses server internals; other versions are not tested)
- Java 25

## Download

Get `paper-actors-<version>.jar` and `actors_client.py` from the
[releases](https://github.com/MrMirhan/paper-actors/releases). `SHA256SUMS.txt` has the checksums.

## Build

```bash
./gradlew build
```

The jar is in `build/libs/paper-actors-<version>.jar`. Do not use the `-dev` jar.

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
| `rename`, `trade`, `sign` | `name` / `index` / `x`, `y`, `z`, `lines`, `front` | anvil name, villager offer, sign text |
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
./gradlew test
python3 -m unittest discover -s client
```

## Credits

Some ideas come from [CraftWire](https://github.com/UXPLIMA/craftwire) (MIT).

## License

[MIT](LICENSE)
