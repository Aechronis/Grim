package ac.grim.grimac.platform.minestom.packet;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.UserLoginEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.PacketEventsImplHelper;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerFlag;
import net.minestom.server.entity.Player;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.PacketRegistry;
import net.minestom.server.network.packet.PacketVanilla;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.network.packet.server.BufferedPacket;
import net.minestom.server.network.player.PlayerSocketConnection;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

final class MinestomConnection {
    final PlayerSocketConnection nativeConnection;
    final User user;
    private final MinestomPacketEvents api;
    private final ReentrantLock lock = new ReentrantLock();
    private final java.util.Set<Object> silentPackets =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private ByteArrayOutputStream output;
    private boolean outputCompressed;
    volatile boolean loggedIn;

    MinestomConnection(MinestomPacketEvents api, PlayerSocketConnection connection) {
        this.api = api;
        nativeConnection = connection;
        user = new User(this, ConnectionState.HANDSHAKING, null, new UserProfile(null, null));
    }

    void run(Runnable task) {
        lock.lock();
        try {
            task.run();
        } finally {
            lock.unlock();
        }
    }

    void close() {
        silentPackets.clear();
    }

    void login(Player player) {
        if (!loggedIn) {
            loggedIn = true;
            api.getEventManager().callEvent(new UserLoginEvent(user, player));
        }
    }

    private void maybeLogin() {
        Player player = nativeConnection.getPlayer();
        if (player != null && user.getUUID() != null) login(player);
    }

    NetworkBuffer read(PacketRegistry.PacketInfo<?> info, NetworkBuffer input) {
        lock.lock();
        ByteBuf packet = Unpooled.buffer();
        try {
            maybeLogin();
            ByteBufHelper.writeVarInt(packet, info.id());
            byte[] payload = new byte[Math.toIntExact(input.readableBytes())];
            input.copyTo(input.readIndex(), payload, 0, payload.length);
            packet.writeBytes(payload);
            PacketEventsImplHelper.handleServerBoundPacket(
                    this, user, nativeConnection.getPlayer(), packet, true);
            input.readIndex(input.writeIndex());
            if (!packet.isReadable()) return null;
            int id = ByteBufHelper.readVarInt(packet);
            if (id != info.id())
                throw new IllegalStateException("A packet listener changed the native packet type");
            byte[] accepted = bytes(packet);
            return NetworkBuffer.wrap(
                    accepted, 0, accepted.length, MinecraftServer.getRegistries());
        } catch (Exception error) {
            fail(error);
            return null;
        } finally {
            packet.release();
            lock.unlock();
        }
    }

    void sent(
            Object nativePacket,
            NetworkBuffer buffer,
            long start,
            net.minestom.server.network.ConnectionState state,
            boolean compressed) {
        lock.lock();
        try {
            maybeLogin();
            if (silentPackets.remove(nativePacket)) return;
            byte[] framed = new byte[Math.toIntExact(buffer.writeIndex() - start)];
            buffer.copyTo(start, framed, 0, framed.length);
            ByteBuf stream = Unpooled.wrappedBuffer(framed);
            output = new ByteArrayOutputStream(framed.length);
            outputCompressed = compressed && MinecraftServer.getCompressionThreshold() > 0;
            try {
                while (stream.isReadable()) {
                    int frameStart = stream.readerIndex();
                    int length = ByteBufHelper.readVarInt(stream);
                    if (length < 0 || length > stream.readableBytes())
                        throw new IllegalStateException("Invalid outgoing frame length");
                    byte[] frame = new byte[length];
                    stream.readBytes(frame);
                    byte[] payload = inflate(frame, outputCompressed);
                    encode(
                            payload,
                            Arrays.copyOfRange(framed, frameStart, stream.readerIndex()),
                            false);
                }
            } finally {
                stream.release();
            }
            byte[] result = output.toByteArray();
            if (start + result.length > Integer.MAX_VALUE)
                throw new IllegalStateException("Outgoing buffer overflow");
            if (buffer.capacity() < start + result.length) buffer.resize(start + result.length);
            buffer.writeIndex(start);
            buffer.write(NetworkBuffer.RAW_BYTES, result);
        } catch (Exception error) {
            buffer.writeIndex(start);
            fail(error);
        } finally {
            output = null;
            lock.unlock();
        }
    }

