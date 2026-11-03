package net.poena.actors.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** Seeded generator, bit-for-bit the same stream as rng() in agent/js/human.js, so a retake with one seed behaves the same on every body. */
public final class Rng {
    private int a;

    public Rng(String seed) {
        int h = 0x811C9DC5;
        for (int i = 0; i < seed.length(); ) {
            int cp = seed.codePointAt(i);
            i += Character.charCount(cp);
            // JS charCodeAt(0) of a code point string is its first UTF-16 unit.
            int unit = cp > 0xFFFF ? Character.highSurrogate(cp) : cp;
            h = (h ^ unit) * 16777619;
        }
        this.a = h;
    }

    public Rng(long seed) {
        this.a = (int) seed;
    }

    /** A JSON seed is a string or a number; anything else falls back to seed 1 like the JS side. */
    public static Rng fromJson(JsonElement seed) {
        if (seed != null && seed.isJsonPrimitive()) {
            JsonPrimitive p = seed.getAsJsonPrimitive();
            if (p.isString()) {
                return new Rng(p.getAsString());
            }
            if (p.isNumber()) {
                return new Rng(p.getAsLong());
            }
        }
        return new Rng(1L);
    }

    /** Uniform in [0, 1). */
    public double next() {
        a += 0x6d2b79f5;
        int t = a;
        t = (t ^ (t >>> 15)) * (t | 1);
        t ^= t + (t ^ (t >>> 7)) * (t | 61);
        return ((t ^ (t >>> 14)) & 0xFFFFFFFFL) / 4294967296.0;
    }

    public double range(double lo, double hi) {
        return lo + next() * (hi - lo);
    }

    public boolean chance(double p) {
        return next() < p;
    }

    public double normal(double mean, double sd) {
        double u = Math.max(next(), 1e-9);
        double v = next();
        return mean + sd * Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
    }
}
