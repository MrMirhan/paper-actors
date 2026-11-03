package net.poena.actors.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A walking path over the blocks a server has loaded: A* on whole blocks, steps up one block (a jump), drops down up to three,
 * and through open doors and plants. The server's adapter says what each block is; the search is the same on every server.
 * Bounded, so a far or closed target answers "no path" instead of stalling the tick.
 */
public final class Pathfinder {
    public static final int MAX_NODES = 6000;
    public static final int MAX_DROP = 3;

    /** What the search needs to know of a block. Every call is on the thread that owns the world. */
    public interface Blocks {
        /** The chunk of this block is loaded: the search never goes into an unloaded one. */
        boolean loaded(int x, int y, int z);

        /** The body fits here: no solid block, nothing that hurts (lava, fire, cactus). */
        boolean passable(int x, int y, int z);

        /** A block to stand on: solid or a liquid, and nothing that hurts. */
        boolean ground(int x, int y, int z);
    }

    /** A block position. */
    public record Pos(int x, int y, int z) {
        public Pos offset(int dx, int dy, int dz) {
            return new Pos(x + dx, y + dy, z + dz);
        }

        public double distance(Pos o) {
            double dx = x - o.x;
            double dy = y - o.y;
            double dz = z - o.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        int manhattan(Pos o) {
            return Math.abs(x - o.x) + Math.abs(y - o.y) + Math.abs(z - o.z);
        }
    }

    private record Node(Pos pos, double g, double f, Node parent) {
    }

    private Pathfinder() {
    }

    public static boolean standable(Blocks b, Pos feet) {
        return b.loaded(feet.x, feet.y, feet.z) && passable(b, feet) && passable(b, feet.offset(0, 1, 0)) && b.ground(feet.x, feet.y - 1, feet.z);
    }

    private static boolean passable(Blocks b, Pos p) {
        return b.passable(p.x, p.y, p.z);
    }

    /** The feet positions from `start` to `goal`, or an empty list when there is none within the bound. */
    public static List<Pos> find(Blocks b, Pos start, Pos goal, double within) {
        PriorityQueue<Node> open = new PriorityQueue<>((a, c) -> Double.compare(a.f, c.f));
        Map<Pos, Double> best = new HashMap<>();
        open.add(new Node(start, 0, start.manhattan(goal), null));
        best.put(start, 0.0);
        int seen = 0;
        while (!open.isEmpty() && seen++ < MAX_NODES) {
            Node n = open.poll();
            if (n.pos.distance(goal) <= within) {
                return unwind(n);
            }
            for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
                Pos side = n.pos.offset(d[0], 0, d[1]);
                boolean diagonal = d[0] != 0 && d[1] != 0;
                if (diagonal && (!passable(b, n.pos.offset(d[0], 0, 0)) || !passable(b, n.pos.offset(0, 0, d[1]))
                        || !passable(b, n.pos.offset(d[0], 1, 0)) || !passable(b, n.pos.offset(0, 1, d[1])))) {
                    continue;
                }
                Pos to = null;
                double cost = diagonal ? 1.414 : 1;
                if (standable(b, side)) {
                    to = side;
                } else if (!diagonal && standable(b, side.offset(0, 1, 0)) && passable(b, n.pos.offset(0, 2, 0))) {
                    to = side.offset(0, 1, 0);
                    cost += 0.6;
                } else if (!diagonal && passable(b, side) && passable(b, side.offset(0, 1, 0))) {
                    for (int drop = 1; drop <= MAX_DROP; drop++) {
                        Pos down = side.offset(0, -drop, 0);
                        if (!b.loaded(down.x, down.y, down.z)) {
                            break;
                        }
                        if (standable(b, down)) {
                            to = down;
                            cost += 0.3 * drop;
                            break;
                        }
                        if (!passable(b, down)) {
                            break;
                        }
                    }
                }
                if (to == null) {
                    continue;
                }
                double g = n.g + cost;
                Double known = best.get(to);
                if (known != null && known <= g) {
                    continue;
                }
                best.put(to, g);
                open.add(new Node(to, g, g + to.distance(goal), n));
            }
        }
        return List.of();
    }

    private static List<Pos> unwind(Node n) {
        Deque<Pos> out = new ArrayDeque<>();
        for (Node at = n; at != null; at = at.parent) {
            out.addFirst(at.pos);
        }
        return new ArrayList<>(out);
    }
}
