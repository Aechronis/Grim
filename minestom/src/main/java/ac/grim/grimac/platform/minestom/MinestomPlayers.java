package ac.grim.grimac.platform.minestom;

import ac.grim.grimac.platform.api.player.AbstractPlatformPlayerFactory;
import ac.grim.grimac.platform.api.player.OfflinePlatformPlayer;
import ac.grim.grimac.platform.api.player.PlatformPlayer;

import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

final class MinestomPlayers extends AbstractPlatformPlayerFactory<Player> {
    private final GrimMinestom runtime;

    MinestomPlayers(GrimMinestom runtime) {
        this.runtime = runtime;
    }

    protected Player getNativePlayer(UUID uuid) {
        return MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uuid);
    }

    protected Player getNativePlayer(String name) {
        return MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(name);
    }

    protected PlatformPlayer createPlatformPlayer(Player player) {
        return new MinestomPlayer(runtime, player);
    }

    protected UUID getPlayerUUID(Player player) {
        return player.getUuid();
    }

    protected Collection<Player> getNativeOnlinePlayers() {
        return MinecraftServer.getConnectionManager().getOnlinePlayers();
    }

    public OfflinePlatformPlayer getOfflineFromUUID(UUID uuid) {
        PlatformPlayer player = getFromUUID(uuid);
        return player != null ? player : new Offline(uuid, uuid.toString());
    }

    public OfflinePlatformPlayer getOfflineFromName(String name) {
        return getFromName(name);
    }

    public Collection<OfflinePlatformPlayer> getOfflinePlayers() {
        return List.copyOf(getOnlinePlayers());
    }

    private record Offline(UUID id, String name) implements OfflinePlatformPlayer {
        public UUID getUniqueId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public boolean isOnline() {
            return false;
        }
    }
}
