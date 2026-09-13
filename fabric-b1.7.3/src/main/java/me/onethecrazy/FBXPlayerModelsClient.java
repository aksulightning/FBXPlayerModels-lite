package me.onethecrazy;

import com.aksulightning.platform.PlatformServices;
import com.aksulightning.platform.b173.BabricPlatformClient;
import com.aksulightning.platform.b173.BabricPlatformConfig;
import com.aksulightning.platform.b173.BabricPlatformEvents;
import me.onethecrazy.util.FileUtil;
import me.onethecrazy.util.objects.save.FBXPlayerModelsSave;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class FBXPlayerModelsClient {
    private static final Queue<Runnable> CLIENT_TASKS = new ConcurrentLinkedQueue<>();
    private static FBXPlayerModelsSave options;
    private static boolean initialized;

    private FBXPlayerModelsClient() {
    }

    public static void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;

        PlatformServices.initialize(
                FBXPlayerModelsMod.PLATFORM_LOGGER,
                new BabricPlatformConfig(),
                new BabricPlatformClient(),
                new BabricPlatformEvents()
        );
        FBXPlayerModelsMod.LOGGER.info("Initializing {} for Minecraft Beta 1.7.3", FBXPlayerModels.DISPLAY_NAME);
        FileUtil.createPaths();
        PlatformServices.events().registerClientStarted(SkinManager::loadSelfSkin);
    }

    public static FBXPlayerModelsSave options() {
        try {
            if (options == null) {
                options = FileUtil.loadSave();
            }
        } catch (IOException exception) {
            FBXPlayerModelsMod.LOGGER.error("Error while loading the client configuration", exception);
        }

        if (options == null) {
            options = new FBXPlayerModelsSave();
        }
        if (options.selectedSkin == null) {
            options.selectedSkin = new me.onethecrazy.util.objects.save.ClientSkin();
        }
        return options;
    }

    public static Minecraft minecraft() {
        return (Minecraft) FabricLoader.getInstance().getGameInstance();
    }

    public static void enqueue(Runnable task) {
        if (task != null) {
            CLIENT_TASKS.add(task);
        }
    }

    public static void runPendingTasks() {
        Runnable task;
        while ((task = CLIENT_TASKS.poll()) != null) {
            try {
                task.run();
            } catch (RuntimeException exception) {
                FBXPlayerModelsMod.LOGGER.error("Client task failed", exception);
            }
        }
    }
}
