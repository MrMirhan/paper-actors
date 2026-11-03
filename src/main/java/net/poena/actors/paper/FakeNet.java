package net.poena.actors.paper;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;

/** A connection with no socket behind it: every outgoing packet is dropped, nothing ever arrives. */
final class FakeNet {
    final Connection connection;
    private final EmbeddedChannel channel;

    private FakeNet(Connection connection, EmbeddedChannel channel) {
        this.connection = connection;
        this.channel = channel;
    }

    static FakeNet open() {
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new LoopbackChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                ReferenceCountUtil.release(msg);
                promise.setSuccess();
            }
        }, connection);
        return new FakeNet(connection, channel);
    }

    void close() {
        channel.close();
    }

    /** Plugins that ban or log by IP read the address as an InetSocketAddress, so it must be one. */
    private static final class LoopbackChannel extends EmbeddedChannel {
        private static final SocketAddress ADDRESS = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);

        LoopbackChannel(io.netty.channel.ChannelHandler... handlers) {
            super(handlers);
        }

        @Override
        protected SocketAddress remoteAddress0() {
            return isActive() ? ADDRESS : null;
        }

        @Override
        protected SocketAddress localAddress0() {
            return isActive() ? ADDRESS : null;
        }
    }
}
