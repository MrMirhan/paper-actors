package net.poena.actors.paper;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.poena.actors.core.Pathfinder;

/**
 * A walking path over the blocks the server has loaded (core.Pathfinder): what each block is, as Paper says it. Never through
 * lava, fire or cactus; never into an unloaded chunk.
 */
final class Path {
    private Path() {
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

    /** The feet positions from `start` to `goal` (both block positions), or an empty list when there is none within the bound. */
    static List<BlockPos> find(Level level, BlockPos start, BlockPos goal, double within) {
        Pathfinder.Blocks blocks = new Pathfinder.Blocks() {
            public boolean loaded(int x, int y, int z) {
                return Path.loaded(level, new BlockPos(x, y, z));
            }

            public boolean passable(int x, int y, int z) {
                return Path.passable(level, new BlockPos(x, y, z));
            }

            public boolean ground(int x, int y, int z) {
                return Path.ground(level, new BlockPos(x, y, z));
            }
        };
        List<BlockPos> out = new ArrayList<>();
        for (Pathfinder.Pos p : Pathfinder.find(blocks, pos(start), pos(goal), within)) {
            out.add(new BlockPos(p.x(), p.y(), p.z()));
        }
        return out;
    }

    private static Pathfinder.Pos pos(BlockPos p) {
        return new Pathfinder.Pos(p.getX(), p.getY(), p.getZ());
    }
}
