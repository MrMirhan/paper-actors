package net.poena.actors.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HumanTest {
    @Test
    void gazeMatchesTheJavaScriptSpring() {
        Rng r = new Rng("take1");
        Human.Gaze g = new Human.Gaze(r, Human.profile("mid"), 0, 0);
        double[] res = null;
        for (int i = 0; i < 20; i++) {
            res = g.step(0.05, 1.2, -0.3);
        }
        assertEquals(1.1972556142371804, res[0], 1e-9);
        assertEquals(-0.298344300611485, res[1], 1e-9);
    }

    @Test
    void swingTimingMatchesTheJavaScriptSide() {
        Rng r = new Rng("x");
        Human.Profile p = Human.profile("tryhard");
        assertEquals(101.01054800452238, Human.nextSwing(r, p, "1.8", 625), 1e-6);
        assertEquals(697.8224341666763, Human.nextSwing(r, p, "1.9", 625), 1e-6);
    }

    @Test
    void gazeTurnsSmoothlyAndDoesNotSnap() {
        Human.Gaze g = new Human.Gaze(new Rng("s"), Human.profile("rookie"), 0, 0);
        double first = g.step(0.05, 2.0, 0)[0];
        assertTrue(first < 1.0, "a rookie must not snap to the target in one tick, got " + first);
    }

    @Test
    void unknownProfileFallsBackToMid() {
        assertEquals(Human.PROFILES.get("mid"), Human.profile("nope"));
        assertEquals(Human.PROFILES.get("mid"), Human.profile(null));
    }

    @Test
    void minecraftAngleConversionRoundTrips() {
        for (float yaw : new float[] {0f, 90f, -90f, 179f, -179f}) {
            assertEquals(Math.floorMod(Math.round(yaw), 360), Math.floorMod(Math.round(Human.mcYaw(Human.fromMcYaw(yaw))), 360));
        }
        // Looking at +z (south) from the origin is Minecraft yaw 0.
        double[] a = Human.angles(new Human.Point(0, 0, 0), new Human.Point(0, 0, 5));
        assertEquals(0f, Human.mcYaw(a[0]) % 360f == 360f ? 0f : (Human.mcYaw(a[0]) % 360f), 1e-3);
        // West (-x) is Minecraft yaw 90 and east (+x) is -90: Minecraft points along (-sin yaw, cos yaw).
        assertEquals(90f, Math.floorMod(Math.round(Human.mcYaw(Human.angles(new Human.Point(0, 0, 0), new Human.Point(-5, 0, 0))[0])), 360), 1e-3);
        assertEquals(270f, Math.floorMod(Math.round(Human.mcYaw(Human.angles(new Human.Point(0, 0, 0), new Human.Point(5, 0, 0))[0])), 360), 1e-3);
    }
}
