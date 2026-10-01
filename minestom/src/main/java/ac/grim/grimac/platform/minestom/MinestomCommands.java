package ac.grim.grimac.platform.minestom;

import ac.grim.grimac.command.CloudCommandService;
import ac.grim.grimac.platform.api.command.PlayerSelector;
import ac.grim.grimac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.grim.grimac.platform.api.sender.Sender;

import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;

import org.incendo.cloud.CommandManager;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.internal.CommandRegistrationHandler;
import org.incendo.cloud.parser.ArgumentParseResult;
import org.incendo.cloud.parser.ParserDescriptor;
import org.incendo.cloud.suggestion.Suggestion;
import org.incendo.cloud.suggestion.SuggestionProvider;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

final class MinestomCommands implements AutoCloseable {
    private final GrimMinestom runtime;
    private Command command;

    MinestomCommands(GrimMinestom runtime) {
        this.runtime = runtime;
    }

    void register() {
        if (command != null) return;
        CommandManager<Sender> manager =
                new CommandManager<>(
                        ExecutionCoordinator.simpleCoordinator(),
                        CommandRegistrationHandler.nullCommandRegistrationHandler()) {
                    public boolean hasPermission(Sender sender, String permission) {
                        return sender.hasPermission(permission);
                    }
                };
        new CloudCommandService(
                        () -> manager,
                        new CloudPlatformCommandArguments() {
                            public ParserDescriptor<Sender, PlayerSelector>
                                    singlePlayerSelectorParser() {
                                return ParserDescriptor.of(
                                        (context, input) -> {
                                            String name = input.readString();
                                            var player =
                                                    MinecraftServer.getConnectionManager()
                                                            .getOnlinePlayerByUsername(name);
                                            if (player == null)
                                                return ArgumentParseResult.failure(
                                                        new IllegalArgumentException(
                                                                "Player is not online: " + name));
                                            Sender sender = runtime.senders().wrap(player);
                                            return ArgumentParseResult.success(
                                                    new PlayerSelector() {
                                                        public boolean isSingle() {
                                                            return true;
                                                        }

                                                        public Sender getSinglePlayer() {
                                                            return sender;
                                                        }

                                                        public Collection<Sender> getPlayers() {
                                                            return List.of(sender);
                                                        }

                                                        public String inputString() {
                                                            return name;
                                                        }
                                                    });
                                        },
                                        PlayerSelector.class);
                            }

                            public SuggestionProvider<Sender> onlinePlayerSuggestions() {
                                return (context, input) ->
                                        CompletableFuture.completedFuture(
                                                MinecraftServer.getConnectionManager()
                                                        .getOnlinePlayers()
                                                        .stream()
                                                        .map(
                                                                player ->
                                                                        Suggestion.suggestion(
                                                                                player
                                                                                        .getUsername()))
                                                        .toList());
                            }
                        })
                .registerCommands();
        Command command = new Command("grim", "grimac");
        var arguments = ArgumentType.StringArray("arguments");
        arguments.setSuggestionCallback(
                (sender, context, suggestions) -> {
                    var result =
                            manager.suggestionFactory()
                                    .suggest(runtime.senders().wrap(sender), context.getInput())
                                    .join();
                    for (Suggestion suggestion : result.list())
                        suggestions.addEntry(new SuggestionEntry(suggestion.suggestion()));
                });
        command.setDefaultExecutor(
                (sender, context) ->
                        manager.commandExecutor()
                                .executeCommand(runtime.senders().wrap(sender), "grim help"));
        command.addSyntax(
                (sender, context) ->
                        manager.commandExecutor()
                                .executeCommand(
                                        runtime.senders().wrap(sender),
                                        "grim " + String.join(" ", context.get(arguments)))
                                .exceptionally(
                                        error -> {
                                            sender.sendMessage(
                                                    Component.text(
                                                            "Grim command failed: "
                                                                    + error.getMessage()));
                                            return null;
                                        }),
                arguments);
        runtime.registerCommand(command);
        this.command = command;
    }

    public void close() {
        if (command != null) {
            runtime.unregisterCommand(command);
            command = null;
        }
    }
}
