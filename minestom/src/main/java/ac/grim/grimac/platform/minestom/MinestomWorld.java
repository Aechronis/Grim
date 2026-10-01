package ac.grim.grimac.platform.minestom;

import ac.grim.grimac.platform.api.world.PlatformChunk;
import ac.grim.grimac.platform.api.world.PlatformWorld;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;

import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Instance;

import java.util.UUID;

final class MinestomWorld implements PlatformWorld {
    final Instance instance;

    MinestomWorld(Instance instance) {
        this.instance = instance;
    }

    public boolean isChunkLoaded(int x, int z) {
        return instance.isChunkLoaded(x, z);
    }

    public WrappedBlockState getBlockAt(int x, int y, int z) {
        return WrappedBlockState.getByGlobalId(
                PacketEvents.getAPI().getServerManager().getVersion().toClientVersion(),
                instance.getBlock(x, y, z).stateId());
    }

    public String getName() {
        return instance.getUuid().toString();
    }

    public UUID getUID() {
        return instance.getUuid();
    }

    public PlatformChunk getChunkAt(int x, int z) {
        var chunk = instance.getChunk(x, z);
        return chunk == null ? null : (bx, by, bz) -> chunk.getBlock(bx, by, bz).stateId();
    }

    public boolean isLoaded() {
        return MinecraftServer.getInstanceManager().getInstances().contains(instance);
    }
}
