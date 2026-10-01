package ac.grim.grimac.platform.minestom;

import ac.grim.grimac.api.plugin.GrimPlugin;
import ac.grim.grimac.minestom.CallbackSlot;
import ac.grim.grimac.platform.api.entity.GrimEntity;
import ac.grim.grimac.platform.api.scheduler.*;
import ac.grim.grimac.platform.api.world.PlatformWorld;
import ac.grim.grimac.utils.math.Location;

import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Entity;
import net.minestom.server.timer.Scheduler;
import net.minestom.server.timer.Task;
import net.minestom.server.timer.TaskSchedule;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

final class MinestomScheduler implements PlatformScheduler, AutoCloseable {
    private final ScheduledThreadPoolExecutor executor =
            new ScheduledThreadPoolExecutor(
                    2, Thread.ofPlatform().name("grim-minestom-worker-", 0).factory());
    private final Set<Handle> tasks = ConcurrentHashMap.newKeySet();
    private final ReentrantReadWriteLock callbacks = new ReentrantReadWriteLock(true);
    private volatile boolean closing;

    MinestomScheduler() {
        executor.setRemoveOnCancelPolicy(true);
    }

    void retireRemovedEntities() {
        for (Handle handle : tasks) {
            Runnable retired = handle.retireIfRemoved();
            if (retired != null) retired.run();
        }
    }

    private Handle handle(GrimPlugin plugin, Runnable action, boolean sync, boolean repeat) {
        if (closing) throw new RejectedExecutionException("Grim scheduler is stopping");
        Handle handle = new Handle(plugin, sync);
        handle.slot =
                new CallbackSlot(
                        () -> {
                            callbacks.readLock().lock();
                            Thread thread = Thread.currentThread();
                            ClassLoader previous = thread.getContextClassLoader();
                            try {
                                thread.setContextClassLoader(
                                        MinestomScheduler.class.getClassLoader());
                                if (!closing && !handle.cancelled) {
                                    Runnable retired = handle.retireIfRemoved();
                                    if (retired != null) retired.run();
                                    if (!handle.cancelled) action.run();
                                }
                            } finally {
                                thread.setContextClassLoader(previous);
                                if (!repeat) handle.cancel();
                                callbacks.readLock().unlock();
                            }
                        });
        tasks.add(handle);
        if (closing) handle.cancel();
        return handle;
    }

    private TaskHandle sync(
            Scheduler scheduler, GrimPlugin plugin, Runnable action, long delay, long period) {
        return sync(scheduler, plugin, action, delay, period, null, null);
    }

    private TaskHandle sync(
            Scheduler scheduler,
            GrimPlugin plugin,
            Runnable action,
            long delay,
            long period,
            Entity entity,
            Runnable retired) {
        Handle handle = handle(plugin, action, true, period > 0);
        handle.bindEntity(entity, retired);
        try {
            var builder =
                    scheduler
                            .buildTask(handle.slot)
                            .delay(TaskSchedule.tick(Math.toIntExact(Math.max(1, delay))));
            if (period > 0) builder.repeat(TaskSchedule.tick(Math.toIntExact(period)));
            Task task = builder.schedule();
            handle.bind(task::cancel);
            return handle;
        } catch (RuntimeException error) {
            handle.cancel();
            throw error;
        }
    }

    private TaskHandle async(
            GrimPlugin plugin, Runnable action, long delay, long period, TimeUnit unit) {
        Handle handle = handle(plugin, action, false, period > 0);
        try {
            Future<?> future =
                    period > 0
                            ? executor.scheduleAtFixedRate(handle.slot, delay, period, unit)
                            : executor.schedule(handle.slot, delay, unit);
            handle.bind(() -> future.cancel(false));
            return handle;
        } catch (RuntimeException error) {
            handle.cancel();
            throw error;
        }
    }

    private void cancel(GrimPlugin plugin) {
        tasks.stream().filter(task -> task.plugin == plugin).forEach(Handle::cancel);
    }

    private final class Handle implements TaskHandle {
        final GrimPlugin plugin;
        final boolean sync;
        volatile boolean cancelled;
        CallbackSlot slot;
        Runnable cancellation;
        Entity entity;
        Runnable retired;

        Handle(GrimPlugin plugin, boolean sync) {
            this.plugin = plugin;
            this.sync = sync;
        }

        synchronized void bind(Runnable cancellation) {
            if (cancelled) cancellation.run();
            else this.cancellation = cancellation;
        }

        synchronized void bindEntity(Entity entity, Runnable retired) {
            if (!cancelled) {
                this.entity = entity;
                this.retired = retired;
            }
        }

        synchronized Runnable retireIfRemoved() {
            if (cancelled || entity == null || !entity.isRemoved()) return null;
            Runnable callback = retired;
            cancel();
            return callback;
        }

        public boolean isSync() {
            return sync;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public synchronized void cancel() {
            cancelled = true;
            entity = null;
            retired = null;
            if (slot != null) slot.close();
            if (cancellation != null) {
                cancellation.run();
                cancellation = null;
            }
            tasks.remove(this);
        }
    }

