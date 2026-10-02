package ac.grim.grimac.minestom;

import io.netty.buffer.ByteBuf;

import org.jetbrains.annotations.Nullable;

/**
 * Optional raw-packet integration for a translating {@code PlayerSocketConnection} subclass. Grim
 * discovers this interface on the player's connection; ordinary Minestom connections continue to
 * use the native packet bridge. This interface belongs in the host classloader.
 *
 * <p>Buffers contain a VarInt packet ID and payload, without framing, compression or encryption.
 * Native states on the connection must describe the packet currently being dispatched. The
 * transport must preserve packet order, including reentrant sends from Grim's listeners.
 */
public interface PacketTransport {
    /** Return the original client protocol, even when the transport translates to Minestom. */
    int getProtocolVersion();

    /**
     * Run synchronously on the connection's event loop; run inline when already on that loop. Must
     * return only after the action completes, so Grim's transaction counters remain ordered.
     */
    void runOnEventLoop(Runnable action);

    /**
     * Replace the single Grim listener on the event loop. Passing {@code null} detaches it and
     * waits for in-flight callbacks to finish. Keep the loop available until Grim has detached.
     */
    void installPacketListener(@Nullable Listener listener);

    /**
     * Send a Minestom-protocol packet, taking ownership of and releasing the buffer on all paths.
     * Run outgoing listeners synchronously before returning. {@code silent} bypasses Grim's
     * listener, while preserving ordinary Minestom events and outbound protocol translation.
     */
    void sendRaw(ByteBuf packet, boolean silent);

    /**
     * Inject a Minestom-protocol packet after inbound translation, taking ownership of and
     * releasing the buffer on all paths. Unless {@code silent}, invoke the listener's translated
     * receive stage synchronously before delivering it to Minestom.
     */
    void receiveRaw(ByteBuf packet, boolean silent);

    /** Callbacks run on the event loop and borrow the buffer; clearing it cancels the packet. */
    interface Listener {
        /**
         * Call before translation with the original client bytes, then after translation with
         * native bytes. Respect cancellation and rewrites at each stage before continuing.
         */
        void receive(ByteBuf packet, boolean beforeTranslation);

        /**
         * Call once for each outgoing native packet, before translation and after ordinary Minestom
         * cancellation listeners, including packets from buffered batches. Run the returned action
         * on the event loop after submitting an accepted packet for writing; discard it when the
         * packet is cancelled. The callback does not own the buffer.
         */
        @Nullable
        Runnable send(ByteBuf packet);
    }
}
