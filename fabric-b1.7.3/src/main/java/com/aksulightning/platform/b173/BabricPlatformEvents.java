package com.aksulightning.platform.b173;

import com.aksulightning.platform.PlatformEvents;
import me.onethecrazy.FBXPlayerModelsClient;

public final class BabricPlatformEvents implements PlatformEvents {
    @Override
    public void registerClientStarted(Runnable callback) {
        FBXPlayerModelsClient.enqueue(callback);
    }
}
