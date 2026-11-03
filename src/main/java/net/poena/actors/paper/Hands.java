package net.poena.actors.paper;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.poena.actors.core.ApiException;

/**
 * What an actor does with its hands, sent through the game's own packet handlers as a client's packets would be. So plugins
 * see the events a person makes: PlayerInteractEvent, BlockBreakEvent, BlockPlaceEvent, InventoryClickEvent, InventoryDragEvent,
 * PlayerInteractEntityEvent, PlayerDropItemEvent, SignChangeEvent, PrepareAnvilEvent, TradeSelectEvent, PlayerCommandPreprocessEvent.
 * Runs on the thread that owns the player.
 */
final class Hands {
    /** How far a block or an entity can be reached, as the server checks it. */
    static final double REACH = 4.5;

    /** A click of the container screen: the vanilla input and button. */
    record Click(ContainerInput input, int button) {
    }

    private final ServerPlayer player;
    private final ActorListener listener;
    private int sequence;

    Hands(ServerPlayer player, ActorListener listener) {
        this.player = player;
        this.listener = listener;
    }

    private int next() {
        return ++sequence;
    }

    // ---- items and blocks

    /** Right click into the air with the held item: eat, throw, open a menu item. */
    void use() {
        listener.handleUseItem(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, next(), player.getYRot(), player.getXRot()));
    }

    /** Right click on a block: open a chest, press a button, sit on a sign editor; the held item acts on it. */
    void useOn(BlockPos pos, Direction face) {
        reachable(Vec3.atCenterOf(pos));
        Vec3 hit = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        listener.handleUseItemOn(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false), next()));
    }

    /** The face of `pos` that looks at the actor. */
    Direction faceTowardsMe(BlockPos pos) {
        Vec3 d = player.getEyePosition().subtract(Vec3.atCenterOf(pos));
        return Direction.getApproximateNearest(d.x, d.y, d.z);
    }

    /** Places the held block at `target`, against a solid neighbour, as a click on that neighbour's face does. */
    void place(BlockPos target) {
        if (!player.level().getBlockState(target).canBeReplaced()) {
            throw new ApiException(409, "the block at " + target.toShortString() + " is taken");
        }
        Direction best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            BlockPos against = target.relative(d);
            BlockState s = player.level().getBlockState(against);
            if (s.isAir() || s.canBeReplaced()) {
                continue;
            }
            double dist = player.getEyePosition().distanceToSqr(Vec3.atCenterOf(against));
            if (dist < bestDistance) {
                bestDistance = dist;
                best = d;
            }
        }
        if (best == null) {
            throw new ApiException(409, "nothing to place against at " + target.toShortString());
        }
        BlockPos against = target.relative(best);
        Direction face = best.getOpposite();
        reachable(Vec3.atCenterOf(target));
        Vec3 hit = Vec3.atCenterOf(against).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        listener.handleUseItemOn(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, against, false), next()));
    }

    void startBreak(BlockPos pos, Direction face) {
        reachable(Vec3.atCenterOf(pos));
        listener.handlePlayerAction(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, face, next()));
    }

    void finishBreak(BlockPos pos, Direction face) {
        listener.handlePlayerAction(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, face, next()));
    }

    void abortBreak(BlockPos pos, Direction face) {
        listener.handlePlayerAction(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, pos, face, next()));
    }

    /** How much of the block one tick of digging takes off, with the held tool, as the server counts it. */
    float digPerTick(BlockPos pos) {
        return player.level().getBlockState(pos).getDestroyProgress(player, player.level(), pos);
    }

    void drop(boolean stack) {
        var action = stack ? ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS : ServerboundPlayerActionPacket.Action.DROP_ITEM;
        listener.handlePlayerAction(new ServerboundPlayerActionPacket(action, BlockPos.ZERO, Direction.DOWN));
    }

    void swapHands() {
        listener.handlePlayerAction(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
    }

    void select(int slot) {
        if (slot < 0 || slot > 8) {
            throw new ApiException(400, "slot is 0-8");
        }
        listener.handleSetCarriedItem(new ServerboundSetCarriedItemPacket(slot));
    }

    /** Right click on an entity: a villager's trades, a horse, an NPC plugin's menu. */
    void interact(Entity target) {
        reachable(target.position().add(0, target.getBbHeight() / 2, 0));
        Vec3 at = new Vec3(0, target.getBbHeight() / 2, 0);
        listener.handleInteract(new ServerboundInteractPacket(target.getId(), InteractionHand.MAIN_HAND, at, player.isShiftKeyDown()));
    }

    /** A command as the actor types it (no slash). The actor has its own permissions, not the console's. */
    void command(String line) {
        String c = line.startsWith("/") ? line.substring(1) : line;
        if (c.isBlank() || c.length() > 256) {
            throw new ApiException(400, "a command is 1-256 characters");
        }
        listener.handleChatCommand(new ServerboundChatCommandPacket(c));
    }

    // ---- the open screen

    private AbstractContainerMenu menu() {
        return player.containerMenu;
    }

    static Click click(String type, int hotbar) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "left" -> new Click(ContainerInput.PICKUP, 0);
            case "right" -> new Click(ContainerInput.PICKUP, 1);
            case "shift", "shift_left" -> new Click(ContainerInput.QUICK_MOVE, 0);
            case "shift_right" -> new Click(ContainerInput.QUICK_MOVE, 1);
            case "number" -> {
                if (hotbar < 1 || hotbar > 9) {
                    throw new ApiException(400, "a number click needs hotbar 1-9");
                }
                yield new Click(ContainerInput.SWAP, hotbar - 1);
            }
            case "offhand" -> new Click(ContainerInput.SWAP, 40);
            case "middle" -> new Click(ContainerInput.CLONE, 2);
            case "drop" -> new Click(ContainerInput.THROW, 0);
            case "drop_stack" -> new Click(ContainerInput.THROW, 1);
            case "pickup_all" -> new Click(ContainerInput.PICKUP_ALL, 0);
            default -> throw new ApiException(400, "unknown click '" + type + "' (left, right, shift, shift_right, number, offhand, middle, drop, "
                    + "drop_stack, pickup_all, double, outside_left, outside_right)");
        };
    }

    /** One window_click as the client sends it; the server answers with its own state, so nothing is predicted here. */
    void clickRaw(int slot, int button, ContainerInput input) {
        AbstractContainerMenu m = menu();
        if (slot != -999 && (slot < 0 || slot >= m.slots.size())) {
            throw new ApiException(400, "slot " + slot + " is not in the open screen (0-" + (m.slots.size() - 1) + ")");
        }
        listener.handleContainerClick(new ServerboundContainerClickPacket(m.containerId, m.getStateId(), (short) slot, (byte) button, input,
                new Int2ObjectOpenHashMap<HashedStack>(), HashedStack.EMPTY));
    }

    void click(String type, Integer slot, int hotbar) {
        String t = type.toLowerCase(Locale.ROOT);
        if (t.equals("outside_left") || t.equals("outside_right")) {
            clickRaw(-999, t.equals("outside_left") ? 0 : 1, ContainerInput.PICKUP);
            return;
        }
        if (slot == null) {
            throw new ApiException(400, "slot is required");
        }
        if (t.equals("double")) {
            clickRaw(slot, 0, ContainerInput.PICKUP);
            clickRaw(slot, 0, ContainerInput.PICKUP_ALL);
            return;
        }
        Click c = click(t, hotbar);
        clickRaw(slot, c.button(), c.input());
    }

    /** A drag of the carried stack over slots: start, one add per slot, end (QUICK_CRAFT). */
    void drag(List<Integer> slots, String button) {
        int type = switch (button.toLowerCase(Locale.ROOT)) {
            case "left" -> 0;
            case "right" -> 1;
            case "middle" -> 2;
            default -> throw new ApiException(400, "button is left, right or middle");
        };
        if (slots.isEmpty()) {
            throw new ApiException(400, "a drag needs slots");
        }
        if (menu().getCarried().isEmpty()) {
            throw new ApiException(409, "the cursor holds nothing to drag");
        }
        clickRaw(-999, type << 2, ContainerInput.QUICK_CRAFT);
        for (int s : slots) {
            clickRaw(s, (type << 2) | 1, ContainerInput.QUICK_CRAFT);
        }
        clickRaw(-999, (type << 2) | 2, ContainerInput.QUICK_CRAFT);
    }

    void close() {
        listener.handleContainerClose(new ServerboundContainerClosePacket(menu().containerId));
    }

    /** A screen button: enchanting option, stonecutter or loom index, lectern page. */
    void button(int id) {
        listener.handleContainerButtonClick(new ServerboundContainerButtonClickPacket(menu().containerId, id));
    }

    void rename(String name) {
        if (name.length() > 50) {
            throw new ApiException(400, "an anvil name is at most 50 characters");
        }
        listener.handleRenameItem(new ServerboundRenameItemPacket(name));
    }

    void trade(int index) {
        listener.handleSelectTrade(new ServerboundSelectTradePacket(index));
    }

    /** The sign editor the server opened for the actor (after a right click on a sign). */
    void sign(BlockPos pos, boolean front, List<String> lines) {
        String[] l = new String[4];
        for (int i = 0; i < 4; i++) {
            l[i] = i < lines.size() ? lines.get(i) : "";
        }
        listener.handleSignUpdate(new ServerboundSignUpdatePacket(pos, front, l[0], l[1], l[2], l[3]));
    }

    private void reachable(Vec3 at) {
        if (player.getEyePosition().distanceTo(at) > REACH + 1) {
            throw new ApiException(409, "out of reach (" + Math.round(player.getEyePosition().distanceTo(at) * 10) / 10.0 + " blocks)");
        }
    }
}
