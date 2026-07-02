package com.jedrock.utils.lazy;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Thread-safe lazy value holder.
 * Useful for deferring expensive parsing or calculations.
 */
public final class Lazy<T> {

    private volatile T value;
    private volatile boolean initialized;
    private final Supplier<T> supplier;

    private Lazy(Supplier<T> supplier) {
        this.supplier = Objects.requireNonNull(supplier, "supplier");
    }

    public static <T> Lazy<T> of(Supplier<T> supplier) {
        return new Lazy<>(supplier);
    }

    public T get() {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    value = supplier.get();
                    initialized = true;
                }
            }
        }
        return value;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void reset() {
        synchronized (this) {
            initialized = false;
            value = null;
        }
    }
}
