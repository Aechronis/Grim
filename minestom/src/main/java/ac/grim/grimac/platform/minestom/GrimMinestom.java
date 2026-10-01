package ac.grim.grimac.platform.minestom;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.GrimAPIProvider;
import ac.grim.grimac.api.GrimAbstractAPI;
import ac.grim.grimac.api.plugin.BasicGrimPlugin;
import ac.grim.grimac.api.plugin.GrimPlugin;
import ac.grim.grimac.minestom.agent.MinestomAgent;
import ac.grim.grimac.platform.api.*;
import ac.grim.grimac.platform.api.command.CommandService;
import ac.grim.grimac.platform.api.manager.*;
import ac.grim.grimac.platform.api.permissions.PermissionDefaultValue;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.platform.api.player.PlatformPlayerFactory;
import ac.grim.grimac.platform.api.scheduler.PlatformScheduler;
import ac.grim.grimac.platform.api.sender.Sender;
import ac.grim.grimac.platform.api.sender.SenderFactory;
import ac.grim.grimac.platform.minestom.packet.MinestomPacketEvents;

import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;

import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.instance.Instance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Embeds upstream Grim in a stock Minestom server. Start after MinecraftServer.init(), before
 * accepting connections. Close before stopping Minestom. Full engine replacement requires a fresh
 * classloader and reconnects existing clients; configuration reload keeps their tracking intact.
 */
public final class GrimMinestom implements PlatformLoader, AutoCloseable {
    @FunctionalInterface
    public interface PermissionChecker {
        boolean hasPermission(CommandSender sender, String permission, boolean defaultIfUnset);
    }

    public static final class Builder {
        private final Path dataDirectory;
        private PermissionChecker permissions =
                (sender, permission, fallback) -> !(sender instanceof Player) || fallback;
        private Consumer<Command> register =
                command -> MinecraftServer.getCommandManager().register(command);
        private Consumer<Command> unregister =
                command -> MinecraftServer.getCommandManager().unregister(command);
        private BiConsumer<String, PermissionDefaultValue> permissionRegistration =
                (name, value) -> {};

        private Builder(Path directory) {
            dataDirectory = Objects.requireNonNull(directory);
        }

        public Builder permissions(PermissionChecker checker) {
            permissions = Objects.requireNonNull(checker);
            return this;
        }

        public Builder commands(Consumer<Command> register, Consumer<Command> unregister) {
            this.register = Objects.requireNonNull(register);
            this.unregister = Objects.requireNonNull(unregister);
            return this;
        }

        public Builder permissionRegistration(
                BiConsumer<String, PermissionDefaultValue> registration) {
            permissionRegistration = Objects.requireNonNull(registration);
            return this;
        }

        public GrimMinestom build() {
            return new GrimMinestom(this);
        }
    }

    public static Builder builder(Path directory) {
        return new Builder(directory);
    }

    private static final AtomicBoolean USED = new AtomicBoolean();
    private final Builder options;
    private final MinestomScheduler scheduler = new MinestomScheduler();
    private final MinestomPacketEvents packets = new MinestomPacketEvents();
    private final MinestomPlayers players = new MinestomPlayers(this);
    private final MinestomSenders senders = new MinestomSenders(this);
    private final MinestomCommands commands = new MinestomCommands(this);
    private final ConcurrentHashMap<Instance, MinestomWorld> worlds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PermissionDefaultValue> permissions =
            new ConcurrentHashMap<>();
    private final GrimPlugin plugin;
    private GrimAPI engine;
    private volatile double tps = net.minestom.server.ServerFlag.SERVER_TICKS_PER_SECOND;
    private boolean started, closed;

    private GrimMinestom(Builder options) {
        this.options = options;
        plugin =
                new BasicGrimPlugin(
                        Logger.getLogger("GrimAC"),
                        options.dataDirectory.toFile(),
                        version(),
                        "GrimAC for Minestom",
                        List.of("GrimAC contributors"));
    }

