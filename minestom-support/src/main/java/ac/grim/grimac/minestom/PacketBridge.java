package ac.grim.grimac.minestom;

import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerPacketOutEvent;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.network.packet.server.BufferedPacket;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Detachable boundary between host-owned Minestom extensions and a reloadable Grim engine. */
public final class PacketBridge {
    public interface Listener {
        void connected(Player player);

        ClientPacket receive(Player player, ClientPacket packet);

        void outgoing(PlayerPacketOutEvent event);

        void buffered(Player player, BufferedPacket packet);

        void disconnected(Player player);
    }

    private static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock(true);
    private static final Set<Player> PLAYERS = ConcurrentHashMap.newKeySet();
    private static volatile Listener listener;

    private PacketBridge() {}

    public static boolean active() {
        return listener != null;
    }

    /** Whether raw player sends will dispatch native events on the writer, before Grim checks. */
    public static boolean handlesBufferedPackets(Player player) {
        return player instanceof GrimPlayer && active();
    }

    static boolean buffered(Player player, BufferedPacket packet) {
        return call(
                current -> {
                    if (current == null) return false;
                    current.buffered(player, packet);
                    return true;
                });
    }

    public static AutoCloseable register(Listener next) {
        MinestomSupport.requireInstalled();
        LOCK.writeLock().lock();
        try {
            if (listener != null)
                throw new IllegalStateException("A Grim transport is already registered");
            for (Player player : Set.copyOf(PLAYERS)) player.getPlayerConnection().disconnect();
            PLAYERS.clear();
            listener = next;
        } finally {
            LOCK.writeLock().unlock();
        }
        return () -> {
            if (!LOCK.writeLock().tryLock(Duration.ofSeconds(15).toNanos(), TimeUnit.NANOSECONDS))
                throw new IllegalStateException(
                        "Minestom callbacks did not stop within 15 seconds");
            try {
                if (listener == next) listener = null;
            } finally {
                LOCK.writeLock().unlock();
            }
        };
    }

    static void connected(Player player) {
        call(
                current -> {
                    PLAYERS.add(player);
                    if (current != null) current.connected(player);
                    return null;
                });
    }

    static ClientPacket receive(Player player, ClientPacket packet) {
        return call(current -> current == null ? packet : current.receive(player, packet));
    }

    static void outgoing(PlayerPacketOutEvent event) {
        call(
                current -> {
                    if (current != null) current.outgoing(event);
                    return null;
                });
    }

    static void disconnected(Player player) {
        call(
                current -> {
                    if (PLAYERS.remove(player) && current != null) current.disconnected(player);
                    return null;
                });
    }

    private static <T> T call(java.util.function.Function<Listener, T> action) {
        LOCK.readLock().lock();
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            Listener current = listener;
            if (current != null) thread.setContextClassLoader(current.getClass().getClassLoader());
            return action.apply(current);
        } finally {
            thread.setContextClassLoader(previous);
            LOCK.readLock().unlock();
        }
    }
}
