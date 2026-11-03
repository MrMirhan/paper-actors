package net.poena.actors.minestom;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.collision.CollisionUtils;
import net.minestom.server.collision.PhysicsResult;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.inventory.AbstractInventory;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.network.packet.client.play.ClientAnimationPacket;
import net.minestom.server.network.packet.client.play.ClientAttackPacket;
import net.minestom.server.network.packet.client.play.ClientChatMessagePacket;
import net.minestom.server.network.packet.client.play.ClientClickWindowButtonPacket;
import net.minestom.server.network.packet.client.play.ClientClickWindowPacket;
import net.minestom.server.network.packet.client.play.ClientCloseWindowPacket;
import net.minestom.server.network.packet.client.play.ClientCommandChatPacket;
import net.minestom.server.network.packet.client.play.ClientEntityActionPacket;
import net.minestom.server.network.packet.client.play.ClientHeldItemChangePacket;
import net.minestom.server.network.packet.client.play.ClientInputPacket;
import net.minestom.server.network.packet.client.play.ClientInteractEntityPacket;
import net.minestom.server.network.packet.client.play.ClientNameItemPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerActionPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerLoadedPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerPositionAndRotationPacket;
import net.minestom.server.network.packet.client.play.ClientSelectTradePacket;
import net.minestom.server.network.packet.client.play.ClientTeleportConfirmPacket;
import net.minestom.server.network.packet.client.play.ClientUpdateSignPacket;
import net.minestom.server.network.packet.client.play.ClientUseItemPacket;
import net.minestom.server.utils.block.BlockBreakCalculation;
import net.poena.actors.core.ApiException;
import net.poena.actors.core.Human;
import net.poena.actors.core.Pathfinder;
import net.poena.actors.core.Rng;

/**
 * One fake player with its human-like brain, on Minestom. Minestom moves no player itself (a client says where it is), so the
 * actor walks with Minestom's own collision code and says where it is as a client does. Everything else it does is a client's
 * packet put in the player's queue: the server handles it in the player's tick with its own listeners, and the game sees the
 * events a person makes (PlayerMoveEvent, PlayerBlockInteractEvent, PlayerBlockPlaceEvent, PlayerBlockBreakEvent,
 * InventoryPreClickEvent, PlayerEntityInteractEvent, EntityAttackEvent, PlayerChatEvent, commands).
 *
 * <p>Everything here runs at the start of the server's tick (the scheduler), before the instances tick.
 */
final class Actor {
    private static final double REACH = 3.0;
    /** How far a block or an entity can be reached, as a client reaches it. */
    static final double HAND_REACH = 4.5;
    private static final double WALK = 0.21585;
    private static final double SPRINT = WALK * 1.3;
    private static final double SNEAK = WALK * 0.3;
    private static final int JOIN_TICKS = 20 * 30;

    final String name;
    final UUID uuid;
    final String profileName;
    final AtomicInteger hits = new AtomicInteger();
    volatile Player player;
    private final ActorConnection connection;
    private final Rng rng;
    private final Human.Profile prof;
    private Human.Gaze gaze;
    private final BiConsumer<String, JsonObject> events;
    private final Consumer<Player> onJoin;
    private Task task;
    private int delay;
    private long ticks;
    private double aimYaw;
    private double aimPitch;
    private double yaw;
    private double pitch;
    private int nextLookAround = 40;
    private boolean retired;
    private boolean joined;
    private int sequence;

    // Where the actor is, as its "client" sees it.
    private Pos pos;
    private double vy;
    private boolean onGround;
    private boolean horizontalCollision;
    private double moved;
    private PhysicsResult lastPhysics;
    private Pos sentPos;
    private byte sentInput;
    private boolean sentSprint;
    private boolean sneaking;

    private float fwd;
    private float str;
    private boolean jump;
    private boolean sprint;

    Actor(String name, UUID uuid, ActorConnection connection, String profileName, Rng rng, BiConsumer<String, JsonObject> events,
          Consumer<Player> onJoin) {
        this.name = name;
        this.uuid = uuid;
        this.connection = connection;
        this.profileName = profileName;
        this.rng = rng;
        this.prof = Human.profile(profileName);
        this.events = events;
        this.onJoin = onJoin;
    }

    ActorConnection connection() {
        return connection;
    }

    boolean joined() {
        return joined;
    }

    boolean retired() {
        return retired;
    }

    void retire() {
        retired = true;
        task = null;
    }

    String taskName() {
        return !joined ? "joining" : task == null ? "idle" : task.name();
    }

