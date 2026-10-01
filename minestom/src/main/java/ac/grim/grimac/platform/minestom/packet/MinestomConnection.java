package ac.grim.grimac.platform.minestom.packet;

import ac.grim.grimac.minestom.GrimPlayer;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.UserLoginEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.PacketEventsImplHelper;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerFlag;
import net.minestom.server.adventure.MinestomAdventure;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.player.PlayerPacketOutEvent;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.PacketParser;
import net.minestom.server.network.packet.PacketReading;
import net.minestom.server.network.packet.PacketVanilla;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.network.packet.server.BufferedPacket;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.configuration.FinishConfigurationPacket;
import net.minestom.server.network.player.PlayerSocketConnection;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.Deflater;

final class MinestomConnection {
    final PlayerSocketConnection nativeConnection;
    final GrimPlayer player;
    final User user;
    private final MinestomPacketEvents api;
    private final ReentrantLock lock = new ReentrantLock();
    private final IdentityHashMap<ServerPacket, Pending> pending = new IdentityHashMap<>();
    private ByteArrayOutputStream output;
    private boolean awaitingPlay;
    volatile boolean loggedIn;

    private record Pending(boolean silent, List<ServerPacket> tail) {}

    MinestomConnection(
            MinestomPacketEvents api, PlayerSocketConnection connection, GrimPlayer player) {
        this.api = api;
        this.nativeConnection = connection;
        this.player = player;
        user =
                new User(
                        this,
                        ConnectionState.CONFIGURATION,
                        ClientVersion.getById(connection.getProtocolVersion()),
                        new UserProfile(player.getUuid(), player.getUsername()));
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
        pending.clear();
    }

    void login(Player player) {
        if (!loggedIn) {
            loggedIn = true;
            api.getEventManager().callEvent(new UserLoginEvent(user, player));
        }
    }

    ClientPacket read(ClientPacket original) {
        lock.lock();
        ByteBuf packet = null;
        try {
            // FinishConfiguration is consumed directly by Minestom's socket listener. All queued
            // play packets reach us before tick processing; keep the decoder states independent.
            if (awaitingPlay
                    && nativeConnection.getClientState()
                            == net.minestom.server.network.ConnectionState.PLAY) {
                user.setDecoderState(ConnectionState.PLAY);
                awaitingPlay = false;
            }
            var state =
                    net.minestom.server.network.ConnectionState.valueOf(
                            user.getDecoderState().name());
            byte[] before = serialize(PacketVanilla.CLIENT_PACKET_PARSER, state, original);
            packet = Unpooled.buffer(before.length).writeBytes(before);
            PacketEventsImplHelper.handleServerBoundPacket(this, user, player, packet, true);
            if (!packet.isReadable()) return null;
            if (Arrays.equals(before, bytes(packet))) return original;
            int id = ByteBufHelper.readVarInt(packet);
            byte[] body = bytes(packet);
            return PacketVanilla.CLIENT_PACKET_PARSER.parse(
                    state,
                    id,
                    NetworkBuffer.wrap(body, 0, body.length, MinecraftServer.getRegistries()));
        } catch (Exception error) {
            fail(error);
            return null;
        } finally {
            if (packet != null) packet.release();
            lock.unlock();
        }
    }

    void send(PlayerPacketOutEvent nativeEvent) {
        lock.lock();
        boolean owner = output == null;
        try {
            Pending injected = pending.remove(nativeEvent.getPacket());
            if (nativeEvent.isCancelled()) return;
            // Cancel before native serialization. Each original event produces one immutable
            // buffered batch, so native resize retries cannot re-run Grim or split transactions.
            nativeEvent.setCancelled(true);
            ServerPacket packet = nativeEvent.getPacket();
            var state = nativeConnection.getServerState();
            user.setEncoderState(ConnectionState.valueOf(state.name()));
            if (ServerFlag.AUTOMATIC_COMPONENT_TRANSLATION
                    && packet instanceof ServerPacket.ComponentHolding components) {
                packet =
                        components.copyWithOperator(
                                component ->
                                        MinestomAdventure.COMPONENT_TRANSLATOR.apply(
                                                component,
                                                Objects.requireNonNullElseGet(
                                                        player.getLocale(),
                                                        MinestomAdventure::getDefaultLocale)));
            }
            if (owner) output = new ByteArrayOutputStream();
            boolean accepted =
                    encode(
                            serialize(PacketVanilla.SERVER_PACKET_PARSER, state, packet),
                            injected != null && injected.silent());
            if (accepted) {
                // BufferedPacket intentionally bypasses native state transitions. Mirror the
                // transition of the accepted original, just as writePacketSync normally does.
                nativeConnection.setServerState(PacketVanilla.nextServerState(packet, state));
                if (packet instanceof FinishConfigurationPacket) awaitingPlay = true;
            }
            if (injected != null && injected.tail() != null) {
                for (ServerPacket next : injected.tail()) {
                    // A buffered send stays one queue entry and one wire batch. Each contained
                    // packet still passes through the host's ordinary cancellation listeners.
                    EventDispatcher.call(new PlayerPacketOutEvent(player, next));
                }
            }
            if (owner) {
                byte[] result = output.toByteArray();
                if (result.length != 0) {
                    var buffer =
                            NetworkBuffer.wrap(
                                    result, 0, result.length, MinecraftServer.getRegistries());
                    nativeConnection.sendPacket(new BufferedPacket(buffer, 0, result.length));
                }
            }
        } catch (Exception error) {
            fail(error);
        } finally {
            if (owner) output = null;
            lock.unlock();
        }
    }

