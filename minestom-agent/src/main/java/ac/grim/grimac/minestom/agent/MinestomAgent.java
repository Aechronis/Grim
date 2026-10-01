package ac.grim.grimac.minestom.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.Method;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/** Installs packet hooks before Minestom is loaded; no dynamic attach or Minestom fork required. */
public final class MinestomAgent {
    private static final String CONNECTION =
            "net/minestom/server/network/player/PlayerSocketConnection";
    private static final Type HOOKS = Type.getType(PacketHooks.class);
    private static final Type BUFFER =
            Type.getObjectType("net/minestom/server/network/NetworkBuffer");
    private static volatile boolean installed;
    private static volatile boolean transformed;
    private static volatile String failure;

    private MinestomAgent() {}

    public static void premain(String arguments, Instrumentation instrumentation) {
        install(instrumentation);
    }

    public static void agentmain(String arguments, Instrumentation instrumentation) {
        install(instrumentation);
    }

    private static synchronized void install(Instrumentation instrumentation) {
        if (installed) return;
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (type.getName().equals(CONNECTION.replace('/', '.'))) {
                throw new IllegalStateException(
                        "Install grim-minestom-agent before loading Minestom");
            }
        }
        instrumentation.addTransformer(new Transformer());
        installed = true;
    }

    public static void requireInstalled() {
        if (!installed)
            throw new IllegalStateException(
                    "Start Java with -javaagent:grim-minestom-agent.jar (or Launcher-Agent-Class)");
        try {
            Class.forName(
                    CONNECTION.replace('/', '.'), false, MinestomAgent.class.getClassLoader());
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException(
                    "Minestom is missing from the agent classloader", error);
        }
        if (!transformed)
            throw new IllegalStateException(
                    "Unsupported Minestom packet implementation: " + failure);
    }

    private static final class Transformer implements ClassFileTransformer {
        @Override
        public byte[] transform(
                ClassLoader loader,
                String name,
                Class<?> redefining,
                ProtectionDomain domain,
                byte[] bytes) {
            if (!CONNECTION.equals(name)) return null;
            try {
                ClassReader reader = new ClassReader(bytes);
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
                int[] matches = new int[4];
                reader.accept(
                        new ClassVisitor(Opcodes.ASM9, writer) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String methodName,
                                    String descriptor,
                                    String signature,
                                    String[] exceptions) {
                                MethodVisitor original =
                                        super.visitMethod(
                                                access,
                                                methodName,
                                                descriptor,
                                                signature,
                                                exceptions);
                                int kind;
                                if (methodName.equals("<init>")
                                        && descriptor.equals(
                                                "(Ljava/nio/channels/SocketChannel;Ljava/net/SocketAddress;Ljava/"
                                                    + "lang/Thread;Ljava/lang/Thread;)V"))
                                    kind = 0;
                                else if (methodName.equals("readClientPacket")
                                        && descriptor.equals(
                                                "(Lnet/minestom/server/network/packet/PacketRegistry$PacketInfo;Lnet/minestom/"
                                                    + "server/network/NetworkBuffer;)Lnet/minestom/server/network/packet/client/"
                                                    + "ClientPacket;")) kind = 1;
                                else if (methodName.equals("writePacketSync")
                                        && descriptor.equals(
                                                "(Lnet/minestom/server/network/NetworkBuffer;Lnet/minestom/server/"
                                                    + "network/packet/server/SendablePacket;Z)Z"))
                                    kind = 2;
                                else if (methodName.equals("disconnect")
                                        && descriptor.equals("()V")) kind = 3;
                                else return original;
                                matches[kind]++;
                                return new AdviceAdapter(
                                        Opcodes.ASM9, original, access, methodName, descriptor) {
                                    private int start;
                                    private int state;

                                    @Override
                                    protected void onMethodEnter() {
                                        if (kind == 1) {
                                            loadThis();
                                            loadArg(0);
                                            loadArg(1);
                                            invokeStatic(
                                                    HOOKS,
                                                    new Method(
                                                            "receive",
                                                            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/"
                                                                + "lang/Object;"));
                                            dup();
                                            Label accepted = new Label();
                                            ifNonNull(accepted);
                                            pop();
                                            visitInsn(ACONST_NULL);
                                            visitInsn(ARETURN);
                                            mark(accepted);
                                            checkCast(BUFFER);
                                            storeArg(1);
                                        } else if (kind == 2) {
                                            start = newLocal(Type.LONG_TYPE);
                                            loadArg(0);
                                            invokeInterface(
                                                    BUFFER, new Method("writeIndex", "()J"));
                                            storeLocal(start);
                                            state = newLocal(Type.getType(Object.class));
                                            loadThis();
                                            invokeVirtual(
                                                    Type.getObjectType(CONNECTION),
                                                    new Method(
                                                            "getServerState",
                                                            "()Lnet/minestom/server/network/ConnectionState;"));
                                            storeLocal(state);
                                        }
                                    }

                                    @Override
                                    protected void onMethodExit(int opcode) {
                                        if (opcode == ATHROW) return;
                                        if (kind == 0 || kind == 3) {
                                            loadThis();
                                            invokeStatic(
                                                    HOOKS,
                                                    new Method(
                                                            kind == 0
                                                                    ? "connected"
                                                                    : "disconnected",
                                                            "(Ljava/lang/Object;)V"));
                                        } else if (kind == 2) {
                                            // Keep the return value; the hook checks successful
                                            // serialization itself.
                                            dup();
                                            loadThis();
                                            loadArg(1);
                                            loadArg(0);
                                            loadLocal(start);
                                            loadLocal(state);
                                            loadArg(2);
                                            invokeStatic(
                                                    Type.getType(WriteHook.class),
                                                    new Method(
                                                            "complete",
                                                            "(ZLjava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;JLjava/"
                                                                + "lang/Object;Z)V"));
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.EXPAND_FRAMES);
                for (int count : matches) {
                    if (count != 1)
                        throw new IllegalStateException("Packet hook signatures changed");
                }
                transformed = true;
                return writer.toByteArray();
            } catch (Throwable error) {
                failure = error.toString();
                return null; // requireInstalled refuses to start the engine.
            }
        }
    }

    public static final class WriteHook {
        private WriteHook() {}

        public static void complete(
                boolean success,
                Object connection,
                Object packet,
                Object buffer,
                long start,
                Object state,
                boolean compressed) {
            if (success) PacketHooks.sent(connection, packet, buffer, start, state, compressed);
        }
    }
}
