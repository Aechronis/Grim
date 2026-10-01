package ac.grim.grimac.platform.minestom;

import ac.grim.grimac.platform.api.entity.GrimEntity;
import ac.grim.grimac.platform.api.player.BlockTranslator;
import ac.grim.grimac.platform.api.player.PlatformInventory;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.platform.api.sender.Sender;

import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.util.Vector3d;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minestom.server.entity.Player;
import net.minestom.server.network.packet.server.common.PluginMessagePacket;

final class MinestomPlayer extends MinestomEntity implements PlatformPlayer {
    final Player player;

    MinestomPlayer(GrimMinestom runtime, Player player) {
        super(runtime, player);
        this.player = player;
    }

    public String getName() {
        return player.getUsername();
    }

    public boolean isOnline() {
        return player.isOnline();
    }

    public void kickPlayer(String reason) {
        player.kick(LegacyComponentSerializer.legacySection().deserialize(reason));
    }

    public void resyncSharedFlags() {
        player.sendPacket(player.getMetadataPacket());
    }

    public boolean hasPermission(String permission) {
        return runtime.hasPermission(player, permission, false);
    }

    public boolean hasPermission(String permission, boolean fallback) {
        return runtime.hasPermission(player, permission, fallback);
    }

    public void sendMessage(String message) {
        player.sendMessage(LegacyComponentSerializer.legacySection().deserialize(message));
    }

    public void sendMessage(Component message) {
        player.sendMessage(message);
    }

    public void updateInventory() {
        player.getInventory().update(player);
    }

    public Vector3d getPosition() {
        var pos = player.getPosition();
        return new Vector3d(pos.x(), pos.y(), pos.z());
    }

    public PlatformInventory getInventory() {
        return new MinestomInventory(player);
    }

    public GrimEntity getVehicle() {
        var vehicle = player.getVehicle();
        return vehicle == null ? null : new MinestomEntity(runtime, vehicle);
    }

    public GameMode getGameMode() {
        return GameMode.valueOf(player.getGameMode().name());
    }

    public void setGameMode(GameMode mode) {
        player.setGameMode(net.minestom.server.entity.GameMode.valueOf(mode.name()));
    }

    public boolean isExternalPlayer() {
        return false;
    }

    public void sendPluginMessage(String name, byte[] bytes) {
        player.sendPacket(new PluginMessagePacket(name, bytes));
    }

    public Sender getSender() {
        return runtime.senders().wrap(player);
    }

    public BlockTranslator getBlockTranslator() {
        return BlockTranslator.IDENTITY;
    }
}
