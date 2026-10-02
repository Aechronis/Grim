package ac.grim.grimac.platform.minestom.packet;

import ac.grim.grimac.minestom.GrimPlayer;
import ac.grim.grimac.minestom.PacketTransport;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.ProtocolPacketEvent;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.util.EventCreationUtil;
import com.github.retrooper.packetevents.util.PacketEventsImplHelper;

import io.netty.buffer.ByteBuf;

import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerPacketOutEvent;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.network.packet.server.BufferedPacket;
import net.minestom.server.network.player.PlayerSocketConnection;

/** Checks raw packets supplied by an in-process protocol translator. */
final class TranslatedMinestomConnection extends MinestomConnection
        implements PacketTransport.Listener {
    private final PacketTransport transport;

    TranslatedMinestomConnection(
            MinestomPacketEvents api,
            PlayerSocketConnection connection,
            GrimPlayer player,
            PacketTransport transport) {
        super(api, connection, player);
        this.transport = transport;
    }

    void run(Runnable task) {
        transport.runOnEventLoop(
                () ->
                        inContext(
                                () -> {
                                    task.run();
                                    return null;
                                }));
    }

    private <T> T inContext(java.util.function.Supplier<T> action) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(getClass().getClassLoader());
            return action.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Override
    void login(Player player) {
        if (loggedIn) return;
        super.login(player);
        transport.installPacketListener(this);
    }

    void close() {
        transport.installPacketListener(null);
    }

    // Raw packets have already passed the checks before Minestom decoded/queued them.
    ClientPacket read(ClientPacket packet) {
        return packet;
    }

    void send(PlayerPacketOutEvent event) {}

    void buffered(BufferedPacket packet) {
        nativeConnection.sendPacket(packet);
    }

    void write(Object packet, boolean silent) {
        try {
            transport.sendRaw((ByteBuf) packet, silent);
        } catch (Exception error) {
            fail(error);
        }
    }

    void receive(Object packet, boolean silent) {
        try {
            transport.receiveRaw((ByteBuf) packet, silent);
        } catch (Exception error) {
            fail(error);
        }
    }

    @Override
    public void receive(ByteBuf packet, boolean beforeTranslation) {
        inContext(
                () -> {
                    handleReceive(packet, beforeTranslation);
                    return null;
                });
    }

    private void handleReceive(ByteBuf packet, boolean beforeTranslation) {
        user.setDecoderState(ConnectionState.valueOf(nativeConnection.getClientState().name()));
        try {
            if (beforeTranslation) {
                ProtocolPacketEvent event;
                int start = packet.readerIndex();
                clientEncoding = true;
                try {
                    event = EventCreationUtil.createReceiveEvent(this, user, player, packet, false);
                } finally {
                    clientEncoding = false;
                }
                dispatchPreTranslation(event, packet, start);
            } else {
                PacketEventsImplHelper.handleServerBoundPacket(this, user, player, packet, true);
            }
        } catch (Exception error) {
            fail(error);
            packet.clear();
        }
    }

    @Override
    public Runnable send(ByteBuf packet) {
        return inContext(() -> handleSend(packet));
    }

    private Runnable handleSend(ByteBuf packet) {
        user.setEncoderState(ConnectionState.valueOf(nativeConnection.getServerState().name()));
        try {
            int start = packet.readerIndex();
            PacketSendEvent pre =
                    EventCreationUtil.createSendEvent(this, user, player, packet, true);
            dispatchPreTranslation(pre, packet, start);
            if (!packet.isReadable()) return null;
            PacketSendEvent event =
                    PacketEventsImplHelper.handleClientBoundPacket(
                            this, user, player, packet, true);
            return () ->
                    run(
                            () -> {
                                if (pre.hasTasksAfterSend())
                                    pre.getTasksAfterSend().forEach(Runnable::run);
                                if (event != null && event.hasTasksAfterSend())
                                    event.getTasksAfterSend().forEach(Runnable::run);
                            });
        } catch (Exception error) {
            fail(error);
            packet.clear();
            return null;
        }
    }

    private void dispatchPreTranslation(ProtocolPacketEvent event, ByteBuf packet, int start) {
        int body = packet.readerIndex();
        api.callPreTranslationEvent(event, () -> packet.readerIndex(body));
        if (event.isCancelled()) {
            packet.clear();
        } else if (event.getLastUsedWrapper() != null) {
            packet.clear();
            var wrapper = event.getLastUsedWrapper();
            wrapper.writeVarInt(event.getPacketId());
            wrapper.write();
        } else {
            packet.readerIndex(start);
        }
        if (event.hasPostTasks()) event.getPostTasks().forEach(Runnable::run);
    }
}
