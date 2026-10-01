package ac.grim.grimac.utils.anticheat;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Tracks engine-owned clients so embedded platforms can stop them before classloader disposal. */
public final class GrimHttpClients {
    private static final List<ClientHandle> CLIENTS = new ArrayList<>();

    private GrimHttpClients() {}

    public static synchronized Supplier<HttpClient> create(Duration timeout) {
        ClientHandle handle =
                new ClientHandle(HttpClient.newBuilder().connectTimeout(timeout).build());
        CLIENTS.add(handle);
        return handle;
    }

    public static synchronized List<HttpClient> releaseClients() {
        List<HttpClient> result = new ArrayList<>();
        for (ClientHandle handle : CLIENTS) {
            result.add(handle.client);
            // JDK HTTP cleaners can retain shutdown exception backtraces, including engine
            // classes. Clear static references to the facade so its cleaner can still run.
            handle.client = null;
        }
        CLIENTS.clear();
        return result;
    }

    private static final class ClientHandle implements Supplier<HttpClient> {
        private volatile HttpClient client;

        private ClientHandle(HttpClient client) {
            this.client = client;
        }

        @Override
        public HttpClient get() {
            HttpClient current = client;
            if (current == null)
                throw new IllegalStateException("Grim HTTP client has been retired");
            return current;
        }
    }
}
