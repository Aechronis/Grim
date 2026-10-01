package ac.grim.grimac.manager.init.load;

import ac.grim.grimac.utils.anticheat.LogUtil;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.protocol.chat.ChatTypes;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.enchantment.type.EnchantmentTypes;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;

import java.util.concurrent.Executors;

public class PacketEventsInit implements LoadableInitable {

    private final PacketEventsAPI<?> packetEventsAPI;

    public PacketEventsInit(PacketEventsAPI<?> packetEventsAPI) {
        this.packetEventsAPI = packetEventsAPI;
    }

    @Override
    public void load() {
        LogUtil.info("Loading PacketEvents...");
        PacketEvents.setAPI(packetEventsAPI);
        PacketEvents.getAPI().getSettings()
                .fullStackTrace(true)
                .kickOnPacketException(true)
                .checkForUpdates(false)
                .reEncodeByDefault(false)
                .debug(false);
        // The Minestom transport owns its packet boundary and uses upstream PacketEvents,
        // which has no Grim-specific ViaVersion injector setting.
        if (ac.grim.grimac.GrimAPI.INSTANCE.getPlatform() != ac.grim.grimac.platform.api.Platform.MINESTOM) {
            PacketEvents.getAPI().getSettings().preViaInjection(true);
        }
        PacketEvents.getAPI().load();
        // This may seem useless, but it causes java to start loading stuff async before we need it
        Runnable preload = () -> {
            StateTypes.AIR.getName();
            ItemTypes.AIR.getName();
            EntityTypes.PLAYER.getParent();
            EntityDataTypes.BOOLEAN.getName();
            ChatTypes.CHAT.getName();
            EnchantmentTypes.ALL_DAMAGE_PROTECTION.getName();
            ParticleTypes.DUST.getName();
            WrappedBlockState.getByString(PacketEvents.getAPI().getServerManager().getVersion().toClientVersion(), "", false);
        };
        if (ac.grim.grimac.GrimAPI.INSTANCE.getLoader().failOnLifecycleError()) {
            // Embedded hosts own worker lifetimes; finish preloading before startup succeeds.
            preload.run();
        } else {
            Executors.defaultThreadFactory().newThread(preload).start();
        }
    }
}
