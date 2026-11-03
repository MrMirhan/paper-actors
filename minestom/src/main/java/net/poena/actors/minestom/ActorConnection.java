package net.poena.actors.minestom;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import net.minestom.server.entity.Player;
import net.minestom.server.network.packet.client.common.ClientKeepAlivePacket;
import net.minestom.server.network.packet.client.play.ClientChunkBatchReceivedPacket;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.common.KeepAlivePacket;
import net.minestom.server.network.packet.server.play.ChunkBatchFinishedPacket;
import net.minestom.server.network.player.PlayerConnection;

/**
 * The connection of an actor: no socket. What the server sends goes nowhere, except the two packets a client must answer to stay:
 * a keep-alive (or the server kicks it) and the end of a chunk batch (or the server stops sending it chunks, and the world around
 * it stops loading). Both are answered as a client answers them: into the player's packet queue, which the server reads in the
 * player's own tick. A disconnect is the base class's, so the server removes the player as it removes a person.
 */
final class ActorConnection extends PlayerConnection {
    /** The chunks a tick the actor says it takes, as a client on a fast machine says it. */
    static final float CHUNKS_PER_TICK = 25f;
    private static final SocketAddress ADDRESS = new InetSocketAddress("127.0.0.1", 0);

    @Override
    public void sendPacket(SendablePacket packet) {
        ServerPacket p = packet instanceof ServerPacket sp ? sp : SendablePacket.extractServerPacket(getServerState(), packet);
        if (p instanceof KeepAlivePacket keepAlive) {
            answer(new ClientKeepAlivePacket(keepAlive.id()));
        } else if (p instanceof ChunkBatchFinishedPacket) {
            answer(new ClientChunkBatchReceivedPacket(CHUNKS_PER_TICK));
        }
    }

    private void answer(net.minestom.server.network.packet.client.ClientPacket reply) {
        Player player = getPlayer();
        if (player != null) {
            player.addPacketToQueue(reply);
        }
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return ADDRESS;
    }
}
