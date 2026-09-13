package com.aksulightning.platform.b173;

import com.aksulightning.platform.PlatformLogger;
import org.slf4j.Logger;

public final class BabricPlatformLogger implements PlatformLogger {
    private final Logger delegate;

    public BabricPlatformLogger(Logger delegate) {
        this.delegate = delegate;
    }

    @Override
    public void info(String message, Object... args) {
        delegate.info(message, args);
    }

    @Override
    public void warn(String message, Object... args) {
        delegate.warn(message, args);
    }

    @Override
    public void error(String message, Object... args) {
        delegate.error(message, args);
    }
}
