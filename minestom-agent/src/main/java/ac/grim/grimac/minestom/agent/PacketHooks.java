package ac.grim.grimac.minestom.agent;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Stable, classloader-neutral boundary. The agent never retains a retired engine. */
public final class PacketHooks {
    public interface Listener {
        void connected(Object connection);

        void existingConnection(Object connection);

        Object receive(Object connection, Object packetInfo, Object buffer);

        void sent(
                Object connection,
                Object packet,
                Object buffer,
                long start,
                Object state,
                boolean compressed);

        void disconnected(Object connection);
    }

    private static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock(true);
    private static final Set<Object> CONNECTIONS = ConcurrentHashMap.newKeySet();
    private static Listener listener;

    private PacketHooks() {}

    public static AutoCloseable register(Listener next) {
        MinestomAgent.requireInstalled();
        LOCK.writeLock().lock();
        try {
            if (listener != null)
                throw new IllegalStateException("A packet transport is already registered");
            // Their earlier packet history is unavailable. Never silently exempt live players.
            for (Object connection : Set.copyOf(CONNECTIONS)) next.existingConnection(connection);
            listener = next;
        } finally {
            LOCK.writeLock().unlock();
        }
        return () -> {
            if (!LOCK.writeLock().tryLock(Duration.ofSeconds(15).toNanos(), TimeUnit.NANOSECONDS)) {
                throw new IllegalStateException(
                        "Minestom packet callbacks did not stop within 15 seconds");
            }
            try {
                if (listener == next) listener = null;
            } finally {
                LOCK.writeLock().unlock();
            }
        };
    }

    public static void connected(Object connection) {
        LOCK.readLock().lock();
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            if (listener != null)
                thread.setContextClassLoader(listener.getClass().getClassLoader());
            CONNECTIONS.add(connection);
            if (listener != null) listener.connected(connection);
        } finally {
            thread.setContextClassLoader(previous);
            LOCK.readLock().unlock();
        }
    }

    public static Object receive(Object connection, Object packetInfo, Object buffer) {
        LOCK.readLock().lock();
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            if (listener != null)
                thread.setContextClassLoader(listener.getClass().getClassLoader());
            return listener == null ? buffer : listener.receive(connection, packetInfo, buffer);
        } finally {
            thread.setContextClassLoader(previous);
            LOCK.readLock().unlock();
        }
    }

    public static void sent(
            Object connection,
            Object packet,
            Object buffer,
            long start,
            Object state,
            boolean compressed) {
        LOCK.readLock().lock();
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            if (listener != null)
                thread.setContextClassLoader(listener.getClass().getClassLoader());
            if (listener != null)
                listener.sent(connection, packet, buffer, start, state, compressed);
        } finally {
            thread.setContextClassLoader(previous);
            LOCK.readLock().unlock();
        }
    }

    public static void disconnected(Object connection) {
        LOCK.readLock().lock();
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            if (listener != null)
                thread.setContextClassLoader(listener.getClass().getClassLoader());
            if (CONNECTIONS.remove(connection) && listener != null)
                listener.disconnected(connection);
        } finally {
            thread.setContextClassLoader(previous);
            LOCK.readLock().unlock();
        }
    }
}
