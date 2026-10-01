# Grim for Minestom

This platform adapter embeds Grim's existing checks in a stock Minestom server. It contains no application-specific checks or exemptions. It currently targets **Java 25** and **Minestom `2026.09.12-26.2` (protocol 776)**. Other Minestom versions require verification of the socket hooks and protocol mappings before updating the pin.

## Build and use

From this repository:

```sh
./gradlew -PminestomOnly=true :minestom:build :minestom-agent:shadowJar
```

For source dependencies, add this to your application's `settings.gradle.kts` (adjust the checkout path):

```kotlin
includeBuild("../Grim") {
    dependencySubstitution {
        substitute(module("ac.grim.grimac:grim-minestom")).using(project(":minestom"))
        substitute(module("ac.grim.grimac:grim-minestom-agent")).using(project(":minestom-agent"))
    }
}
```

Then add `implementation("ac.grim.grimac:grim-minestom:local")` to the application, alongside its Minestom dependency. The standalone startup agent supplies the agent classes. If embedding the agent into your executable JAR instead, also depend on `ac.grim.grimac:grim-minestom-agent:local`.

Composite builds include only the common engine and the Minestom projects by default. For Maven consumption, publish `:common`, `:minestom`, and `:minestom-agent` together at the same version to your repository. These coordinates are not an announced upstream release.

The library's runtime repositories are Maven Central, `https://maven.grim.ac/public/releases`, `https://repo.codemc.io/repository/maven-snapshots/`, and `https://nexus.scarsz.me/content/repositories/releases`. The Minestom adapter pins an upstream PacketEvents snapshot containing Adventure 5 support. The other Grim platforms retain their existing PacketEvents dependency.

Install the agent **before Minestom classes load**:

```sh
java -javaagent:/path/to/grim-minestom-agent.jar -jar your-server.jar
```

The standalone agent is `minestom-agent/build/libs/grim-minestom-agent.jar`. Alternatively, bundle the agent and its dependencies into your executable server JAR and set `Launcher-Agent-Class` to `ac.grim.grimac.minestom.agent.MinestomAgent`. This works with `java -jar`; IDE/classpath launches still need `-javaagent`. Keep the agent in the application classloader, outside any reloadable engine classloader.

After `MinecraftServer.init()` and before accepting players:

```java
GrimMinestom grim = GrimMinestom.builder(Path.of("grim"))
    .permissions((sender, permission, defaultIfUnset) ->
        permissionService.hasPermission(sender, permission, defaultIfUnset))
    .build();
grim.start();
```

The permission callback is optional. Its default grants console permissions and uses Grim's declared permission defaults for players. Provide your own permission service for staff access. Optional `commands(register, unregister)` and `permissionRegistration(...)` callbacks let a host track registrations; the defaults use Minestom's command manager directly. The standard `/grim` and `/grimac` commands are available. Minestom has no built-in offline-player directory, so player selectors resolve online players.

Call `grim.close()` before stopping Minestom. Hosts with a staged shutdown can call `grim.quiesce()` first to detach packet input and drain scheduled work, then `close()` to finish engine shutdown. Startup failures propagate to the host.

## Reloading

`/grim reload` (or `grim.api().reloadAsync()`) reloads upstream configuration while retaining player tracking. Replacing the engine requires closing the previous runtime and loading the library **and its engine dependencies in a fresh classloader**. There can be only one active runtime in a process and one startup per engine classloader.

Full engine replacement disconnects tracked players. Enabling the engine while players are already connected also disconnects them: their earlier world and transaction history cannot be reconstructed safely. Reconnecting gives each player a complete tracked session. Updating the startup agent requires restarting the server process.

## Packet transport

Minestom uses NIO socket threads rather than Netty pipelines. A small ASM startup agent adds callbacks at decrypted inbound decoding, successful outbound serialization before encryption, connection creation, and disconnection. This includes cached, framed, and buffered sends which ordinary outgoing packet events can miss.

The adapter translates those frames through PacketEvents, serializes each connection's callbacks, applies cancellation and rewrites, and keeps Grim's injected transactions in order with the surrounding packets. The native Minestom framing, encryption, socket ownership, and gameplay handling remain in place. Hook registration drains in-flight callbacks on detach; canceled native scheduled tasks retain only detachable callbacks from the agent classloader.

The initial supported transport is direct Java clients using Minestom's native protocol. Protocol translators, alternate socket implementations, and custom packet registries require separate integration work. SQLite is included for the default upstream storage configuration. Other database backends require their driver dependencies supplied by the host.

## Following upstream

Keep upstream changes in `common` limited to platform detection and embedded lifecycle integration; implement platform behavior here and in `minestom-agent`. The checks remain upstream code. Keep the original repository as the `upstream` Git remote and merge its `2.0` branch into your Minestom branch. After updating, verify startup, complete login, compression, chunk/block tracking, transaction ordering, cancellation, and shutdown/reload against the pinned Minestom release before distributing it.
