package net.poena.actors.paper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.poena.actors.core.ApiException;
import net.poena.actors.core.Human;
import net.poena.actors.core.Human.Point;
import net.poena.actors.core.Rng;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** One fake player with its human-like brain. Everything here runs on the thread that owns the player (the main thread on Paper). */
final class Actor {
    private static final double REACH = 3.0;

    final String name;
    final UUID uuid;
    final ActorPlayer player;
    final ActorListener listener;
    final FakeNet net;
    final String profileName;
    final Hands hands;
    int hits;

    private final Rng rng;
    private final Human.Profile prof;
    private final Human.Gaze gaze;
    private final BiConsumer<String, JsonObject> events;
    private Task task;
    private int delay;
    private long ticks;
    private double aimYaw;
    private double aimPitch;
    private int nextLookAround;
    private boolean retired;

    private float fwd;
    private float str;
    private boolean jump;
    private boolean sprint;

    Actor(String name, UUID uuid, ActorPlayer player, ActorListener listener, FakeNet net, String profileName, Rng rng,
          BiConsumer<String, JsonObject> events) {
        this.name = name;
        this.uuid = uuid;
        this.player = player;
        this.listener = listener;
        this.net = net;
        this.profileName = profileName;
        this.hands = new Hands(player, listener);
        this.rng = rng;
        this.prof = Human.profile(profileName);
        this.events = events;
        this.aimYaw = Human.fromMcYaw(player.getYRot());
        this.aimPitch = Human.fromMcPitch(player.getXRot());
        this.gaze = new Human.Gaze(rng, prof, aimYaw, aimPitch);
        this.nextLookAround = 40;
    }

    Player bukkit() {
        return player.getBukkitEntity();
    }

    boolean retired() {
        return retired;
    }

    void retire() {
        retired = true;
        task = null;
    }

