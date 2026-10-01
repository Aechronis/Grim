package ac.grim.grimac.platform.minestom;

import ac.grim.grimac.platform.api.sender.Sender;
import ac.grim.grimac.platform.api.sender.SenderFactory;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.entity.Player;

import java.util.UUID;

final class MinestomSenders extends SenderFactory<CommandSender> {
    private final GrimMinestom runtime;

    MinestomSenders(GrimMinestom runtime) {
        this.runtime = runtime;
    }

    protected UUID getUniqueId(CommandSender sender) {
        return sender instanceof Player player ? player.getUuid() : Sender.CONSOLE_UUID;
    }

    protected String getName(CommandSender sender) {
        return sender instanceof Player player ? player.getUsername() : Sender.CONSOLE_NAME;
    }

    protected void sendMessage(CommandSender sender, String message) {
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize(message));
    }

    protected void sendMessage(CommandSender sender, Component message) {
        sender.sendMessage(message);
    }

    protected boolean hasPermission(CommandSender sender, String permission) {
        return runtime.hasPermission(sender, permission, false);
    }

    protected boolean hasPermission(CommandSender sender, String permission, boolean fallback) {
        return runtime.hasPermission(sender, permission, fallback);
    }

    protected void performCommand(CommandSender sender, String command) {
        MinecraftServer.getCommandManager().execute(sender, command);
    }

    protected boolean isConsole(CommandSender sender) {
        return !(sender instanceof Player);
    }

    protected boolean isPlayer(CommandSender sender) {
        return sender instanceof Player;
    }
}