    private static String version() {
        var properties = new java.util.Properties();
        try (var stream = GrimMinestom.class.getResourceAsStream("/grimac.properties")) {
            if (stream != null) properties.load(stream);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot read Grim version", error);
        }
        return properties.getProperty("build.version", "unknown");
    }

    public synchronized void start() {
        if (started || closed)
            throw new IllegalStateException("This Grim runtime has already been started or closed");
        MinestomAgent.requireInstalled();
        if (MinecraftServer.PROTOCOL_VERSION != 776)
            throw new IllegalStateException("This adapter targets Minestom 26.2 (protocol 776)");
        if (!USED.compareAndSet(false, true))
            throw new IllegalStateException(
                    "Full Grim replacement requires a fresh classloader; use api().reloadAsync()"
                        + " for configuration");
        try {
            Files.createDirectories(options.dataDirectory);
            engine = GrimAPI.INSTANCE;
            engine.getExtensionManager()
                    .registerResolver(
                            value ->
                                    value == this || value == plugin || "GrimAC".equals(value)
                                            ? plugin
                                            : null);
            GrimAPI.INSTANCE.load(this);
            GrimAPI.INSTANCE.start();
            // CommandRegister is best-effort upstream. Make command failures visible to embedded
            // hosts.
            commands.register();
            long[] lastTick = {System.nanoTime()};
            scheduler
                    .getGlobalRegionScheduler()
                    .runAtFixedRate(
                            plugin,
                            () -> {
                                long now = System.nanoTime();
                                tps =
                                        Math.min(
                                                net.minestom.server.ServerFlag
                                                        .SERVER_TICKS_PER_SECOND,
                                                20_000_000_000.0 / Math.max(1, now - lastTick[0]));
                                lastTick[0] = now;
                                scheduler.retireRemovedEntities();
                            },
                            20,
                            20);
            packets.activate();
            started = true;
        } catch (Exception | LinkageError error) {
            try {
                close();
            } catch (Exception | LinkageError cleanup) {
                error.addSuppressed(cleanup);
            }
            throw new IllegalStateException("Could not start Grim for Minestom", error);
        }
    }

    public GrimAbstractAPI api() {
        if (!started || closed) throw new IllegalStateException("Grim is not running");
        return GrimAPI.INSTANCE.getExternalAPI();
    }

    public int trackedPlayers() {
        return GrimAPI.INSTANCE.getPlayerDataManager().size();
    }