    String taskName() {
        return task == null ? "idle" : task.name();
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

    // ---- per tick

    void tick() {
        if (retired) {
            return;
        }
        // A client says it has loaded the world (and again after a respawn); until then the server ignores its actions.
        if (!listener.hasClientLoaded()) {
            listener.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
        }
        listener.confirmTeleport();
        ticks++;
        fwd = 0;
        str = 0;
        jump = false;
        sprint = false;
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
        float yaw = Human.mcYaw(ang[0]);
        player.setYRot(yaw);
        player.setXRot(Human.mcPitch(ang[1]));
        player.setYHeadRot(yaw);
        player.drive(fwd, str, jump, sprint);
        player.doTick();
        if (ticks % 5 == 0) {
            player.level().getChunkSource().move(player);
        }
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
        String action = str(req, "do");
        switch (action) {
            case "idle" -> start(timed("idle", seconds(req, 2), () -> { }), false);
            case "look" -> {
                look(new Point(num(req, "x"), num(req, "y"), num(req, "z")));
                task = null;
            }
            case "watch" -> start(timed("watch", seconds(req, 1.5), () -> faceEntity(find(str(req, "target")))), true);
            case "stroll" -> start(new Stroll(num(req, "x"), num(req, "z"), bool(req, "sprint")), true);
            case "fight" -> start(new Fight(find(str(req, "target")).getBukkitEntity().getName(), "1.9".equals(opt(req, "style", "1.8")) ? "1.9" : "1.8",
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
            case "swing" -> player.swing(InteractionHand.MAIN_HAND);
            case "attack" -> {
                ServerPlayer target = find(str(req, "target"));
                if (distance(target) > REACH + 1.5) {
                    throw new ApiException(409, "target is out of reach");
                }
                player.attack(target);
                player.swing(InteractionHand.MAIN_HAND);
            }
            case "equip" -> equip(str(req, "item"), (int) opt(req, "slot", 0));
            case "chat" -> {
                String text = str(req, "text");
                if (text.length() > 256) {
                    throw new ApiException(400, "text is longer than 256 characters");
                }
                bukkit().chat(text);
            }
            case "teleport" -> {
                Location at = new Location(bukkit().getWorld(), num(req, "x"), num(req, "y"), num(req, "z"), bukkit().getYaw(), bukkit().getPitch());
                bukkit().teleportAsync(at);
            }
            case "stop" -> stop();
            case "jump" -> start(timed("jump", 0.05, () -> jump = true), false);
            case "sneak" -> {
                boolean on = !req.has("on") || bool(req, "on");
                player.setShiftKeyDown(on);
                bukkit().setSneaking(on);
            }
            case "goto" -> start(new Goto(blockAt(req), opt(req, "within", 1.5), bool(req, "sprint")), true);
            case "select" -> hands.select((int) num(req, "slot"));
            case "use" -> {
                hands.use();
                player.swing(InteractionHand.MAIN_HAND);
            }
            case "use_on" -> {
                BlockPos pos = blockAt(req);
                look(center(pos));
                hands.useOn(pos, face(req, pos));
                player.swing(InteractionHand.MAIN_HAND);
            }
            case "place" -> {
                BlockPos pos = blockAt(req);
                look(center(pos));
                hands.place(pos);
                player.swing(InteractionHand.MAIN_HAND);
            }
            case "break" -> {
                BlockPos pos = blockAt(req);
                start(new Break(pos, face(req, pos)), true);
            }
            case "interact" -> {
                Entity target = entity(str(req, "target"));
                look(new Point(target.getX(), target.getY() + target.getBbHeight() * 0.6, target.getZ()));
                hands.interact(target);
                player.swing(InteractionHand.MAIN_HAND);
            }
            case "drop" -> hands.drop(bool(req, "stack"));
            case "swap_hands" -> hands.swapHands();
            case "command" -> hands.command(str(req, "command"));
            case "click" -> hands.click(opt(req, "type", "left"), req.has("slot") ? (int) num(req, "slot") : null, (int) opt(req, "hotbar", 0));
            case "drag" -> hands.drag(ints(req, "slots"), opt(req, "button", "left"));
            case "close" -> hands.close();
            case "button" -> hands.button((int) num(req, "id"));
            case "rename" -> hands.rename(str(req, "name"));
            case "trade" -> hands.trade((int) num(req, "index"));
            case "sign" -> {
                BlockPos pos = blockAt(req);
                hands.sign(pos, !req.has("front") || bool(req, "front"), strings(req, "lines"));
            }
            default -> throw new ApiException(400, "unknown action '" + action + "'");
        }
    }

    private void equip(String item, int slot) {
        Material m = Material.matchMaterial(item);
        if (m == null || !m.isItem() || m.isAir()) {
            throw new ApiException(400, "unknown item '" + item + "'");
        }
        if (slot < 0 || slot > 8) {
            throw new ApiException(400, "slot is 0-8");
        }
        bukkit().getInventory().setItem(slot, new ItemStack(m, m.getMaxStackSize()));
        bukkit().getInventory().setHeldItemSlot(slot);
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

    private Point eye() {
        return new Point(player.getX(), player.getEyeY(), player.getZ());
    }

    private void look(Point p) {
        double[] a = Human.angles(eye(), p);
        aimYaw = a[0];
        aimPitch = a[1];
    }

    private void faceEntity(ServerPlayer t) {
        double d = distance(t);
        Point chest = new Point(t.getX(), t.getY(), t.getZ());
        look(Human.aimPoint(rng, prof, chest, d));
    }

    private double distance(ServerPlayer t) {
        return Math.sqrt(player.distanceToSqr(t));
    }

    private ServerPlayer find(String targetName) {
        Player p = Bukkit.getPlayerExact(targetName);
        if (p == null) {
            throw new ApiException(404, "no player '" + targetName + "' is online");
        }
        return ((CraftPlayer) p).getHandle();
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
            double dx = x - player.getX();
            double dz = z - player.getZ();
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
            jump = player.horizontalCollision && player.onGround();
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
            Player other = Bukkit.getPlayerExact(targetName);
            if (other == null || other.isDead() || ticks >= end || !bukkit().isValid()) {
                return true;
            }
            ServerPlayer target = ((CraftPlayer) other).getHandle();
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

        private boolean facing(ServerPlayer target) {
            double dx = target.getX() - player.getX();
            double dz = target.getZ() - player.getZ();
            double want = Math.atan2(-dx, -dz);
            return Math.abs(Human.wrap(want - Human.fromMcYaw(player.getYRot()))) < Math.toRadians(28);
        }

        private void swingOnce(ServerPlayer target) {
            if ("1.9".equals(style) && player.getAttackStrengthScale(0.5f) < 0.95f) {
                return;
            }
            if (player.onGround() && rng.chance(prof.crit() * 0.2)) {
                jump = true;
            }
            boolean miss = rng.chance(prof.miss());
            if (!miss) {
                player.attack(target);
                if (rng.chance(prof.wtap())) {
                    wtapUntil = ticks + 3;
                }
            }
            player.swing(InteractionHand.MAIN_HAND);
            nextSwing = ticks + Math.max(1, Math.round(Human.nextSwing(rng, prof, style, 625) / 50));
        }
    }

    // ---- blocks and entities

    private static BlockPos blockAt(JsonObject req) {
        return BlockPos.containing(num(req, "x"), num(req, "y"), num(req, "z"));
    }

    private static Point center(BlockPos p) {
        return new Point(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
    }

    private Direction face(JsonObject req, BlockPos pos) {
        if (!req.has("face")) {
            return hands.faceTowardsMe(pos);
        }
        Direction d = Direction.byName(str(req, "face").toLowerCase(java.util.Locale.ROOT));
        if (d == null) {
            throw new ApiException(400, "face is up, down, north, south, east or west");
        }
        return d;
    }

    /** A player by name, or the nearest living entity of a type within reach (villager, minecraft:zombie). */
    private Entity entity(String target) {
        Player p = Bukkit.getPlayerExact(target);
        if (p != null) {
            return ((CraftPlayer) p).getHandle();
        }
        String type = target.toLowerCase(java.util.Locale.ROOT).replace("minecraft:", "");
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity e : player.level().getEntities(player, player.getBoundingBox().inflate(Hands.REACH + 2))) {
            String id = EntityType.getKey(e.getType()).getPath();
            if (!id.equals(type)) {
                continue;
            }
            double d = player.distanceToSqr(e);
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

    /** Digs a block as a person holds the button: the server counts the progress, and the break event fires when it is done. */
    private final class Break implements Task {
        private final BlockPos pos;
        private final Direction face;
        private float progress;
        private boolean started;
        private final long timeout = ticks + 20 * 60;

        Break(BlockPos pos, Direction face) {
            this.pos = pos;
            this.face = face;
        }

        public String name() {
            return "break";
        }

        public boolean tick() {
            look(center(pos));
            if (player.level().getBlockState(pos).isAir()) {
                return true;
            }
            if (!started) {
                started = true;
                hands.startBreak(pos, face);
                if (player.level().getBlockState(pos).isAir()) {
                    return true;
                }
            }
            if (ticks >= timeout) {
                hands.abortBreak(pos, face);
                emit("error", "error", "break: not done in 60 s");
                return true;
            }
            float step = hands.digPerTick(pos);
            if (step <= 0) {
                hands.abortBreak(pos, face);
                emit("error", "error", "break: this block cannot be broken");
                return true;
            }
            progress += step;
            if (ticks % 4 == 0) {
                player.swing(InteractionHand.MAIN_HAND);
            }
            if (progress >= 1) {
                hands.finishBreak(pos, face);
                return true;
            }
            return false;
        }
    }

    /** Walks a found path: jumps one-block steps, recomputes when pushed off it. */
    private final class Goto implements Task {
        private final BlockPos goal;
        private final double within;
        private final boolean run;
        private final long timeout;
        private List<BlockPos> path = List.of();
        private int at;
        private int stuck;
        private int replans;

        Goto(BlockPos goal, double within, boolean run) {
            this.goal = goal;
            this.within = Math.max(0.5, within);
            this.run = run;
            this.timeout = ticks + 20 * 90;
        }

        public String name() {
            return "goto";
        }

        private boolean plan() {
            path = Path.find(player.level(), player.blockPosition(), goal, within);
            at = 0;
            return !path.isEmpty();
        }

        public boolean tick() {
            if (Math.sqrt(player.blockPosition().distSqr(goal)) <= within) {
                return true;
            }
            if (ticks >= timeout) {
                emit("error", "error", "goto: did not arrive in 90 s");
                return true;
            }
            if (path.isEmpty() || stuck > 40) {
                stuck = 0;
                if (replans++ > 5 || !plan()) {
                    emit("error", "error", "goto: no path to " + goal.toShortString());
                    return true;
                }
            }
            while (at < path.size() - 1 && horizontal(path.get(at)) < 0.45) {
                at++;
            }
            BlockPos next = path.get(Math.min(at, path.size() - 1));
            double dx = next.getX() + 0.5 - player.getX();
            double dz = next.getZ() + 0.5 - player.getZ();
            aimYaw = Math.atan2(-dx, -dz);
            aimPitch = 0;
            fwd = 1;
            sprint = run;
            jump = (next.getY() > player.getBlockY() || player.horizontalCollision) && player.onGround();
            stuck = player.getDeltaMovement().horizontalDistanceSqr() < 0.0004 ? stuck + 1 : 0;
            return false;
        }

        private double horizontal(BlockPos p) {
            return Math.hypot(p.getX() + 0.5 - player.getX(), p.getZ() + 0.5 - player.getZ());
        }
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
