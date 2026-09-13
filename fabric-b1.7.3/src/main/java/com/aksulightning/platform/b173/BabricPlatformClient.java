package com.aksulightning.platform.b173;

import com.aksulightning.platform.PlatformClient;
import me.onethecrazy.FBXPlayerModelsClient;
import net.minecraft.client.Minecraft;

public final class BabricPlatformClient implements PlatformClient {
    @Override
    public void executeOnRenderThread(Runnable task) {
        FBXPlayerModelsClient.enqueue(task);
    }

    @Override
    public String currentSessionUuid() {
        Minecraft minecraft = FBXPlayerModelsClient.minecraft();
        return minecraft == null || minecraft.session == null ? null : minecraft.session.username;
    }
}