    /** Stops packet input and background callbacks before the host saves or unloads modules. */
    public synchronized void quiesce() {
        commands.close();
        packets.transport().close();
        scheduler.close();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        quiesce();
        if (engine != null && engine.getInitManager() != null) engine.getInitManager().stop();
        for (var client : ac.grim.grimac.utils.anticheat.GrimHttpClients.releaseClients()) {
            client.shutdownNow();
            try {
                if (!client.awaitTermination(java.time.Duration.ofSeconds(15))) {
                    throw new IllegalStateException("Grim HTTP client did not stop");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted stopping Grim HTTP client", error);
            }
        }
        // DriverManager is JVM-global. Only retire drivers from an isolated engine loader;
        // a directly embedded application may still use its shared JDBC drivers elsewhere.
        ClassLoader loader = getClass().getClassLoader();
        if (loader != MinecraftServer.class.getClassLoader()) {
            try {
                for (var drivers = java.sql.DriverManager.getDrivers();
                        drivers.hasMoreElements(); ) {
                    var driver = drivers.nextElement();
                    if (driver.getClass().getClassLoader() == loader)
                        java.sql.DriverManager.deregisterDriver(driver);
                }
            } catch (java.sql.SQLException error) {
                throw new IllegalStateException("Could not deregister Grim JDBC drivers", error);
            }
        }
        for (PlatformPlayer player : players.getOnlinePlayers())
            players.invalidatePlayer(player.getUniqueId());
        worlds.clear();
        started = false;
        closed = true;
    }

    MinestomWorld world(Instance instance) {
        return instance == null ? null : worlds.computeIfAbsent(instance, MinestomWorld::new);
    }

    MinestomSenders senders() {
        return senders;
    }

    boolean hasPermission(CommandSender sender, String name, boolean fallback) {
        PermissionDefaultValue value = permissions.get(name);
        boolean defaultValue =
                value == null
                        ? fallback
                        : value == PermissionDefaultValue.TRUE
                                || value == PermissionDefaultValue.NOT_OP;
        return options.permissions.hasPermission(sender, name, defaultValue);
    }

    void registerCommand(Command command) {
        options.register.accept(command);
    }

    void unregisterCommand(Command command) {
        options.unregister.accept(command);
    }

    @Override
    public boolean failOnLifecycleError() {
        return true;
    }

    public PlatformScheduler getScheduler() {
        return scheduler;
    }

    public PlatformPlayerFactory getPlatformPlayerFactory() {
        return players;
    }

    public PacketEventsAPI<?> getPacketEvents() {
        return packets;
    }

    public CommandService getCommandService() {
        return commands::register;
    }

    public SenderFactory<?> getSenderFactory() {
        return senders;
    }

    public GrimPlugin getPlugin() {
        return plugin;
    }

    public void registerAPIService() {
        GrimAPIProvider.init(GrimAPI.INSTANCE.getExternalAPI());
    }

    public PermissionRegistrationManager getPermissionManager() {
        return (name, value) -> {
            permissions.put(name, value);
            options.permissionRegistration.accept(name, value);
        };
    }

    public MessagePlaceHolderManager getMessagePlaceHolderManager() {
        return (player, message) -> message;
    }

    public ItemResetHandler getItemResetHandler() {
        return new ItemResetHandler() {
            public void resetItemUsage(PlatformPlayer platformPlayer) {
                if (platformPlayer == null) return;
                Player player = (Player) platformPlayer.getNative();
                player.clearItemUse();
                player.refreshActiveHand(false, false, false);
            }

            public InteractionHand getItemUsageHand(PlatformPlayer platformPlayer) {
                if (platformPlayer == null) return null;
                PlayerHand hand = ((Player) platformPlayer.getNative()).getItemUseHand();
                return hand == null
                        ? null
                        : hand == PlayerHand.MAIN
                                ? InteractionHand.MAIN_HAND
                                : InteractionHand.OFF_HAND;
            }

            public boolean isUsingItem(PlatformPlayer player) {
                return player != null && ((Player) player.getNative()).isUsingItem();
            }
        };
    }

    public PlatformPluginManager getPluginManager() {
        return new PlatformPluginManager() {
            private final PlatformPlugin self =
                    new PlatformPlugin() {
                        public boolean isEnabled() {
                            return !closed;
                        }

                        public String getName() {
                            return "GrimAC";
                        }

                        public String getVersion() {
                            return version();
                        }
                    };

            public PlatformPlugin[] getPlugins() {
                return new PlatformPlugin[] {self};
            }

            public PlatformPlugin getPlugin(String name) {
                return name.equalsIgnoreCase("GrimAC") ? self : null;
            }
        };
    }

    public PlatformServer getPlatformServer() {
        return new PlatformServer() {
            public String getPlatformImplementationString() {
                return "Minestom " + MinecraftServer.VERSION_NAME;
            }

            public void dispatchCommand(Sender sender, String command) {
                scheduler
                        .getGlobalRegionScheduler()
                        .execute(
                                plugin,
                                () ->
                                        MinecraftServer.getCommandManager()
                                                .execute(
                                                        (CommandSender) sender.getNativeSender(),
                                                        command));
            }

            public Sender getConsoleSender() {
                return senders.wrap(MinecraftServer.getCommandManager().getConsoleSender());
            }

            public void registerOutgoingPluginChannel(
                    String name) {} // Minestom has no plugin-channel registration requirement.

            public double getTPS() {
                return tps;
            }
        };
    }
}
