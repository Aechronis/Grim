# Grim for Minestom

This platform adapter embeds Grim's existing checks in a stock Minestom server. It contains no application-specific checks or exemptions. It currently targets **Java 25** and **Minestom `2026.09.12-26.2` (protocol 776)**. Other Minestom versions require verification of the extension points and protocol mappings before updating the pin. No Minestom fork, bytecode transformation, or Java agent is used.

## Build and use

From this repository:

```sh
./gradlew -PminestomOnly=true :minestom:build
```

For source dependencies, add this to your application's `settings.gradle.kts` (adjust the checkout path):

```kotlin
includeBuild("../Grim") {
    dependencySubstitution {
        substitute(module("ac.grim.grimac:grim-minestom")).using(project(":minestom"))
    }
}
```

Add the single library dependency alongside the application's Minestom dependency:

```kotlin
implementation("ac.grim.grimac:grim-minestom:local")
```

The library includes both the platform adapter and the native Minestom extensions and detachable callbacks. Reloadable hosts must package `ac/grim/grimac/minestom/**` from this JAR in the host classloader and exclude that package from the replaceable classloader's JAR. The remaining adapter classes and engine dependencies belong in the replaceable classloader. Do not put the full library or its engine dependencies on the host classpath when using engine replacement.

Composite builds include only the common engine and the Minestom project by default. For Maven consumption, publish `:common` and `:minestom` together at the same version to your repository. These coordinates are not an announced upstream release.

The library's runtime repositories are Maven Central, `https://maven.grim.ac/public/releases`, `https://repo.codemc.io/repository/maven-snapshots/`, and `https://nexus.scarsz.me/content/repositories/releases`. The Minestom adapter pins an upstream PacketEvents snapshot containing Adventure 5 support. The other Grim platforms retain their existing PacketEvents dependency.

Initialize the native support before Minestom loads its settings, install its player provider after initialization, then start Grim before accepting players:

```java
import ac.grim.grimac.minestom.MinestomSupport;
import ac.grim.grimac.platform.minestom.GrimMinestom;

MinestomSupport.prepare();
MinecraftServer server = MinecraftServer.init();
MinestomSupport.install();

GrimMinestom grim = GrimMinestom.builder(Path.of("grim"))
    .permissions((sender, permission, defaultIfUnset) ->
        permissionService.hasPermission(sender, permission, defaultIfUnset))
    .build();
grim.start();
// Configure the world and other application services, then call server.start(...).
```

`prepare()` sets Minestom's existing `minestom.viewable-packet` setting to `false`, allowing viewable packets to reach native outgoing events. `install()` rejects initialization if Minestom had already loaded with that optimization enabled. Grouped and cached packets remain supported. No JVM agent arguments or manifest entries are needed.

`install()` uses Minestom's supported `PlayerProvider` API. Applications with a custom player class must extend `ac.grim.grimac.minestom.GrimPlayer` and call `MinestomSupport.install(MyPlayer::new)`. Its packet entry points are final so subclasses cannot silently bypass tracking. Keep the custom player class in the host classloader too. Install the provider once; enabling and reloading the engine does not replace it.

The permission callback is optional. Its default grants console permissions and uses Grim's declared permission defaults for players. Provide your own permission service for staff access. Optional `commands(register, unregister)` and `permissionRegistration(...)` callbacks let a host track registrations; the defaults use Minestom's command manager directly. The standard `/grim` and `/grimac` commands are available. Minestom has no built-in offline-player directory, so player selectors resolve online players.

Call `grim.close()` before stopping Minestom. Hosts with a staged shutdown can call `grim.quiesce()` first to detach packet input and drain scheduled work, then `close()` to finish engine shutdown. Startup failures propagate to the host.

## Reloading

`/grim reload` (or `grim.api().reloadAsync()`) reloads upstream configuration while retaining player tracking. Replacing the engine requires closing the previous runtime and loading the adapter **and its engine dependencies in a fresh classloader**, while the native support package stays in the host classloader. There can be only one active runtime in a process and one startup per engine classloader.

Full engine replacement disconnects tracked players. Enabling the engine while players are already connected also disconnects them: their earlier world and transaction history cannot be reconstructed safely. Reconnecting gives each player a complete tracked session. Updating the native support classes or the host player class requires restarting the server process; neither is part of the reloadable engine.

## Packet transport

The `GrimPlayer` extension intercepts decoded gameplay packets on the native socket reader before they enter the tick queue. Native packet events cover immediate play packets. During reconfiguration, the socket reader waits for the native tick to process the configuration acknowledgement before decoding another read, preserving both queued gameplay order and the new protocol state. Outgoing events run on the socket writer after ordinary application listeners have made their cancellation decisions. The adapter translates packet objects through PacketEvents and runs the unchanged upstream checks.

Each accepted outgoing event is replaced with one immutable native buffered batch containing Grim's pre-send transactions, the accepted/rewritten packet, and its post-send transactions. These batches retain the order of the original events, and Minestom's buffer-growth retries cannot run the checks a second time. Minestom continues to own framing delivery, socket threads, compression negotiation, encryption, and authentication.

The native transport has these integration requirements:

- Use `GrimPlayer` (or a subclass) for checked connections. Replacing the provider with an incompatible one causes affected logins to fail.
- Send raw `BufferedPacket` values through `Player.sendPacket`/`sendPackets`. The player extension queues each buffer as a single batch; its contained packets pass through native events on the writer without interleaving another send. Direct `PlayerConnection.sendPacket(BufferedPacket)` bypasses Minestom's events and is **unsupported**. Direct connection sends of ordinary server, cached, and framed packets are supported.
- Custom batch helpers that manually dispatch `PlayerPacketOutEvent` or translate components should defer those operations when `PacketBridge.handlesBufferedPackets(player)` is true. The adapter will perform them once on the writer. Do not manually dispatch outgoing events for packets that are not being sent.
- Reserve the support event node's final priority (`Integer.MAX_VALUE`); later listeners must not undo its cancellation. Other listeners can cancel packets before the adapter runs.
- The adapter begins after native login acknowledgement, before configuration/world delivery. Minestom handles authentication and rejects malformed wire encodings before Grim sees decoded packet objects. This is not raw-byte inspection of the login protocol.

Hook registration drains in-flight callbacks on detach. Disabled engines leave the native player extension in pass-through mode. Canceled native scheduled tasks retain only detachable callbacks from the support classloader.

Disabling viewable batching and translating decoded packet objects adds work compared with Minestom's optimized native sends. Live-client gameplay and performance testing is still required; socket checks do not establish identical behavior to the former raw transport for every malformed input or third-party networking extension.

The initial supported transport is direct Java clients using Minestom's native protocol. Protocol translators, alternate socket implementations, and custom packet registries require separate integration work. SQLite is included for the default upstream storage configuration. Other database backends require their driver dependencies supplied by the host.

## Following upstream

Keep upstream changes in `common` limited to platform detection and embedded lifecycle integration; implement platform behavior in `minestom`. The checks remain upstream code. Keep the original repository as the `upstream` Git remote and merge its `2.0` branch into your Minestom branch. After updating, verify startup, complete login, compression, chunk/block tracking, transaction ordering, cancellation, and shutdown/reload against the pinned Minestom release before distributing it.
