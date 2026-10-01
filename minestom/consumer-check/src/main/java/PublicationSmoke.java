import ac.grim.grimac.minestom.MinestomSupport;
import ac.grim.grimac.platform.minestom.GrimMinestom;
import net.minestom.server.Auth;
import net.minestom.server.MinecraftServer;

import java.net.InetSocketAddress;
import java.nio.file.Path;

/** Runs against Maven artifacts only, without a checkout dependency or extra repositories. */
public final class PublicationSmoke {
    public static void main(String[] args) throws Exception {
        System.setProperty("minestom.shutdown-on-signal", "false");
        MinestomSupport.prepare();
        MinecraftServer server = MinecraftServer.init(new Auth.Offline());
        MinestomSupport.install();
        try {
            try (GrimMinestom grim = GrimMinestom.builder(Path.of("grim")).build()) {
                grim.start();
                server.start(new InetSocketAddress("127.0.0.1", 0));
                if (grim.api() == null || grim.trackedPlayers() != 0)
                    throw new AssertionError("Engine did not initialize");
                if (MinecraftServer.getCommandManager().getCommand("grim") == null)
                    throw new AssertionError("Grim commands were not registered");
                Class.forName("org.sqlite.JDBC");
            }
            if (MinecraftServer.getCommandManager().getCommand("grim") != null)
                throw new AssertionError("Grim commands survived shutdown");
        } finally {
            MinecraftServer.stopCleanly();
        }
        System.out.println("GRIM_MAVEN_CONSUMER_OK");
    }
}
