# Grim for Minestom

Embed Grim's anticheat in a Minestom project using **Java 25** and **Minestom `2026.09.12-26.2`**.

## Dependencies

Add the following to your `build.gradle.kts`, replacing `<version>` with your Grim for Minestom release:

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("net.aechronis:grim-minestom:<version>")
    implementation("net.minestom:minestom:2026.09.12-26.2")
}
```

The Grim dependency includes the engine and native Minestom support. No Java agent is needed.

## Startup

Call `prepare()` before Minestom loads its settings, `install()` after initialization, and `grim.start()` before accepting players:

```java
import ac.grim.grimac.minestom.MinestomSupport;
import ac.grim.grimac.platform.minestom.GrimMinestom;
import net.minestom.server.MinecraftServer;

import java.nio.file.Path;

MinestomSupport.prepare();
MinecraftServer server = MinecraftServer.init();
MinestomSupport.install();

GrimMinestom grim = GrimMinestom.builder(Path.of("grim")).build();
grim.start();

// Configure your world and player spawning before starting the server.
server.start("0.0.0.0", 25565);
```

`Path.of("grim")` is Grim's configuration and data directory. `prepare()` disables Minestom's viewable-packet optimization so Grim can track outgoing packets.

## Integration

- **Custom players:** extend `ac.grim.grimac.minestom.GrimPlayer` and use `MinestomSupport.install(MyPlayer::new)` instead of `install()`. Install the provider once, before server startup.
- **Permissions:** optionally call `.permissions((sender, permission, defaultIfUnset) -> ...)` on the builder to connect your permission service. The default grants console access and uses Grim's declared permission defaults for players.
- **Commands:** `/grim` and `/grimac` are registered automatically. Player selectors resolve online players.
- **Buffered packets:** send raw `BufferedPacket` values through `Player.sendPacket` or `sendPackets`; direct `PlayerConnection.sendPacket(BufferedPacket)` bypasses tracking.
- **Storage:** SQLite is included. Supply the appropriate JDBC driver if you configure another database backend.

## Reload and shutdown

Use `/grim reload` or `grim.api().reloadAsync()` to reload configuration while retaining player tracking. Only one Grim runtime can be active; it cannot be restarted in the same classloader.

Call `grim.close()` before stopping Minestom. For staged shutdown, call `grim.quiesce()` to stop packet input and scheduled work, then `grim.close()` to finish cleanup.
