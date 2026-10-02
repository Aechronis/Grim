package ac.grim.grimac.platform.minestom.packet;

import ac.grim.grimac.minestom.GrimPlayer;

import com.github.retrooper.packetevents.event.UserLoginEvent;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;

import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerPacketOutEvent;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.network.packet.server.BufferedPacket;
import net.minestom.server.network.player.PlayerSocketConnection;

abstract class MinestomConnection {
    final PlayerSocketConnection nativeConnection;
    final GrimPlayer player;
    final User user;
    final MinestomPacketEvents api;
    volatile boolean loggedIn;
    // Set only while constructing events for the client's untranslated incoming bytes.
    boolean clientEncoding;

    MinestomConnection(
            MinestomPacketEvents api, PlayerSocketConnection connection, GrimPlayer player) {
        this.api = api;
        this.nativeConnection = connection;
        this.player = player;
        this.user =
                new User(
                        this,
                        ConnectionState.CONFIGURATION,
                        ClientVersion.getById(connection.getProtocolVersion()),
                        new UserProfile(player.getUuid(), player.getUsername())) {
                    @Override
                    public ClientVersion getPacketVersion() {
                        return clientEncoding ? getClientVersion() : super.getPacketVersion();
                    }
                };
    }

    void login(Player player) {
        if (loggedIn) return;
        loggedIn = true;
        api.getEventManager().callEvent(new UserLoginEvent(user, player));
    }

    abstract void run(Runnable task);

    abstract void close();

    abstract ClientPacket read(ClientPacket packet);

    abstract void send(PlayerPacketOutEvent event);

    abstract void buffered(BufferedPacket packet);

    abstract void write(Object packet, boolean silent);

    abstract void receive(Object packet, boolean silent);

    final void fail(Exception error) {
        api.getLogManager()
                .warn("Minestom packet transport failed; closing the affected connection", error);
        nativeConnection.disconnect();
    }
}
