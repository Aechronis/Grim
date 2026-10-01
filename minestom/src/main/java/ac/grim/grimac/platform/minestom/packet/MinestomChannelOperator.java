package ac.grim.grimac.platform.minestom.packet;

import com.github.retrooper.packetevents.netty.channel.ChannelOperator;

import io.netty.buffer.Unpooled;

import java.net.SocketAddress;
import java.util.List;

final class MinestomChannelOperator implements ChannelOperator {
    private MinestomConnection connection(Object value) {
        return (MinestomConnection) value;
    }

    public SocketAddress remoteAddress(Object channel) {
        return connection(channel).nativeConnection.getRemoteAddress();
    }

    public SocketAddress localAddress(Object channel) {
        try {
            return connection(channel).nativeConnection.getChannel().getLocalAddress();
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
    }

    public boolean isOpen(Object channel) {
        return connection(channel).nativeConnection.isOnline();
    }

    public Object close(Object channel) {
        connection(channel).nativeConnection.disconnect();
        return null;
    }

    public Object write(Object channel, Object buffer) {
        connection(channel).write(buffer, false);
        return null;
    }

    public Object flush(Object channel) {
        return null;
    } // Native sendPacket wakes the socket writer.

    public Object writeAndFlush(Object channel, Object buffer) {
        return write(channel, buffer);
    }

    public Object fireChannelRead(Object channel, Object buffer) {
        connection(channel).receive(buffer, false);
        return null;
    }

    public Object writeInContext(Object channel, String ctx, Object buffer) {
        connection(channel).write(buffer, true);
        return null;
    }

    public Object flushInContext(Object channel, String ctx) {
        return null;
    }

    public Object writeAndFlushInContext(Object channel, String ctx, Object buffer) {
        return writeInContext(channel, ctx, buffer);
    }

    public Object fireChannelReadInContext(Object channel, String ctx, Object buffer) {
        connection(channel).receive(buffer, true);
        return null;
    }

    public List<String> pipelineHandlerNames(Object channel) {
        return List.of("minestom-framing", "grim-packets", "minestom-codec");
    }

    public Object getPipelineHandler(Object channel, String name) {
        return null;
    }

    public Object getPipelineContext(Object channel, String name) {
        return null;
    }

    public Object getPipeline(Object channel) {
        return channel;
    }

    public void runInEventLoop(Object channel, Runnable runnable) {
        connection(channel).run(runnable);
    }

    public Object pooledByteBuf(Object channel) {
        return Unpooled.buffer();
    }
}