    void buffered(BufferedPacket buffered) {
        lock.lock();
        try {
            var copy = buffered.buffer().copy(buffered.index(), buffered.length());
            if (copy.readableBytes() == 0) return;
            var result =
                    PacketReading.readServers(
                            copy,
                            nativeConnection.getServerState(),
                            MinecraftServer.getCompressionThreshold() > 0);
            if (!(result instanceof PacketReading.Result.Success<ServerPacket> success)
                    || copy.readableBytes() != 0)
                throw new IllegalArgumentException(
                        "Expected complete native-protocol buffered packets");
            List<ServerPacket> packets =
                    success.packets().stream().map(PacketReading.ParsedPacket::packet).toList();
            ServerPacket first = queueIdentity(packets.getFirst());
            pending.put(first, new Pending(false, packets.subList(1, packets.size())));
            nativeConnection.sendPacket(first);
        } catch (Exception error) {
            fail(error);
        } finally {
            lock.unlock();
        }
    }

    private boolean encode(byte[] original, boolean silent) throws Exception {
        ByteBuf packet = Unpooled.buffer(original.length).writeBytes(original);
        try {
            PacketSendEvent event =
                    silent
                            ? null
                            : PacketEventsImplHelper.handleClientBoundPacket(
                                    this, user, player, packet, true);
            if (!packet.isReadable()) return false;
            output.writeBytes(frame(bytes(packet), MinecraftServer.getCompressionThreshold() > 0));
            if (event != null && event.hasTasksAfterSend()) {
                for (Runnable task : event.getTasksAfterSend()) task.run();
            }
            return true;
        } finally {
            packet.release();
        }
    }

    void write(Object value, boolean silent) {
        ByteBuf packet = (ByteBuf) value;
        lock.lock();
        try {
            byte[] payload = bytes(packet);
            if (output != null) encode(payload, silent);
            else {
                // PacketEvents writes must run their listeners before returning: setbacks send
                // a ping and immediately capture the transaction incremented by its listener.
                // Deferring that listener to the socket writer records the previous transaction
                // and makes every valid setback acknowledgement look like an ignored teleport.
                // Dispatch through the host listeners now; send() queues the resulting immutable
                // batch under this lock, keeping wire order equal to Grim's processing order.
                var state =
                        net.minestom.server.network.ConnectionState.valueOf(
                                user.getEncoderState().name());
                int id = ByteBufHelper.readVarInt(packet);
                byte[] body = bytes(packet);
                ServerPacket nativePacket =
                        PacketVanilla.SERVER_PACKET_PARSER.parse(
                                state,
                                id,
                                NetworkBuffer.wrap(
                                        body, 0, body.length, MinecraftServer.getRegistries()));
                nativePacket = queueIdentity(nativePacket);
                pending.put(nativePacket, new Pending(silent, null));
                EventDispatcher.call(new PlayerPacketOutEvent(player, nativePacket));
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
            var state =
                    net.minestom.server.network.ConnectionState.valueOf(
                            user.getDecoderState().name());
            if (!silent)
                PacketEventsImplHelper.handleServerBoundPacket(this, user, player, packet, true);
            if (!packet.isReadable()) return;
            int id = ByteBufHelper.readVarInt(packet);
            byte[] body = bytes(packet);
            player.enqueueAccepted(
                    PacketVanilla.CLIENT_PACKET_PARSER.parse(
                            state,
                            id,
                            NetworkBuffer.wrap(
                                    body, 0, body.length, MinecraftServer.getRegistries())));
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

    private static ServerPacket queueIdentity(ServerPacket packet)
            throws ReflectiveOperationException {
        // Minestom reuses singleton instances when decoding fieldless records (e.g. bundle
        // delimiters). Construct a fresh public value so each queued batch has its own identity.
        var type = packet.getClass();
        return type.isRecord() && type.getRecordComponents().length == 0
                ? (ServerPacket) type.getConstructor().newInstance()
                : packet;
    }

    private static <T> byte[] serialize(
            PacketParser<T> parser, net.minestom.server.network.ConnectionState state, T packet) {
        @SuppressWarnings("unchecked")
        var registry =
                (net.minestom.server.network.packet.PacketRegistry<T>) parser.stateRegistry(state);
        var info = registry.packetInfo(packet);
        NetworkBuffer buffer = NetworkBuffer.resizableBuffer(256, MinecraftServer.getRegistries());
        buffer.write(NetworkBuffer.VAR_INT, info.id());
        buffer.write(info.serializer(), packet);
        byte[] bytes = new byte[Math.toIntExact(buffer.writeIndex())];
        buffer.copyTo(0, bytes, 0, bytes.length);
        return bytes;
    }

    private static byte[] bytes(ByteBuf buffer) {
        byte[] result = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), result);
        return result;
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
