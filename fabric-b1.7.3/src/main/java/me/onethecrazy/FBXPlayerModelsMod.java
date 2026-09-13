package me.onethecrazy;

import com.aksulightning.platform.b173.BabricPlatformLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FBXPlayerModelsMod {
    public static final String MOD_ID = FBXPlayerModels.MOD_ID;
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final BabricPlatformLogger PLATFORM_LOGGER = new BabricPlatformLogger(LOGGER);

    private FBXPlayerModelsMod() {
    }
}
