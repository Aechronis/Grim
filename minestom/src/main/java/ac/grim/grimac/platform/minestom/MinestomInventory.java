package ac.grim.grimac.platform.minestom;

import ac.grim.grimac.platform.api.player.PlatformInventory;

import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;

import io.netty.buffer.Unpooled;

import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.Player;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.utils.inventory.PlayerInventoryUtils;

import java.util.Arrays;

final class MinestomInventory implements PlatformInventory {
    private final Player player;

    MinestomInventory(Player player) {
        this.player = player;
    }

    private ItemStack convert(net.minestom.server.item.ItemStack stack) {
        NetworkBuffer data = NetworkBuffer.resizableBuffer(MinecraftServer.getRegistries());
        data.write(net.minestom.server.item.ItemStack.NETWORK_TYPE, stack);
        byte[] bytes = new byte[Math.toIntExact(data.writeIndex())];
        data.copyTo(0, bytes, 0, bytes.length);
        var buffer = Unpooled.wrappedBuffer(bytes);
        try {
            return PacketWrapper.createUniversalPacketWrapper(buffer).readItemStack();
        } finally {
            buffer.release();
        }
    }

    public ItemStack getItemInHand() {
        return convert(player.getItemInMainHand());
    }

    public ItemStack getItemInOffHand() {
        return convert(player.getItemInOffHand());
    }

    public ItemStack getStack(int bukkitSlot, int vanillaSlot) {
        if (vanillaSlot < 0 || vanillaSlot > 45) return ItemStack.EMPTY;
        int slot = PlayerInventoryUtils.convertWindow0SlotToMinestomSlot(vanillaSlot);
        return convert(player.getInventory().getItemStack(slot));
    }

    public ItemStack getHelmet() {
        return convert(player.getEquipment(EquipmentSlot.HELMET));
    }

    public ItemStack getChestplate() {
        return convert(player.getEquipment(EquipmentSlot.CHESTPLATE));
    }

    public ItemStack getLeggings() {
        return convert(player.getEquipment(EquipmentSlot.LEGGINGS));
    }

    public ItemStack getBoots() {
        return convert(player.getEquipment(EquipmentSlot.BOOTS));
    }

    public ItemStack[] getContents() {
        return Arrays.stream(player.getInventory().getItemStacks())
                .map(this::convert)
                .toArray(ItemStack[]::new);
    }

    public String getOpenInventoryKey() {
        if (!(player.getOpenInventory() instanceof Inventory inventory)) return "PLAYER";
        return switch (inventory.getInventoryType()) {
            case CHEST_1_ROW, CHEST_2_ROW, CHEST_3_ROW, CHEST_4_ROW, CHEST_5_ROW, CHEST_6_ROW ->
                    "CHEST";
            case WINDOW_3X3 -> "DISPENSER";
            default -> inventory.getInventoryType().name();
        };
    }
}