    private void emit(String ev, String key, Object value) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        if (value instanceof Number n) {
            o.addProperty(key, n);
        } else if (value != null) {
            o.addProperty(key, value.toString());
        }
        events.accept(ev, o);
    }

    private void send(ClientPacket packet) {
        player.addPacketToQueue(packet);
    }

    private int next() {
        return ++sequence;
    }

    // ---- per tick

    /** Returns false once the actor cannot go on (it never joined), so its manager forgets it. */
    boolean tick() {
        if (retired) {
            return false;
        }
        ticks++;
        if (!joined) {
            Player p = player;
            if (p != null && p.getInstance() != null && !p.isRemoved() && p.isOnline()) {
                joined = true;
                // A client says it has loaded the world: PlayerLoadedEvent.
                send(new ClientPlayerLoadedPacket());
                pos = p.getPosition();
                sentPos = pos;
                yaw = Human.fromMcYaw(pos.yaw());
                pitch = Human.fromMcPitch(pos.pitch());
                aimYaw = yaw;
                aimPitch = pitch;
                gaze = new Human.Gaze(rng, prof, yaw, pitch);
                onJoin.accept(p);
            } else if (ticks > JOIN_TICKS) {
                emit("error", "error", "did not join in 30 s");
                return false;
            }
            return true;
        }
        if (player.isRemoved() || !player.isOnline()) {
            return false;
        }
        // The server moved the actor (a teleport, a respawn, a correction): it confirms, as a client must before it moves again.
        if (player.getLastSentTeleportId() != player.getLastReceivedTeleportId()) {
            send(new ClientTeleportConfirmPacket(player.getLastSentTeleportId()));
            pos = player.getPosition();
            sentPos = pos;
            vy = 0;
            lastPhysics = null;
            return true;
        }
        fwd = 0;
        str = 0;
        jump = false;
        sprint = false;
        if (player.isDead()) {
            return true;
        }
        if (delay > 0) {
            delay--;
        } else if (task != null) {
            boolean done;
            try {
                done = task.tick();
            } catch (RuntimeException e) {
                emit("error", "error", task.name() + ": " + e.getMessage());
                done = true;
            }
            if (done) {
                emit("done", "do", task.name());
                task = null;
            }
        } else {
            lookAround();
        }
        double[] ang = gaze.step(0.05, aimYaw, aimPitch);
        yaw = ang[0];
        pitch = ang[1];
        move();
        return true;
    }

    /** With nothing to do a person still turns their head now and then. */
    private void lookAround() {
        if (--nextLookAround > 0) {
            return;
        }
        nextLookAround = (int) rng.range(40, 100);
        aimYaw = Human.wrap(aimYaw + Math.toRadians(rng.normal(0, 25)));
        aimPitch = Math.max(-0.5, Math.min(0.5, rng.normal(0, 0.12)));
    }

    /** One tick of walking, as a client moves itself: inputs, gravity, Minestom's collisions; then its position packet. */
    private void move() {
        float mcYaw = Human.mcYaw(yaw);
        float mcPitch = Human.mcPitch(pitch);
        double f = fwd;
        double s = str;
        double len = Math.hypot(f, s);
        if (len > 1) {
            f /= len;
            s /= len;
        }
        double speed = sneaking ? SNEAK : sprint && f > 0 ? SPRINT : WALK;
        double rad = Math.toRadians(mcYaw);
        double dx = (s * Math.cos(rad) - f * Math.sin(rad)) * speed;
        double dz = (f * Math.cos(rad) + s * Math.sin(rad)) * speed;
        if (jump && onGround) {
            vy = 0.42;
        } else if (onGround) {
            vy = -0.08;
        } else {
            vy = Math.max(-3.92, (vy - 0.08) * 0.98);
        }
        Instance instance = player.getInstance();
        Chunk chunk = instance.getChunkAt(pos);
        if (chunk == null) {
            // The world under the actor is not loaded yet: it stands still, as a client waits.
            moved = 0;
            sendInputs();
            return;
        }
        PhysicsResult r = CollisionUtils.handlePhysics(instance, chunk, player.getBoundingBox(), pos, new Vec(dx, vy, dz), lastPhysics, false);
        lastPhysics = r;
        Pos to = r.newPosition().withView(mcYaw, mcPitch);
        onGround = r.isOnGround();
        horizontalCollision = r.collisionX() || r.collisionZ();
        if (onGround || (r.collisionY() && vy > 0)) {
            vy = 0;
        }
        moved = Math.hypot(to.x() - pos.x(), to.z() - pos.z());
        pos = to;
        sendInputs();
        if (!pos.samePoint(sentPos) || pos.yaw() != sentPos.yaw() || pos.pitch() != sentPos.pitch() || ticks % 20 == 0) {
            send(new ClientPlayerPositionAndRotationPacket(pos, onGround, horizontalCollision));
            sentPos = pos;
        }
    }

    /** The keys held, as a client says them when they change: PlayerInputEvent, and the sprint as an entity action. */
    private void sendInputs() {
        ClientInputPacket in = new ClientInputPacket(fwd > 0, fwd < 0, str > 0, str < 0, jump, sneaking, sprint);
        if (in.flags() != sentInput) {
            send(in);
            sentInput = in.flags();
        }
        boolean sprinting = sprint && fwd > 0;
        if (sprinting != sentSprint) {
            send(new ClientEntityActionPacket(player.getEntityId(),
                    sprinting ? ClientEntityActionPacket.Action.START_SPRINTING : ClientEntityActionPacket.Action.STOP_SPRINTING, 0));
            sentSprint = sprinting;
        }
    }

    // ---- actions

    interface Task {
        String name();

        /** Returns true when finished. */
        boolean tick();
    }

    void start(Task t, boolean reacts) {
        task = t;
        delay = reacts ? (int) Math.round(Human.reaction(rng, prof) / 50) : 0;
    }

    void stop() {
        task = null;
        delay = 0;
    }

    void act(JsonObject req) {
        if (!joined) {
            throw new ApiException(409, name + " is still joining");
        }
        String action = str(req, "do");
        switch (action) {
            case "idle" -> start(timed("idle", seconds(req, 2), () -> { }), false);
            case "look" -> {
                look(new Human.Point(num(req, "x"), num(req, "y"), num(req, "z")));
                task = null;
            }
            case "watch" -> {
                String target = str(req, "target");
                find(target);
                start(timed("watch", seconds(req, 1.5), () -> faceEntity(find(target))), true);
            }
            case "stroll" -> start(new Stroll(num(req, "x"), num(req, "z"), bool(req, "sprint")), true);
            case "fight" -> start(new Fight(find(str(req, "target")).getUsername(), "1.9".equals(opt(req, "style", "1.8")) ? "1.9" : "1.8",
                    seconds(req, 8)), true);
            case "walk" -> {
                float f = (float) clamp(opt(req, "forward", 1), -1, 1);
                float s = (float) clamp(opt(req, "strafe", 0), -1, 1);
                boolean j = bool(req, "jump");
                boolean sp = bool(req, "sprint");
                start(timed("walk", seconds(req, 1), () -> {
                    fwd = f;
                    str = s;
                    jump = j;
                    sprint = sp;
                }), false);
            }
            case "swing" -> swing();
            case "attack" -> {
                Player target = find(str(req, "target"));
                if (distance(target) > REACH + 1.5) {
                    throw new ApiException(409, "target is out of reach");
                }
                send(new ClientAttackPacket(target.getEntityId()));
                swing();
            }
            case "equip" -> equip(str(req, "item"), (int) opt(req, "slot", 0));
            case "chat" -> {
                String text = str(req, "text");
                if (text.length() > 256) {
                    throw new ApiException(400, "text is longer than 256 characters");
                }
                send(new ClientChatMessagePacket(text, System.currentTimeMillis(), 0L, null, 0, new BitSet(), (byte) 0));
            }
            case "teleport" -> {
                Pos at = new Pos(num(req, "x"), num(req, "y"), num(req, "z"), player.getPosition().yaw(), player.getPosition().pitch());
                player.teleport(at);
            }
            case "stop" -> stop();
            case "jump" -> start(timed("jump", 0.05, () -> jump = true), false);
            case "sneak" -> {
                sneaking = !req.has("on") || bool(req, "on");
                sendInputs();
            }
            case "goto" -> start(new Goto(blockAt(req), opt(req, "within", 1.5), bool(req, "sprint")), true);
            case "select" -> select((int) num(req, "slot"));
            case "use" -> {
                send(new ClientUseItemPacket(PlayerHand.MAIN, next(), Human.mcYaw(yaw), Human.mcPitch(pitch)));
                swing();
            }
            case "use_on" -> {
                Point p = blockAt(req);
                look(center(p));
                useOn(p, face(req, p));
                swing();
            }
            case "place" -> {
                Point p = blockAt(req);
                look(center(p));
                place(p);
                swing();
            }
            case "break" -> {
                Point p = blockAt(req);
                start(new Break(p, face(req, p)), true);
            }
            case "interact" -> {
                Entity target = entity(str(req, "target"));
                Pos t = target.getPosition();
                look(new Human.Point(t.x(), t.y() + target.getBoundingBox().height() * 0.6, t.z()));
                reachable(t.add(0, target.getBoundingBox().height() / 2, 0));
                send(new ClientInteractEntityPacket(target.getEntityId(), PlayerHand.MAIN, new Vec(0, target.getBoundingBox().height() / 2, 0), sneaking));
                swing();
            }
            case "drop" -> send(new ClientPlayerActionPacket(bool(req, "stack") ? ClientPlayerActionPacket.Status.DROP_ITEM_STACK
                    : ClientPlayerActionPacket.Status.DROP_ITEM, Vec.ZERO, BlockFace.BOTTOM, 0));
            case "swap_hands" -> send(new ClientPlayerActionPacket(ClientPlayerActionPacket.Status.SWAP_ITEM_HAND, Vec.ZERO, BlockFace.BOTTOM, 0));
            case "command" -> command(str(req, "command"));
            case "click" -> click(opt(req, "type", "left"), req.has("slot") ? (int) num(req, "slot") : null, (int) opt(req, "hotbar", 0));
            case "drag" -> drag(ints(req, "slots"), opt(req, "button", "left"));
            case "close" -> send(new ClientCloseWindowPacket(windowId()));
            case "button" -> {
                if (player.getOpenInventory() == null) {
                    throw new ApiException(409, "no screen is open");
                }
                send(new ClientClickWindowButtonPacket(windowId(), (int) num(req, "id")));
            }
            case "rename" -> {
                screen(InventoryType.ANVIL, "anvil");
                // The anvil name is `text`: the clients put the actor's own name in `name`.
                String n = str(req, "text");
                if (n.length() > 50) {
                    throw new ApiException(400, "an anvil name is at most 50 characters");
                }
                send(new ClientNameItemPacket(n));
            }
            case "trade" -> {
                // Minestom has no trading of its own: the packet reaches the game's own listener for it, if it has one.
                screen(InventoryType.MERCHANT, "trading");
                send(new ClientSelectTradePacket((int) num(req, "index")));
            }
            case "sign" -> {
                List<String> lines = new ArrayList<>(strings(req, "lines"));
                while (lines.size() < 4) {
                    lines.add("");
                }
                send(new ClientUpdateSignPacket(blockAt(req), !req.has("front") || bool(req, "front"), lines));
            }
            default -> throw new ApiException(400, "unknown action '" + action + "'");
        }
    }

    /** The open screen is of this type, or the action has nothing to act on. */
    private void screen(InventoryType type, String what) {
        if (!(player.getOpenInventory() instanceof Inventory open) || open.getInventoryType() != type) {
            throw new ApiException(409, "no " + what + " screen is open");
        }
    }

    private void swing() {
        send(new ClientAnimationPacket(PlayerHand.MAIN));
    }

    private void select(int slot) {
        if (slot < 0 || slot > 8) {
            throw new ApiException(400, "slot is 0-8");
        }
        send(new ClientHeldItemChangePacket((short) slot));
    }

    private void command(String line) {
        String c = line.startsWith("/") ? line.substring(1) : line;
        if (c.isBlank() || c.length() > 256) {
            throw new ApiException(400, "a command is 1-256 characters");
        }
        send(new ClientCommandChatPacket(c));
    }

    private void equip(String item, int slot) {
        Material m = Material.fromKey(item.contains(":") ? item : "minecraft:" + item.toLowerCase(Locale.ROOT));
        if (m == null || m == Material.AIR) {
            throw new ApiException(400, "unknown item '" + item + "'");
        }
        if (slot < 0 || slot > 8) {
            throw new ApiException(400, "slot is 0-8");
        }
        ItemStack stack = ItemStack.of(m);
        player.getInventory().setItemStack(slot, stack.withAmount(stack.maxStackSize()));
        select(slot);
    }

    private Task timed(String name, double seconds, Runnable each) {
        long end = ticks + Math.max(1, Math.round(seconds * 20));
        return new Task() {
            public String name() {
                return name;
            }

            public boolean tick() {
                each.run();
                return ticks >= end;
            }
        };
    }

    // ---- aiming

    private Human.Point eye() {
        return new Human.Point(pos.x(), pos.y() + player.getEyeHeight(), pos.z());
    }

    private void look(Human.Point p) {
        double[] a = Human.angles(eye(), p);
        aimYaw = a[0];
        aimPitch = a[1];
    }

    private void faceEntity(Player t) {
        double d = distance(t);
        Pos at = t.getPosition();
        look(Human.aimPoint(rng, prof, new Human.Point(at.x(), at.y(), at.z()), d));
    }

    private double distance(Entity t) {
        return pos.distance(t.getPosition());
    }

    private Player find(String targetName) {
        Player p = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(targetName);
        if (p == null || p.getInstance() != player.getInstance()) {
            throw new ApiException(404, "no player '" + targetName + "' is online here");
        }
        return p;
    }

    // ---- behaviours

    private final class Stroll implements Task {
        private final double x;
        private final double z;
        private final boolean run;
        private final long timeout = ticks + 20 * 40;

        Stroll(double x, double z, boolean run) {
            this.x = x;
            this.z = z;
            this.run = run;
        }

        public String name() {
            return "stroll";
        }

        public boolean tick() {
            double dx = x - pos.x();
            double dz = z - pos.z();
            if (Math.hypot(dx, dz) < 0.7) {
                return true;
            }
            if (ticks >= timeout) {
                emit("error", "error", "stroll: did not arrive in 40 s");
                return true;
            }
            aimYaw = Math.atan2(-dx, -dz);
            aimPitch = 0;
            fwd = 1;
            sprint = run;
            jump = horizontalCollision && onGround;
            return false;
        }
    }

    private final class Fight implements Task {
        private final String targetName;
        private final String style;
        private final long end;
        private long nextSwing;
        private long strafeUntil;
        private float strafeDir = 1;
        private long wtapUntil;
        private long retreatUntil;
        private int sinceAim;

        Fight(String targetName, String style, double seconds) {
            this.targetName = targetName;
            this.style = style;
            this.end = ticks + Math.round(seconds * 20);
            this.nextSwing = ticks;
        }

        public String name() {
            return "fight";
        }

        public boolean tick() {
            Player target = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(targetName);
            if (target == null || target.isDead() || target.getInstance() != player.getInstance() || ticks >= end) {
                return true;
            }
            double dist = distance(target);
            if (sinceAim++ % 4 == 0) {
                faceEntity(target);
            }
            if (prof.retreatAt() > 0 && player.getHealth() <= prof.retreatAt() && ticks > retreatUntil && rng.chance(0.02)) {
                retreatUntil = ticks + 30;
            }
            if (ticks < retreatUntil) {
                fwd = -1;
                return false;
            }
            if (dist > 3.4) {
                fwd = 1;
                sprint = true;
            } else if (dist > 2.2) {
                fwd = ticks < wtapUntil ? 0 : 1;
                sprint = ticks >= wtapUntil;
            } else if (dist < 1.4) {
                fwd = -0.6f;
            }
            if (dist < 4.5) {
                if (ticks >= strafeUntil) {
                    strafeUntil = ticks + (long) rng.range(10, 28);
                    strafeDir = rng.chance(prof.strafe()) ? (rng.chance(0.5) ? 1 : -1) : 0;
                }
                str = strafeDir;
            }
            if (ticks >= nextSwing && dist <= REACH && facing(target)) {
                swingOnce(target);
            }
            return false;
        }

        private boolean facing(Player target) {
            double dx = target.getPosition().x() - pos.x();
            double dz = target.getPosition().z() - pos.z();
            double want = Math.atan2(-dx, -dz);
            return Math.abs(Human.wrap(want - yaw)) < Math.toRadians(28);
        }

        private void swingOnce(Player target) {
            if (onGround && rng.chance(prof.crit() * 0.2)) {
                jump = true;
            }
            if (!rng.chance(prof.miss())) {
                send(new ClientAttackPacket(target.getEntityId()));
                if (rng.chance(prof.wtap())) {
                    wtapUntil = ticks + 3;
                }
            }
            swing();
            nextSwing = ticks + Math.max(1, Math.round(Human.nextSwing(rng, prof, style, 625) / 50));
        }
    }

    // ---- blocks and entities

    private static Point blockAt(JsonObject req) {
        return new Vec(Math.floor(num(req, "x")), Math.floor(num(req, "y")), Math.floor(num(req, "z")));
    }

    private static Human.Point center(Point p) {
        return new Human.Point(p.blockX() + 0.5, p.blockY() + 0.5, p.blockZ() + 0.5);
    }

    private BlockFace face(JsonObject req, Point p) {
        if (!req.has("face")) {
            return faceTowardsMe(p);
        }
        return switch (str(req, "face").toLowerCase(Locale.ROOT)) {
            case "up" -> BlockFace.TOP;
            case "down" -> BlockFace.BOTTOM;
            case "north" -> BlockFace.NORTH;
            case "south" -> BlockFace.SOUTH;
            case "west" -> BlockFace.WEST;
            case "east" -> BlockFace.EAST;
            default -> throw new ApiException(400, "face is up, down, north, south, east or west");
        };
    }

    /** The face of the block at `p` that looks at the actor's eyes. */
    private BlockFace faceTowardsMe(Point p) {
        Human.Point e = eye();
        double dx = e.x() - (p.blockX() + 0.5);
        double dy = e.y() - (p.blockY() + 0.5);
        double dz = e.z() - (p.blockZ() + 0.5);
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);
        if (ay >= ax && ay >= az) {
            return dy > 0 ? BlockFace.TOP : BlockFace.BOTTOM;
        }
        if (ax >= az) {
            return dx > 0 ? BlockFace.EAST : BlockFace.WEST;
        }
        return dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    private void reachable(Point at) {
        Human.Point e = eye();
        double d = Math.sqrt(Math.pow(e.x() - at.x(), 2) + Math.pow(e.y() - at.y(), 2) + Math.pow(e.z() - at.z(), 2));
        if (d > HAND_REACH + 1) {
            throw new ApiException(409, "out of reach (" + Math.round(d * 10) / 10.0 + " blocks)");
        }
    }

    /** Right click on a block: open a chest, press a button; the held item acts on it (PlayerBlockInteractEvent). */
    private void useOn(Point p, BlockFace face) {
        reachable(p.add(0.5, 0.5, 0.5));
        Vec cursor = cursorOn(face);
        send(new ClientPlayerBlockPlacementPacket(PlayerHand.MAIN, p, face, (float) cursor.x(), (float) cursor.y(), (float) cursor.z(), false, false, next()));
    }

    /** Where on a block's face a click lands, inside the block (0-1). */
    private static Vec cursorOn(BlockFace face) {
        var d = face.toDirection();
        return new Vec(0.5 + d.normalX() * 0.5, 0.5 + d.normalY() * 0.5, 0.5 + d.normalZ() * 0.5);
    }

    /** Places the held block at `target`, against a solid neighbour, as a click on that neighbour's face does. */
    private void place(Point target) {
        Instance instance = player.getInstance();
        if (!instance.getBlock(target).replaceable()) {
            throw new ApiException(409, "the block at " + target.blockX() + " " + target.blockY() + " " + target.blockZ() + " is taken");
        }
        BlockFace best = null;
        double bestDistance = Double.MAX_VALUE;
        Human.Point e = eye();
        for (BlockFace f : BlockFace.values()) {
            var d = f.toDirection();
            Point against = target.add(d.normalX(), d.normalY(), d.normalZ());
            Block b = instance.getBlock(against);
            if (b.air() || b.replaceable()) {
                continue;
            }
            double dist = Math.pow(e.x() - against.blockX() - 0.5, 2) + Math.pow(e.y() - against.blockY() - 0.5, 2) + Math.pow(e.z() - against.blockZ() - 0.5, 2);
            if (dist < bestDistance) {
                bestDistance = dist;
                best = f;
            }
        }
        if (best == null) {
            throw new ApiException(409, "nothing to place against at " + target.blockX() + " " + target.blockY() + " " + target.blockZ());
        }
        var d = best.toDirection();
        Point against = target.add(d.normalX(), d.normalY(), d.normalZ());
        BlockFace face = best.getOppositeFace();
        reachable(target.add(0.5, 0.5, 0.5));
        Vec cursor = cursorOn(face);
        send(new ClientPlayerBlockPlacementPacket(PlayerHand.MAIN, against, face, (float) cursor.x(), (float) cursor.y(), (float) cursor.z(), false, false,
                next()));
    }

    /** A player by name, or the nearest entity of a type within reach (villager, minecraft:zombie). */
    private Entity entity(String target) {
        Player p = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(target);
        if (p != null && p.getInstance() == player.getInstance()) {
            return p;
        }
        String type = target.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity e : player.getInstance().getNearbyEntities(pos, HAND_REACH + 2)) {
            if (e == player || !e.getEntityType().name().replace("minecraft:", "").equals(type)) {
                continue;
            }
            double d = pos.distanceSquared(e.getPosition());
            if (d < bestDistance) {
                bestDistance = d;
                best = e;
            }
        }
        if (best == null) {
            throw new ApiException(404, "no player or " + type + " within reach");
        }
        return best;
    }

    /** Digs a block as a person holds the button: started, held for the server's own break time, finished. */
    private final class Break implements Task {
        private final Point at;
        private final BlockFace face;
        private int left = -1;
        private final long timeout = ticks + 20 * 60;

        Break(Point at, BlockFace face) {
            this.at = at;
            this.face = face;
        }

        public String name() {
            return "break";
        }

        public boolean tick() {
            look(center(at));
            Block block = player.getInstance().getBlock(at);
            if (block.air()) {
                return true;
            }
            if (left < 0) {
                reachable(at.add(0.5, 0.5, 0.5));
                int breakTicks = BlockBreakCalculation.breakTicks(block, player);
                if (breakTicks == BlockBreakCalculation.UNBREAKABLE) {
                    emit("error", "error", "break: this block cannot be broken");
                    return true;
                }
                send(new ClientPlayerActionPacket(ClientPlayerActionPacket.Status.STARTED_DIGGING, at, face, next()));
                if (breakTicks == 0) {
                    // An instant break: a client sends the start only.
                    return true;
                }
                left = breakTicks;
            }
            if (ticks >= timeout) {
                send(new ClientPlayerActionPacket(ClientPlayerActionPacket.Status.CANCELLED_DIGGING, at, face, next()));
                emit("error", "error", "break: not done in 60 s");
                return true;
            }
            if (ticks % 4 == 0) {
                swing();
            }
            if (--left <= 0) {
                send(new ClientPlayerActionPacket(ClientPlayerActionPacket.Status.FINISHED_DIGGING, at, face, next()));
                return true;
            }
            return false;
        }
    }

    /** What each block is, for the core's pathfinder. */
    private Pathfinder.Blocks blocks() {
        Instance instance = player.getInstance();
        int minY = instance.getCachedDimensionType().minY();
        int maxY = minY + instance.getCachedDimensionType().height();
        return new Pathfinder.Blocks() {
            public boolean loaded(int x, int y, int z) {
                return y >= minY && y < maxY && instance.isChunkLoaded(x >> 4, z >> 4);
            }

            public boolean passable(int x, int y, int z) {
                if (y < minY || y >= maxY) {
                    return y >= maxY;
                }
                Block b = instance.getBlock(x, y, z);
                return !b.blocksMotion() && !dangerous(b);
            }

            public boolean ground(int x, int y, int z) {
                if (y < minY || y >= maxY) {
                    return false;
                }
                Block b = instance.getBlock(x, y, z);
                return (b.blocksMotion() || b.liquid()) && !dangerous(b);
            }
        };
    }

    private static boolean dangerous(Block b) {
        String id = b.key().value();
        return id.contains("lava") || id.contains("fire") || id.contains("cactus") || id.contains("magma") || id.contains("sweet_berry")
                || id.contains("powder_snow") || id.contains("campfire");
    }

    /** Walks a found path: jumps one-block steps, plans again when pushed off it. */
    private final class Goto implements Task {
        private final Point goal;
        private final double within;
        private final boolean run;
        private final long timeout;
        private List<Pathfinder.Pos> path = List.of();
        private int at;
        private int stuck;
        private int replans;

        Goto(Point goal, double within, boolean run) {
            this.goal = goal;
            this.within = Math.max(0.5, within);
            this.run = run;
            this.timeout = ticks + 20 * 90;
        }

        public String name() {
            return "goto";
        }

        private Pathfinder.Pos feet() {
            return new Pathfinder.Pos(pos.blockX(), pos.blockY(), pos.blockZ());
        }

        private boolean plan() {
            path = Pathfinder.find(blocks(), feet(), new Pathfinder.Pos(goal.blockX(), goal.blockY(), goal.blockZ()), within);
            at = 0;
            return !path.isEmpty();
        }

        public boolean tick() {
            if (feet().distance(new Pathfinder.Pos(goal.blockX(), goal.blockY(), goal.blockZ())) <= within) {
                return true;
            }
            if (ticks >= timeout) {
                emit("error", "error", "goto: did not arrive in 90 s");
                return true;
            }
            if (path.isEmpty() || stuck > 40) {
                stuck = 0;
                if (replans++ > 5 || !plan()) {
                    emit("error", "error", "goto: no path to " + goal.blockX() + " " + goal.blockY() + " " + goal.blockZ());
                    return true;
                }
            }
            while (at < path.size() - 1 && horizontal(path.get(at)) < 0.45) {
                at++;
            }
            Pathfinder.Pos next = path.get(Math.min(at, path.size() - 1));
            double dx = next.x() + 0.5 - pos.x();
            double dz = next.z() + 0.5 - pos.z();
            aimYaw = Math.atan2(-dx, -dz);
            aimPitch = 0;
            fwd = 1;
            sprint = run;
            jump = (next.y() > pos.blockY() || horizontalCollision) && onGround;
            stuck = moved < 0.02 ? stuck + 1 : 0;
            return false;
        }

        private double horizontal(Pathfinder.Pos p) {
            return Math.hypot(p.x() + 0.5 - pos.x(), p.z() + 0.5 - pos.z());
        }
    }

    // ---- the open screen

    private int windowId() {
        AbstractInventory open = player.getOpenInventory();
        return open == null ? 0 : open.getWindowId();
    }

    /** The slots of the open screen: the container's, then the player's own 36; or the player's screen (46). */
    int screenSlots() {
        AbstractInventory open = player.getOpenInventory();
        return open == null ? 46 : open.getSize() + 36;
    }

    private void clickRaw(int slot, int button, ClientClickWindowPacket.ClickType type) {
        if (slot != -999 && (slot < 0 || slot >= screenSlots())) {
            throw new ApiException(400, "slot " + slot + " is not in the open screen (0-" + (screenSlots() - 1) + ")");
        }
        send(new ClientClickWindowPacket(windowId(), 0, (short) slot, (byte) button, type, Map.of(),
                ItemStack.Hash.of(ItemStack.AIR, MinecraftServer.process())));
    }

    private void click(String type, Integer slot, int hotbar) {
        String t = type.toLowerCase(Locale.ROOT);
        if (t.equals("outside_left") || t.equals("outside_right")) {
            clickRaw(-999, t.equals("outside_left") ? 0 : 1, ClientClickWindowPacket.ClickType.PICKUP);
            return;
        }
        if (slot == null) {
            throw new ApiException(400, "slot is required");
        }
        if (t.equals("double")) {
            clickRaw(slot, 0, ClientClickWindowPacket.ClickType.PICKUP);
            clickRaw(slot, 0, ClientClickWindowPacket.ClickType.PICKUP_ALL);
            return;
        }
        switch (t) {
            case "left" -> clickRaw(slot, 0, ClientClickWindowPacket.ClickType.PICKUP);
            case "right" -> clickRaw(slot, 1, ClientClickWindowPacket.ClickType.PICKUP);
            case "shift", "shift_left" -> clickRaw(slot, 0, ClientClickWindowPacket.ClickType.QUICK_MOVE);
            case "shift_right" -> clickRaw(slot, 1, ClientClickWindowPacket.ClickType.QUICK_MOVE);
            case "number" -> {
                if (hotbar < 1 || hotbar > 9) {
                    throw new ApiException(400, "a number click needs hotbar 1-9");
                }
                clickRaw(slot, hotbar - 1, ClientClickWindowPacket.ClickType.SWAP);
            }
            case "offhand" -> clickRaw(slot, 40, ClientClickWindowPacket.ClickType.SWAP);
            case "middle" -> clickRaw(slot, 2, ClientClickWindowPacket.ClickType.CLONE);
            case "drop" -> clickRaw(slot, 0, ClientClickWindowPacket.ClickType.THROW);
            case "drop_stack" -> clickRaw(slot, 1, ClientClickWindowPacket.ClickType.THROW);
            case "pickup_all" -> clickRaw(slot, 0, ClientClickWindowPacket.ClickType.PICKUP_ALL);
            default -> throw new ApiException(400, "unknown click '" + type + "' (left, right, shift, shift_right, number, offhand, middle, drop, "
                    + "drop_stack, pickup_all, double, outside_left, outside_right)");
        }
    }

    /** A drag of the carried stack over slots: start, one add per slot, end (QUICK_CRAFT). */
    private void drag(List<Integer> slots, String button) {
        int type = switch (button.toLowerCase(Locale.ROOT)) {
            case "left" -> 0;
            case "right" -> 1;
            case "middle" -> 2;
            default -> throw new ApiException(400, "button is left, right or middle");
        };
        if (slots.isEmpty()) {
            throw new ApiException(400, "a drag needs slots");
        }
        if (player.getInventory().getCursorItem().isAir()) {
            throw new ApiException(409, "the cursor holds nothing to drag");
        }
        clickRaw(-999, type << 2, ClientClickWindowPacket.ClickType.QUICK_CRAFT);
        for (int s : slots) {
            clickRaw(s, (type << 2) | 1, ClientClickWindowPacket.ClickType.QUICK_CRAFT);
        }
        clickRaw(-999, (type << 2) | 2, ClientClickWindowPacket.ClickType.QUICK_CRAFT);
    }

    // ---- request parsing

    private static List<Integer> ints(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) {
            throw new ApiException(400, key + " must be a list of numbers");
        }
        List<Integer> out = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray(key)) {
            out.add(e.getAsInt());
        }
        return out;
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray()) {
            JsonArray a = o.getAsJsonArray(key);
            for (int i = 0; i < Math.min(4, a.size()); i++) {
                String line = a.get(i).getAsString();
                if (line.length() > 90) {
                    throw new ApiException(400, "a sign line is at most 90 characters");
                }
                out.add(line);
            }
        }
        return out;
    }

    private static String str(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) {
            throw new ApiException(400, key + " is required");
        }
        return o.get(key).getAsString();
    }

    private static double num(JsonObject o, String key) {
        try {
            return o.get(key).getAsDouble();
        } catch (RuntimeException e) {
            throw new ApiException(400, key + " must be a number");
        }
    }

    private static double opt(JsonObject o, String key, double fallback) {
        return o.has(key) ? num(o, key) : fallback;
    }

    private static String opt(JsonObject o, String key, String fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
    }

    private static boolean bool(JsonObject o, String key) {
        if (!o.has(key)) {
            return false;
        }
        var e = o.get(key).getAsJsonPrimitive();
        return e.isBoolean() ? e.getAsBoolean() : "true".equals(e.getAsString()) || "evet".equals(e.getAsString());
    }

    private static double seconds(JsonObject o, double fallback) {
        double s = opt(o, "seconds", fallback);
        if (!(s > 0 && s <= 600)) {
            throw new ApiException(400, "seconds must be 0-600");
        }
        return s;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