    private void encode(byte[] original, byte[] originalFrame, boolean silent) throws Exception {
        ByteBuf packet = Unpooled.buffer(original.length).writeBytes(original);
        try {
            PacketSendEvent event =
                    silent
                            ? null
                            : PacketEventsImplHelper.handleClientBoundPacket(
                                    this, user, nativeConnection.getPlayer(), packet, true);
            if (packet.isReadable()) {
                byte[] encoded = bytes(packet);
                output.writeBytes(
                        originalFrame != null && Arrays.equals(original, encoded)
                                ? originalFrame
                                : frame(encoded, outputCompressed));
                if (event != null && event.hasTasksAfterSend()) {
                    for (Runnable task : event.getTasksAfterSend()) task.run();
                }
            }
        } finally {
            packet.release();
        }
    }

    void write(Object value, boolean silent) {
        ByteBuf packet = (ByteBuf) value;
        lock.lock();
        try {
            byte[] payload = bytes(packet);
            if (output != null) {
                encode(payload, null, silent);
            } else {
                // Queue a native packet. Its eventual socket write passes through the hooks once.
                byte[] framed = frame(payload, MinecraftServer.getCompressionThreshold() > 0);
                NetworkBuffer buffer =
                        NetworkBuffer.wrap(
                                framed, 0, framed.length, MinecraftServer.getRegistries());
                BufferedPacket outgoing = new BufferedPacket(buffer, 0, framed.length);
                if (silent) silentPackets.add(outgoing);
                nativeConnection.sendPacket(outgoing);
            }
        } catch (Exception error) {
            fail(error);
        } finally {
            packet.release();
            lock.unlock();
        }
    }

    void receive(Object value, boolean silent) {
        ByteBuf packet = (ByteBuf) value;
        lock.lock();
        try {
            if (!silent)
                PacketEventsImplHelper.handleServerBoundPacket(
                        this, user, nativeConnection.getPlayer(), packet, true);
            if (!packet.isReadable()) return;
            int id = ByteBufHelper.readVarInt(packet);
            byte[] payload = bytes(packet);
            ClientPacket nativePacket =
                    PacketVanilla.CLIENT_PACKET_PARSER.parse(
                            nativeConnection.getClientState(),
                            id,
                            NetworkBuffer.wrap(
                                    payload, 0, payload.length, MinecraftServer.getRegistries()));
            Player player = nativeConnection.getPlayer();
            if (player == null)
                throw new IllegalStateException(
                        "Cannot inject a play packet before player creation");
            player.addPacketToQueue(nativePacket);
        } catch (Exception error) {
            fail(error);
        } finally {
            packet.release();
            lock.unlock();
        }
    }

    private void fail(Exception error) {
        api.getLogManager()
                .warn("Minestom packet transport failed; closing the affected connection", error);
        nativeConnection.disconnect();
    }

    private static byte[] bytes(ByteBuf buffer) {
        byte[] result = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), result);
        return result;
    }

    private static byte[] inflate(byte[] frame, boolean compressed) throws Exception {
        if (!compressed) return frame;
        ByteBuf data = Unpooled.wrappedBuffer(frame);
        try {
            int size = ByteBufHelper.readVarInt(data);
            if (size == 0) return bytes(data);
            if (size < 0 || size > ServerFlag.MAX_PACKET_SIZE)
                throw new IllegalStateException("Invalid decompressed packet size");
            Inflater inflater = new Inflater();
            try {
                inflater.setInput(bytes(data));
                byte[] result = new byte[size];
                int written = 0;
                while (written < size && !inflater.finished()) {
                    int count = inflater.inflate(result, written, size - written);
                    if (count == 0) break;
                    written += count;
                }
                if (written != size || !inflater.finished())
                    throw new IllegalStateException("Incomplete compressed packet");
                return result;
            } finally {
                inflater.end();
            }
        } finally {
            data.release();
        }
    }

    private static byte[] frame(byte[] payload, boolean compressed) {
        ByteBuf body = Unpooled.buffer();
        ByteBuf framed = Unpooled.buffer();
        try {
            if (compressed) {
                if (payload.length >= MinecraftServer.getCompressionThreshold()) {
                    ByteBufHelper.writeVarInt(body, payload.length);
                    Deflater deflater = new Deflater();
                    try {
                        deflater.setInput(payload);
                        deflater.finish();
                        byte[] scratch = new byte[8192];
                        while (!deflater.finished())
                            body.writeBytes(scratch, 0, deflater.deflate(scratch));
                    } finally {
                        deflater.end();
                    }
                } else {
                    body.writeByte(0);
                    body.writeBytes(payload);
                }
            } else body.writeBytes(payload);
            ByteBufHelper.writeVarInt(framed, body.readableBytes());
            framed.writeBytes(body);
            return bytes(framed);
        } finally {
            body.release();
            framed.release();
        }
    }
}
