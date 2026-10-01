package ac.grim.grimac.platform.minestom.packet;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.minestom.GrimPlayer;
import ac.grim.grimac.minestom.PacketBridge;

import com.github.retrooper.packetevents.event.UserConnectEvent;
import com.github.retrooper.packetevents.util.PacketEventsImplHelper;

import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerPacketOutEvent;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.network.packet.server.BufferedPacket;
import net.minestom.server.network.player.PlayerConnection;
import net.minestom.server.network.player.PlayerSocketConnection;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class MinestomTransport implements PacketBridge.Listener, AutoCloseable {
    private final MinestomPacketEvents api;
    private final ConcurrentHashMap<PlayerConnection, MinestomConnection> connections =
            new ConcurrentHashMap<>();
    private AutoCloseable registration;

    MinestomTransport(MinestomPacketEvents api) {
        this.api = api;
    }

    public synchronized void start() {
        if (registration == null) registration = PacketBridge.register(this);
    }

    MinestomConnection find(PlayerConnection connection) {
        return connections.get(connection);
    }

    public int connections() {
        return connections.size();
    }

    @Override
    public void connected(Player player) {
        if (!(player instanceof GrimPlayer)
                || !(player.getPlayerConnection() instanceof PlayerSocketConnection socket)) {
            player.getPlayerConnection().disconnect();
            throw new IllegalStateException(
                    "Grim requires GrimPlayer with a native socket connection");
        }
        MinestomConnection connection = new MinestomConnection(api, socket, (GrimPlayer) player);
        connections.put(socket, connection);
        connection.run(
                () -> {
                    api.getProtocolManager().setUser(connection, connection.user);
                    UserConnectEvent event = new UserConnectEvent(connection.user);
                    api.getEventManager().callEvent(event);
                    if (event.isCancelled()) {
                        socket.disconnect();
                        disconnected(player);
                        return;
                    }
                    // The provider runs immediately after native login acknowledgement.
                    // Authentication
                    // has completed, and no configuration/world packets have been sent to this
                    // player.
                    GrimAPI.INSTANCE.getPlayerDataManager().addUser(connection.user);
                    GrimAPI.INSTANCE
                            .getDataStoreLifecycle()
                            .playerToggleStore()
                            .prefetch(player.getUuid());
                    connection.login(player);
                });
    }

    @Override
    public ClientPacket receive(Player player, ClientPacket packet) {
        MinestomConnection connection = connections.get(player.getPlayerConnection());
        if (connection == null) {
            player.getPlayerConnection().disconnect();
            return null;
        }
        return connection.read(packet);
    }

    @Override
    public void outgoing(PlayerPacketOutEvent event) {
        MinestomConnection connection = connections.get(event.getPlayer().getPlayerConnection());
        if (connection == null) {
            event.setCancelled(true);
            event.getPlayer().getPlayerConnection().disconnect();
            return;
        }
        connection.send(event);
    }

    @Override
    public void buffered(Player player, BufferedPacket packet) {
        MinestomConnection connection = connections.get(player.getPlayerConnection());
        if (connection == null) player.getPlayerConnection().disconnect();
        else connection.buffered(packet);
    }

    @Override
    public void disconnected(Player player) {
        MinestomConnection connection = connections.remove(player.getPlayerConnection());
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
        for (MinestomConnection connection : List.copyOf(connections.values())) {
            connection.nativeConnection.disconnect();
            disconnected(connection.player);
        }
    }
}
