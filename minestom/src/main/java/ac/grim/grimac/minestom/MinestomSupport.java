package ac.grim.grimac.minestom;

import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerFlag;
import net.minestom.server.ServerProcess;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventListener;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerPacketEvent;
import net.minestom.server.event.player.PlayerPacketOutEvent;
import net.minestom.server.network.PlayerProvider;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.network.packet.client.common.ClientCookieResponsePacket;
import net.minestom.server.network.packet.client.common.ClientKeepAlivePacket;
import net.minestom.server.network.packet.client.common.ClientPingRequestPacket;

/** Ordinary Minestom extension setup. Keep this library in the host's classloader. */
public final class MinestomSupport {
    private static ServerProcess installed;
    private static final ThreadLocal<Boolean> DELIVERING_IMMEDIATE = new ThreadLocal<>();

    private MinestomSupport() {}

    /** Call before initializing Minestom (or loading ServerFlag). */
    public static void prepare() {
        System.setProperty("minestom.viewable-packet", "false");
        // Do not read ServerFlag here: that would freeze every other Minestom setting before
        // the host finishes configuring it. install() validates the value after native init.
    }

    /** Call after MinecraftServer.init(), before accepting players. */
    public static void install() {
        install(GrimPlayer::new);
    }

    /** Custom providers must create GrimPlayer instances or subclasses. */
    public static synchronized void install(PlayerProvider provider) {
        if (ServerFlag.VIEWABLE_PACKET)
            throw new IllegalStateException(
                    "Call MinestomSupport.prepare() before Minestom initializes");
        ServerProcess process = MinecraftServer.process();
        if (process == null || process.isAlive())
            throw new IllegalStateException(
                    "Install Grim support after init and before server startup");
        if (installed == process)
            throw new IllegalStateException("Grim support is already installed");
        MinecraftServer.getConnectionManager()
                .setPlayerProvider(
                        (connection, profile) -> {
                            var player = provider.createPlayer(connection, profile);
                            if (!(player instanceof GrimPlayer)) {
                                connection.disconnect();
                                throw new IllegalStateException(
                                        "The Grim player provider must create GrimPlayer"
                                                + " instances");
                            }
                            try {
                                PacketBridge.connected(player);
                            } catch (Throwable error) {
                                connection.disconnect();
                                PacketBridge.disconnected(player);
                                throw error;
                            }
                            return player;
                        });
        // Run after the host's ordinary packet listeners, including their cancellation decisions.
        var events = EventNode.all("grim-native-transport").setPriority(Integer.MAX_VALUE);
        events.addListener(
                EventListener.builder(PlayerPacketOutEvent.class)
                        .ignoreCancelled(false)
                        .handler(PacketBridge::outgoing)
                        .build());
        events.addListener(
                PlayerDisconnectEvent.class, event -> PacketBridge.disconnected(event.getPlayer()));
        events.addListener(
                AsyncPlayerConfigurationEvent.class,
                event -> {
                    if (PacketBridge.active() && !(event.getPlayer() instanceof GrimPlayer)) {
                        event.getPlayer().getPlayerConnection().disconnect();
                        throw new IllegalStateException(
                                "Grim's player provider was replaced with an incompatible"
                                        + " provider");
                    }
                });
        events.addListener(
                PlayerPacketEvent.class,
                event -> {
                    // These are handled on the socket thread and never pass through
                    // Player.addPacketToQueue.
                    var packet = event.getPacket();
                    if (!event.isCancelled()
                            && DELIVERING_IMMEDIATE.get() == null
                            && immediate(packet)) {
                        var accepted = PacketBridge.receive(event.getPlayer(), packet);
                        if (accepted == null) event.setCancelled(true);
                        else if (accepted != packet) {
                            // The event has no setter. Deliver a rewrite to the native listener
                            // once.
                            event.setCancelled(true);
                            ((GrimPlayer) event.getPlayer()).enqueueAccepted(accepted);
                        }
                    }
                });
        MinecraftServer.getGlobalEventHandler().addChild(events);
        installed = process;
    }

    static boolean immediate(ClientPacket packet) {
        return packet instanceof ClientKeepAlivePacket
                || packet instanceof ClientCookieResponsePacket
                || packet instanceof ClientPingRequestPacket;
    }

    static void deliverImmediate(Player player, ClientPacket packet) {
        DELIVERING_IMMEDIATE.set(true);
        try {
            MinecraftServer.getPacketListenerManager()
                    .processClientPacket(packet, player.getPlayerConnection());
        } finally {
            DELIVERING_IMMEDIATE.remove();
        }
    }

    public static synchronized void requireInstalled() {
        if (installed == null
                || installed != MinecraftServer.process()
                || ServerFlag.VIEWABLE_PACKET)
            throw new IllegalStateException("Install MinestomSupport before starting Grim");
    }
}
