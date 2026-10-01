package ac.grim.grimac.minestom.agent;

/** Lets cancelled native tasks release the library classloader before their next scheduled tick. */
public final class CallbackSlot implements Runnable, AutoCloseable {
    private volatile Runnable action;

    public CallbackSlot(Runnable action) {
        this.action = action;
    }

    @Override
    public void run() {
        Runnable current = action;
        if (current != null) current.run();
    }

    @Override
    public void close() {
        action = null;
    }
}
