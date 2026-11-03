package net.poena.actors.paper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * A walking path over the blocks the server has loaded: A* on whole blocks, steps up one block (a jump), drops down up to three,
 * and through open doors and plants. Never through lava, fire or cactus; never into an unloaded chunk. Bounded, so a far or
 * closed target answers "no path" instead of stalling the tick.
 */
final class Path {
    static final int MAX_NODES = 6000;
    static final int MAX_DROP = 3;

    private Path() {
    }

    private record Node(BlockPos pos, double g, double f, Node parent) {
    }

    private static boolean loaded(Level level, BlockPos p) {
        return level.isLoaded(p);
    }

    private static boolean dangerous(BlockState s) {
        String id = s.getBlock().getDescriptionId();
        FluidState f = s.getFluidState();
        return id.contains("lava") || id.contains("fire") || id.contains("cactus") || id.contains("magma") || id.contains("sweet_berry")
                || id.contains("powder_snow") || id.contains("campfire") || (!f.isEmpty() && f.is(net.minecraft.tags.FluidTags.LAVA));
    }

    /** The body fits here: no solid block at the feet or the head. */
    static boolean passable(Level level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return !s.blocksMotion() && !dangerous(s);
    }

    /** A block to stand on. */
    static boolean ground(Level level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return (s.blocksMotion() || !s.getFluidState().isEmpty()) && !dangerous(s);
    }

    static boolean standable(Level level, BlockPos feet) {
        return loaded(level, feet) && passable(level, feet) && passable(level, feet.above()) && ground(level, feet.below());
    }

    /** The feet positions from `start` to `goal` (both block positions), or an empty list when there is none within the bound. */
    static List<BlockPos> find(Level level, BlockPos start, BlockPos goal, double within) {
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        Map<BlockPos, Double> best = new HashMap<>();
        open.add(new Node(start, 0, start.distManhattan(goal), null));
        best.put(start, 0.0);
        int seen = 0;
        while (!open.isEmpty() && seen++ < MAX_NODES) {
            Node n = open.poll();
            if (Math.sqrt(n.pos.distSqr(goal)) <= within) {
                return unwind(n);
            }
            for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
                BlockPos side = n.pos.offset(d[0], 0, d[1]);
                boolean diagonal = d[0] != 0 && d[1] != 0;
                if (diagonal && (!passable(level, n.pos.offset(d[0], 0, 0)) || !passable(level, n.pos.offset(0, 0, d[1]))
                        || !passable(level, n.pos.offset(d[0], 1, 0)) || !passable(level, n.pos.offset(0, 1, d[1])))) {
                    continue;
                }
                BlockPos to = null;
                double cost = diagonal ? 1.414 : 1;
                if (standable(level, side)) {
                    to = side;
                } else if (!diagonal && standable(level, side.above()) && passable(level, n.pos.above(2))) {
                    to = side.above();
                    cost += 0.6;
                } else if (!diagonal && passable(level, side) && passable(level, side.above())) {
                    for (int drop = 1; drop <= MAX_DROP; drop++) {
                        BlockPos down = side.below(drop);
                        if (!loaded(level, down)) {
                            break;
                        }
                        if (standable(level, down)) {
                            to = down;
                            cost += 0.3 * drop;
                            break;
                        }
                        if (!passable(level, down)) {
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
                open.add(new Node(to, g, g + Math.sqrt(to.distSqr(goal)), n));
            }
        }
        return List.of();
    }

    private static List<BlockPos> unwind(Node n) {
        Deque<BlockPos> out = new ArrayDeque<>();
        for (Node at = n; at != null; at = at.parent) {
            out.addFirst(at.pos);
        }
        return new ArrayList<>(out);
    }
}
