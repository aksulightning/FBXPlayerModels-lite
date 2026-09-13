package com.aksulightning.platform.b173;

import com.aksulightning.platform.PlatformConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

public final class BabricPlatformConfig implements PlatformConfig {
    @Override
    public Path gameDirectory() {
        return FabricLoader.getInstance().getGameDir();
    }
}
