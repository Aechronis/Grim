package ac.grim.grimac.platform.minestom.packet;

import ac.grim.grimac.events.packets.PreViaCheckManagerListener;

import com.github.retrooper.packetevents.event.EventManager;
import com.github.retrooper.packetevents.event.PacketEvent;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.ProtocolPacketEvent;

/** Separates the raw pre-translation checks without changing native listener ordering. */
final class MinestomEventManager extends EventManager {
    private final EventManager preTranslation = new EventManager();
    private final EventManager translated = new EventManager();

    @Override
    public PacketListenerCommon registerListener(PacketListenerCommon listener) {
        super.registerListener(listener);
        // The pinned PacketEvents snapshot has no pre-Via dispatch API. Only Grim's raw
        // check manager is moved to the client-byte stage; the existing platform listeners
        // continue to consume Minestom-protocol events, as on the native transport.
        if (listener instanceof PreViaCheckManagerListener)
            preTranslation.registerListener(listener);
        else translated.registerListener(listener);
        return listener;
    }

    @Override
    public PacketListenerCommon[] registerListeners(PacketListenerCommon... listeners) {
        for (PacketListenerCommon listener : listeners) registerListener(listener);
        return listeners;
    }

    @Override
    public void unregisterListener(PacketListenerCommon listener) {
        super.unregisterListener(listener);
        preTranslation.unregisterListener(listener);
        translated.unregisterListener(listener);
    }

    @Override
    public void unregisterListeners(PacketListenerCommon... listeners) {
        for (PacketListenerCommon listener : listeners) unregisterListener(listener);
    }

    @Override
    public void unregisterAllListeners() {
        super.unregisterAllListeners();
        preTranslation.unregisterAllListeners();
        translated.unregisterAllListeners();
    }

    @Override
    public void callEvent(PacketEvent event, Runnable afterListener) {
        if (event instanceof ProtocolPacketEvent packet
                && packet.getChannel() instanceof TranslatedMinestomConnection) {
            translated.callEvent(event, afterListener);
        } else {
            // Native connections and lifecycle events still reach every listener once.
            super.callEvent(event, afterListener);
        }
    }

    void callPreTranslationEvent(ProtocolPacketEvent event, Runnable afterListener) {
        preTranslation.callEvent(event, afterListener);
    }
}
