package ac.grim.grimac.platform.minestom.packet;

import ac.grim.grimac.minestom.agent.PacketHooks;

import com.github.retrooper.packetevents.event.UserConnectEvent;
import com.github.retrooper.packetevents.util.PacketEventsImplHelper;

import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.PacketRegistry;
import net.minestom.server.network.player.PlayerConnection;
import net.minestom.server.network.player.PlayerSocketConnection;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class MinestomTransport implements PacketHooks.Listener, AutoCloseable {
    private final MinestomPacketEvents api;
    private final ConcurrentHashMap<PlayerSocketConnection, MinestomConnection> connections =
            new ConcurrentHashMap<>();
    private AutoCloseable registration;

    MinestomTransport(MinestomPacketEvents api) {
        this.api = api;
    }

    public synchronized void start() {
        if (registration == null) registration = PacketHooks.register(this);
    }

    MinestomConnection find(PlayerConnection connection) {
        return connections.get(connection);
    }

    public int connections() {
        return connections.size();
    }

    @Override
    public void existingConnection(Object value) {
        ((PlayerSocketConnection) value).disconnect();
    }

    @Override
    public void connected(Object value) {
        PlayerSocketConnection nativeConnection = (PlayerSocketConnection) value;
        MinestomConnection connection = new MinestomConnection(api, nativeConnection);
        connections.put(nativeConnection, connection);
        api.getProtocolManager().setUser(connection, connection.user);
        UserConnectEvent event = new UserConnectEvent(connection.user);
        api.getEventManager().callEvent(event);
        if (event.isCancelled()) nativeConnection.disconnect();
    }

    @Override
    public Object receive(Object value, Object info, Object buffer) {
        MinestomConnection connection = connections.get(value);
        if (connection == null) {
            ((PlayerSocketConnection) value).disconnect();
            return null;
        }
        return connection.read((PacketRegistry.PacketInfo<?>) info, (NetworkBuffer) buffer);
    }

    @Override
    public void sent(
            Object value,
            Object packet,
            Object buffer,
            long start,
            Object state,
            boolean compressed) {
        MinestomConnection connection = connections.get(value);
        if (connection != null)
            connection.sent(
                    packet,
                    (NetworkBuffer) buffer,
                    start,
                    (net.minestom.server.network.ConnectionState) state,
                    compressed);
    }

    @Override
    public void disconnected(Object value) {
        MinestomConnection connection = connections.remove(value);
        if (connection != null)
            connection.run(
                    () -> {
                        PacketEventsImplHelper.handleDisconnection(
                                connection, connection.user.getUUID());
                        connection.close();
                    });
    }

    @Override
    public synchronized void close() {
        if (registration != null) {
            try {
                registration.close();
            } catch (Exception error) {
                throw new IllegalStateException("Could not detach the packet transport", error);
            }
            registration = null;
        }
        // Reconnection is required after an engine replacement; no synthetic world history.
        for (PlayerSocketConnection connection : List.copyOf(connections.keySet())) {
            connection.disconnect();
            disconnected(connection);
        }
    }
}