    private final GlobalRegionScheduler global =
            new GlobalRegionScheduler() {
                public void execute(GrimPlugin plugin, Runnable task) {
                    run(plugin, task);
                }

                public TaskHandle run(GrimPlugin plugin, Runnable task) {
                    return runDelayed(plugin, task, 1);
                }

                public TaskHandle runDelayed(GrimPlugin plugin, Runnable task, long delay) {
                    return sync(MinecraftServer.getSchedulerManager(), plugin, task, delay, 0);
                }

                public TaskHandle runAtFixedRate(
                        GrimPlugin plugin, Runnable task, long delay, long period) {
                    return sync(MinecraftServer.getSchedulerManager(), plugin, task, delay, period);
                }

                public void cancel(GrimPlugin plugin) {
                    MinestomScheduler.this.cancel(plugin);
                }
            };
    private final AsyncScheduler asynchronous =
            new AsyncScheduler() {
                public TaskHandle runNow(GrimPlugin plugin, Runnable task) {
                    return async(plugin, task, 0, 0, TimeUnit.MILLISECONDS);
                }

                public TaskHandle runDelayed(
                        GrimPlugin plugin, Runnable task, long delay, TimeUnit unit) {
                    return async(plugin, task, delay, 0, unit);
                }

                public TaskHandle runAtFixedRate(
                        GrimPlugin plugin, Runnable task, long delay, long period, TimeUnit unit) {
                    return async(plugin, task, delay, period, unit);
                }

                public TaskHandle runAtFixedRate(
                        GrimPlugin plugin, Runnable task, long delay, long period) {
                    return async(plugin, task, delay * 50, period * 50, TimeUnit.MILLISECONDS);
                }

                public void cancel(GrimPlugin plugin) {
                    MinestomScheduler.this.cancel(plugin);
                }
            };
    private final EntityScheduler entities =
            new EntityScheduler() {
                private TaskHandle schedule(
                        GrimEntity entity,
                        GrimPlugin plugin,
                        Runnable action,
                        Runnable retired,
                        long delay,
                        long period) {
                    Entity nativeEntity = (Entity) entity.getNative();
                    if (nativeEntity.isRemoved()) {
                        if (retired != null) retired.run();
                        return null;
                    }
                    // Removed entities stop ticking their native scheduler. The global maintenance
                    // tick also retires their pending callbacks so they cannot retain old players.
                    return sync(
                            nativeEntity.scheduler(),
                            plugin,
                            action,
                            delay,
                            period,
                            nativeEntity,
                            retired);
                }

                public void execute(
                        GrimEntity entity,
                        GrimPlugin plugin,
                        Runnable task,
                        Runnable retired,
                        long delay) {
                    schedule(entity, plugin, task, retired, delay, 0);
                }

                public TaskHandle run(
                        GrimEntity entity, GrimPlugin plugin, Runnable task, Runnable retired) {
                    return schedule(entity, plugin, task, retired, 1, 0);
                }

                public TaskHandle runDelayed(
                        GrimEntity entity,
                        GrimPlugin plugin,
                        Runnable task,
                        Runnable retired,
                        long delay) {
                    return schedule(entity, plugin, task, retired, delay, 0);
                }

                public TaskHandle runAtFixedRate(
                        GrimEntity entity,
                        GrimPlugin plugin,
                        Runnable task,
                        Runnable retired,
                        long delay,
                        long period) {
                    return schedule(entity, plugin, task, retired, delay, period);
                }
            };
    private final RegionScheduler regions =
            new RegionScheduler() {
                private Scheduler scheduler(PlatformWorld world) {
                    return ((MinestomWorld) world).instance.scheduler();
                }

                public void execute(
                        GrimPlugin plugin, PlatformWorld world, int x, int z, Runnable task) {
                    run(plugin, world, x, z, task);
                }

                public void execute(GrimPlugin plugin, Location location, Runnable task) {
                    run(plugin, location, task);
                }

                public TaskHandle run(
                        GrimPlugin plugin, PlatformWorld world, int x, int z, Runnable task) {
                    return sync(scheduler(world), plugin, task, 1, 0);
                }

                public TaskHandle run(GrimPlugin plugin, Location location, Runnable task) {
                    return run(plugin, location.getWorld(), 0, 0, task);
                }

                public TaskHandle runDelayed(
                        GrimPlugin plugin,
                        PlatformWorld world,
                        int x,
                        int z,
                        Runnable task,
                        long delay) {
                    return sync(scheduler(world), plugin, task, delay, 0);
                }

                public TaskHandle runDelayed(
                        GrimPlugin plugin, Location location, Runnable task, long delay) {
                    return runDelayed(plugin, location.getWorld(), 0, 0, task, delay);
                }

                public TaskHandle runAtFixedRate(
                        GrimPlugin plugin,
                        PlatformWorld world,
                        int x,
                        int z,
                        Runnable task,
                        long delay,
                        long period) {
                    return sync(scheduler(world), plugin, task, delay, period);
                }

                public TaskHandle runAtFixedRate(
                        GrimPlugin plugin,
                        Location location,
                        Runnable task,
                        long delay,
                        long period) {
                    return runAtFixedRate(plugin, location.getWorld(), 0, 0, task, delay, period);
                }
            };

    public GlobalRegionScheduler getGlobalRegionScheduler() {
        return global;
    }

    public AsyncScheduler getAsyncScheduler() {
        return asynchronous;
    }

    public EntityScheduler getEntityScheduler() {
        return entities;
    }

    public RegionScheduler getRegionScheduler() {
        return regions;
    }

    @Override
    public void close() {
        closing = true;
        tasks.forEach(Handle::cancel);
        executor.shutdown();
        try {
            if (!callbacks.writeLock().tryLock(15, TimeUnit.SECONDS))
                throw new IllegalStateException("Grim callbacks did not drain");
            callbacks.writeLock().unlock();
            if (!executor.awaitTermination(15, TimeUnit.SECONDS))
                throw new IllegalStateException("Grim workers did not stop");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted stopping Grim", error);
        }
    }
}
