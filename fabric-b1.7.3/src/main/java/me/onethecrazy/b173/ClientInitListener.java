package me.onethecrazy.b173;

import me.onethecrazy.FBXPlayerModelsClient;
import me.onethecrazy.screens.ConfigScreen;
import net.mine_diver.unsafeevents.listener.EventListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.option.KeyBinding;
import net.modificationstation.stationapi.api.client.event.keyboard.KeyStateChangedEvent;
import net.modificationstation.stationapi.api.client.event.option.KeyBindingRegisterEvent;
import net.modificationstation.stationapi.api.event.mod.InitEvent;
import net.modificationstation.stationapi.api.mod.entrypoint.Entrypoint;
import net.modificationstation.stationapi.api.mod.entrypoint.EntrypointManager;
import net.modificationstation.stationapi.api.mod.entrypoint.EventBusPolicy;
import org.lwjgl.input.Keyboard;

import java.lang.invoke.MethodHandles;

@Entrypoint(eventBus = @EventBusPolicy(registerInstance = false))
public final class ClientInitListener {
    private static final KeyBinding OPEN_MENU = new KeyBinding("key.fbxplayermodels.open_menu", Keyboard.KEY_O);

    static {
        EntrypointManager.registerLookup(MethodHandles.lookup());
    }

    public ClientInitListener() {
    }

    @EventListener
    private static void onClientInit(InitEvent event) {
        FBXPlayerModelsClient.initialize();
    }

    @EventListener
    private static void registerKeyBindings(KeyBindingRegisterEvent event) {
        event.register(OPEN_MENU);
    }

    @EventListener
    private static void onKeyStateChanged(KeyStateChangedEvent event) {
        if (event.environment != KeyStateChangedEvent.Environment.IN_GAME
                || !Keyboard.getEventKeyState() || Keyboard.isRepeatEvent()
                || Keyboard.getEventKey() != OPEN_MENU.code) {
            return;
        }
        FBXPlayerModelsClient.enqueue(() -> {
            Minecraft client = FBXPlayerModelsClient.minecraft();
            if (client.player != null && client.currentScreen == null) {
                client.setScreen(new ConfigScreen(null));
            }
        });
    }
}
