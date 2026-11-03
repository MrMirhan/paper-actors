package net.poena.actors.paper;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** Game listener of an actor: nobody is on the other end, so nothing is sent and no keep-alive timeout can fire. */
final class ActorListener extends ServerGamePacketListenerImpl {
    ActorListener(MinecraftServer server, Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
        super(server, connection, player, cookie);
    }

    @Override
    protected void keepConnectionAlive() {
    }

    private static final java.lang.reflect.Field AWAITING_POSITION;
    private static final java.lang.reflect.Field AWAITING_TELEPORT;

    static {
        try {
            AWAITING_POSITION = ServerGamePacketListenerImpl.class.getDeclaredField("awaitingPositionFromClient");
            AWAITING_TELEPORT = ServerGamePacketListenerImpl.class.getDeclaredField("awaitingTeleport");
            AWAITING_POSITION.setAccessible(true);
            AWAITING_TELEPORT.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * After a teleport the server waits for the client to confirm it, and ignores block and item use until then. A client
     * confirms at once; so does an actor.
     */
    void confirmTeleport() {
        try {
            if (AWAITING_POSITION.get(this) != null) {
                handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(AWAITING_TELEPORT.getInt(this)));
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void send(Packet<?> packet) {
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener) {
    }
}
