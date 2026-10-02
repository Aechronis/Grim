package ac.grim.grimac.platform.minestom.packet;

import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.EventManager;
import com.github.retrooper.packetevents.event.ProtocolPacketEvent;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.netty.channel.ChannelOperator;
import com.github.retrooper.packetevents.protocol.ProtocolVersion;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;

import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl;

import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;

public final class MinestomPacketEvents extends PacketEventsAPI<MinestomTransport> {
    private final MinestomEventManager events = new MinestomEventManager();
    private final MinestomTransport transport = new MinestomTransport(this);
    private final ServerVersion version = ServerVersion.getById(MinecraftServer.PROTOCOL_VERSION);
    private boolean loaded, initialized, terminated;
    private final ProtocolManager protocol =
            new ProtocolManager() {
                public ProtocolVersion getPlatformVersion() {
                    return ProtocolVersion.UNKNOWN;
                }

                public void sendPacket(Object channel, Object buffer) {
                    ((MinestomConnection) channel).write(buffer, false);
                }

                public void writePacket(Object channel, Object buffer) {
                    sendPacket(channel, buffer);
                }

                public void sendPacketSilently(Object channel, Object buffer) {
                    ((MinestomConnection) channel).write(buffer, true);
                }

                public void writePacketSilently(Object channel, Object buffer) {
                    sendPacketSilently(channel, buffer);
                }

                public void receivePacket(Object channel, Object buffer) {
                    ((MinestomConnection) channel).receive(buffer, false);
                }

                public void receivePacketSilently(Object channel, Object buffer) {
                    ((MinestomConnection) channel).receive(buffer, true);
                }

                public ClientVersion getClientVersion(Object channel) {
                    return ((MinestomConnection) channel).user.getClientVersion();
                }
            };
    private final PlayerManager players =
            new PlayerManager() {
                public int getPing(Object player) {
                    return ((Player) player).getLatency();
                }

                public ClientVersion getClientVersion(Object player) {
                    User user = getUser(player);
                    return user == null ? version.toClientVersion() : user.getClientVersion();
                }

                public Object getChannel(Object player) {
                    return transport.find(((Player) player).getPlayerConnection());
                }

                public User getUser(Object player) {
                    MinestomConnection connection = (MinestomConnection) getChannel(player);
                    return connection == null ? null : connection.user;
                }
            };
    private final NettyManager netty =
            new NettyManagerImpl() {
                private final ChannelOperator channels = new MinestomChannelOperator();

                @Override
                public ChannelOperator getChannelOperator() {
                    return channels;
                }
            };
    private final ChannelInjector injector =
            new ChannelInjector() {
                public void inject() {
                    transport.start();
                }

                public void uninject() {
                    transport.close();
                }

                public void updateUser(Object channel, User user) {}

                public void setPlayer(Object channel, Object player) {
                    ((MinestomConnection) channel).login((Player) player);
                }

                public boolean isPlayerSet(Object channel) {
                    return ((MinestomConnection) channel).loggedIn;
                }

                public boolean isProxy() {
                    return false;
                }
            };

    @Override
    public EventManager getEventManager() {
        return events;
    }

    void callPreTranslationEvent(ProtocolPacketEvent event, Runnable afterListener) {
        events.callPreTranslationEvent(event, afterListener);
    }

    @Override
    public void load() {
        super.load();
        loaded = true;
    }

    @Override
    public void init() {
        initialized = true;
    }

    /** Activate only after Grim has finished registering every check and manager. */
    public void activate() {
        if (!initialized) init();
        transport.start();
    }

    @Override
    public void terminate() {
        // Do not let the upstream best-effort uninject swallow a failed callback drain.
        transport.close();
        super.terminate();
        ProtocolManager.CHANNELS.clear();
        ProtocolManager.USERS.clear();
        terminated = true;
    }

    public MinestomTransport transport() {
        return transport;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public boolean isTerminated() {
        return terminated;
    }

    public MinestomTransport getPlugin() {
        return transport;
    }

    public ServerManager getServerManager() {
        return () -> version;
    }

    public ProtocolManager getProtocolManager() {
        return protocol;
    }

    public PlayerManager getPlayerManager() {
        return players;
    }

    public NettyManager getNettyManager() {
        return netty;
    }

    public ChannelInjector getInjector() {
        return injector;
    }
}
