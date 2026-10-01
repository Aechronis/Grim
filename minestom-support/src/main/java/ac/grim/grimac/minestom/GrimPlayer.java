package ac.grim.grimac.minestom;

import net.minestom.server.entity.Player;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.network.packet.client.play.ClientConfigurationAckPacket;
import net.minestom.server.network.packet.server.BufferedPacket;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.network.player.PlayerConnection;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Base for native players checked by Grim. No instrumentation or replacement socket is used. */
public class GrimPlayer extends Player {
    private volatile CompletableFuture<Void> configurationAcknowledgement;

    public GrimPlayer(PlayerConnection connection, GameProfile profile) {
        super(connection, profile);
    }

    @Override
    public final void addPacketToQueue(ClientPacket packet) {
        ClientPacket accepted = PacketBridge.receive(this, packet);
        if (accepted != null) enqueueAccepted(accepted);
    }

    /** For the adapter to deliver an already checked or explicitly silent packet exactly once. */
    public final void enqueueAccepted(ClientPacket packet) {
        if (MinestomSupport.immediate(packet)) MinestomSupport.deliverImmediate(this, packet);
        else if (packet instanceof ClientConfigurationAckPacket && PacketBridge.active()) {
            // Minestom queues this acknowledgement but decodes subsequent socket reads using
            // the state last processed by the tick. Let that tick consume the acknowledgement
            // before the reader decodes configuration replies from another TCP read. Earlier
            // gameplay packets stay ordered on the native queue and keep their native handlers.
            var acknowledged = new CompletableFuture<Void>();
            configurationAcknowledgement = acknowledged;
            super.addPacketToQueue(packet);
            try {
                acknowledged.get(10, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                getPlayerConnection().disconnect();
            } catch (java.util.concurrent.ExecutionException
                    | java.util.concurrent.TimeoutException error) {
                getPlayerConnection().disconnect();
                throw new IllegalStateException(
                        "Native configuration acknowledgement was not processed", error);
            } finally {
                configurationAcknowledgement = null;
            }
        } else super.addPacketToQueue(packet);
    }

    @Override
    public final void interpretPacketQueue() {
        super.interpretPacketQueue();
        var acknowledged = configurationAcknowledgement;
        if (acknowledged != null
                && getPlayerConnection().getClientState()
                        == net.minestom.server.network.ConnectionState.CONFIGURATION)
            acknowledged.complete(null);
    }

    @Override
    public final void sendPacket(SendablePacket packet) {
        if (packet instanceof BufferedPacket buffered && PacketBridge.buffered(this, buffered))
            return;
        super.sendPacket(packet);
    }

    @Override
    public final void sendPackets(SendablePacket... packets) {
        for (SendablePacket packet : packets) sendPacket(packet);
    }

    @Override
    public final void sendPackets(Collection<SendablePacket> packets) {
        for (SendablePacket packet : packets) sendPacket(packet);
    }
}
