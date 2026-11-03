package net.poena.actors.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PathfinderTest {
    /** A flat floor at y = 0 from -20 to 20, loaded; walls, steps and holes added by each test. */
    private static final class World implements Pathfinder.Blocks {
        final Set<Pathfinder.Pos> solid = new HashSet<>();
        final Set<Pathfinder.Pos> hurts = new HashSet<>();

        World() {
            for (int x = -20; x <= 20; x++) {
                for (int z = -20; z <= 20; z++) {
                    solid.add(new Pathfinder.Pos(x, 0, z));
                }
            }
        }

        public boolean loaded(int x, int y, int z) {
            return Math.abs(x) <= 20 && Math.abs(z) <= 20;
        }

        public boolean passable(int x, int y, int z) {
            Pathfinder.Pos p = new Pathfinder.Pos(x, y, z);
            return !solid.contains(p) && !hurts.contains(p);
        }

        public boolean ground(int x, int y, int z) {
            Pathfinder.Pos p = new Pathfinder.Pos(x, y, z);
            return solid.contains(p) && !hurts.contains(p);
        }
    }

    private static Pathfinder.Pos at(int x, int y, int z) {
        return new Pathfinder.Pos(x, y, z);
    }

    @Test
    void goesAroundAWall() {
        World w = new World();
        for (int z = -5; z <= 5; z++) {
            w.solid.add(at(3, 1, z));
            w.solid.add(at(3, 2, z));
        }
        List<Pathfinder.Pos> path = Pathfinder.find(w, at(0, 1, 0), at(6, 1, 0), 0.5);
        assertFalse(path.isEmpty());
        assertEquals(at(6, 1, 0), path.get(path.size() - 1));
        for (Pathfinder.Pos p : path) {
            assertFalse(p.x() == 3 && Math.abs(p.z()) <= 5, "never through the wall: " + p);
        }
    }

    @Test
    void stepsUpOneBlockAndDropsUpToThree() {
        World w = new World();
        w.solid.add(at(2, 1, 0));
        List<Pathfinder.Pos> up = Pathfinder.find(w, at(0, 1, 0), at(2, 2, 0), 0.5);
        assertEquals(at(2, 2, 0), up.get(up.size() - 1), "onto the step");

        World cliff = new World();
        for (int x = 2; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) {
                cliff.solid.remove(at(x, 0, z));
                cliff.solid.add(at(x, -3, z));
            }
        }
        List<Pathfinder.Pos> down = Pathfinder.find(cliff, at(0, 1, 0), at(4, -2, 0), 0.5);
        assertEquals(at(4, -2, 0), down.get(down.size() - 1), "down three blocks");
    }

    @Test
    void neverThroughWhatHurtsNorIntoUnloadedChunks() {
        World w = new World();
        for (int z = -20; z <= 20; z++) {
            w.hurts.add(at(3, 1, z));
        }
        assertTrue(Pathfinder.find(w, at(0, 1, 0), at(6, 1, 0), 0.5).isEmpty(), "a line of fire closes the way");
        assertTrue(Pathfinder.find(new World(), at(0, 1, 0), at(30, 1, 0), 0.5).isEmpty(), "the goal is beyond the loaded blocks");
    }

    @Test
    void anActorsUuidComesFromTheNamespaceAndTheName() {
        assertEquals(Gate.actorUuid("PaperActor", "Crowd01"), Gate.actorUuid("PaperActor", "crowd01"), "names are not case sensitive");
        assertNotEquals(Gate.actorUuid("PaperActor", "Crowd01"), Gate.actorUuid("Other", "Crowd01"));
        // Never the offline UUID of the name, which a real account on an offline server has.
        assertNotEquals(java.util.UUID.nameUUIDFromBytes("OfflinePlayer:Crowd01".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                Gate.actorUuid("PaperActor", "Crowd01"));
        assertEquals("1c54975f-105e-3eeb-9136-76af7cfc45aa", Gate.actorUuid("PaperActor", "Crowd01").toString(), "as 0.1.0 made it");
    }
}
