package ac.grim.grimac.platform.minestom;

import ac.grim.grimac.platform.api.entity.GrimEntity;
import ac.grim.grimac.platform.api.world.PlatformWorld;
import ac.grim.grimac.utils.math.Location;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.LivingEntity;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

class MinestomEntity implements GrimEntity {
    final GrimMinestom runtime;
    final Entity entity;

    MinestomEntity(GrimMinestom runtime, Entity entity) {
        this.runtime = runtime;
        this.entity = entity;
    }

    public UUID getUniqueId() {
        return entity.getUuid();
    }

    public Object getNative() {
        return entity;
    }

    public boolean eject() {
        var passengers = List.copyOf(entity.getPassengers());
        passengers.forEach(entity::removePassenger);
        return !passengers.isEmpty();
    }

    public CompletableFuture<Boolean> teleportAsync(Location location) {
        Pos position =
                new Pos(
                        location.getX(),
                        location.getY(),
                        location.getZ(),
                        location.getYaw(),
                        location.getPitch());
        if (location.getWorld() instanceof MinestomWorld world
                && world.instance != entity.getInstance()) {
            return entity.setInstance(world.instance, position).thenApply(ignored -> true);
        }
        return entity.teleport(position).thenApply(ignored -> true);
    }

    public boolean isDead() {
        return entity.isRemoved() || entity instanceof LivingEntity living && living.isDead();
    }

    public PlatformWorld getWorld() {
        return runtime.world(entity.getInstance());
    }

    public Location getLocation() {
        Pos pos = entity.getPosition();
        return new Location(getWorld(), pos.x(), pos.y(), pos.z(), pos.yaw(), pos.pitch());
    }

    public double distanceSquared(double x, double y, double z) {
        return entity.getPosition().distanceSquared(x, y, z);
    }
}
