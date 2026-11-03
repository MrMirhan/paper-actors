package net.poena.actors.core;

import java.util.Map;

/** The "plays like a person" model of agent/js/human.js: profiles, reaction delay, swing timing, spring head turns. Angles are radians, mineflayer convention. */
public final class Human {
    private Human() {
    }

    public record Profile(double cpsLo, double cpsHi, double reactionMean, double reactionSd, double aim, double miss, double turn,
                          double strafe, double wtap, double crit, double retreatAt) {
    }

    public static final Map<String, Profile> PROFILES = Map.of(
            "rookie", new Profile(6, 9, 290, 60, 6, 0.25, 7, 0.35, 0.1, 0.05, 0),
            "mid", new Profile(8, 11, 220, 45, 3.5, 0.12, 10, 0.7, 0.45, 0.25, 6),
            "tryhard", new Profile(10, 13, 170, 30, 2, 0.05, 14, 0.9, 0.8, 0.55, 8));

    public static Profile profile(String name) {
        return PROFILES.getOrDefault(name == null ? "mid" : name, PROFILES.get("mid"));
    }

    public static double wrap(double angle) {
        double a = angle % (2 * Math.PI);
        if (a > Math.PI) {
            a -= 2 * Math.PI;
        }
        if (a < -Math.PI) {
            a += 2 * Math.PI;
        }
        return a;
    }

    /** Milliseconds before acting on something new. */
    public static double reaction(Rng r, Profile p) {
        return Math.max(110, r.normal(p.reactionMean(), p.reactionSd()));
    }

    /** Milliseconds until the next swing. 1.8: spam at a drifting CPS. 1.9+: wait out the cooldown, then a human-sized delay. */
    public static double nextSwing(Rng r, Profile p, String style, double cooldownMs) {
        if ("1.8".equals(style)) {
            double cps = r.range(p.cpsLo(), p.cpsHi());
            return Math.max(55, r.normal(1000 / cps, 1000 / cps / 6));
        }
        return cooldownMs + Math.max(0, r.normal(p.reactionMean() * 0.35, p.reactionSd() * 0.5));
    }

    public record Point(double x, double y, double z) {
    }

    /** Crosshair target on another player: chest height plus a wander that grows with distance and the profile's aim. */
    public static Point aimPoint(Rng r, Profile p, Point target, double distance) {
        double spread = Math.tan(Math.toRadians(p.aim())) * Math.max(1, distance);
        return new Point(target.x() + r.normal(0, spread / 2), target.y() + 1.2 + r.normal(0, spread / 3), target.z() + r.normal(0, spread / 2));
    }

    /** {yaw, pitch} to look from one point at another (mineflayer convention: yaw 0 faces north, pitch up is positive). */
    public static double[] angles(Point from, Point to) {
        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        double dz = to.z() - from.z();
        return new double[] {Math.atan2(-dx, -dz), Math.atan2(dy, Math.hypot(dx, dz))};
    }

    /** Mineflayer yaw/pitch in radians to Minecraft yaw/pitch in degrees (yaw 0 faces south, pitch down is positive).
     *  The two yaws turn opposite ways, so this is a mirror (180 - a), not an offset: mineflayer's own toNotchianYaw. */
    public static float mcYaw(double yaw) {
        return 180f - (float) Math.toDegrees(yaw);
    }

    public static float mcPitch(double pitch) {
        return (float) -Math.toDegrees(pitch);
    }

    public static double fromMcYaw(float yawDeg) {
        return wrap(Math.toRadians(180f - yawDeg));
    }

    public static double fromMcPitch(float pitchDeg) {
        return -Math.toRadians(pitchDeg);
    }

    /** The head as a slightly under-damped spring per axis: lands on the target with a small overshoot and a faint tremor, never an instant snap. */
    public static final class Gaze {
        private final Rng r;
        private final Profile prof;
        private double yaw;
        private double pitch;
        private double vy;
        private double vp;
        private double t;
        private Double ty;
        private double tp;

        public Gaze(Rng r, Profile prof, double yaw, double pitch) {
            this.r = r;
            this.prof = prof;
            this.yaw = yaw;
            this.pitch = pitch;
            this.t = r.range(0, 100);
        }

        /** Advance dt seconds toward the target; returns {yaw, pitch} including tremor. */
        public double[] step(double dt, double targetYaw, double targetPitch) {
            double w = prof.turn();
            double z = 0.7;
            int n = 8;
            double h = dt / n;
            if (ty == null) {
                ty = yaw;
                tp = pitch;
            }
            for (int i = 0; i < n; i++) {
                double k = 1 - Math.exp(-h * w * 1.5);
                ty = wrap(ty + wrap(targetYaw - ty) * k);
                tp += (targetPitch - tp) * k;
                vy += (w * w * wrap(ty - yaw) - 2 * z * w * vy) * h;
                vp += (w * w * (tp - pitch) - 2 * z * w * vp) * h;
                yaw = wrap(yaw + vy * h);
                pitch = Math.max(-Math.PI / 2, Math.min(Math.PI / 2, pitch + vp * h));
            }
            t += dt;
            double tremor = 0.18 * Math.PI / 180;
            return new double[] {wrap(yaw + tremor * Math.sin(t * 9.3)), pitch + tremor * Math.sin(t * 7.1 + 1)};
        }
    }
}
