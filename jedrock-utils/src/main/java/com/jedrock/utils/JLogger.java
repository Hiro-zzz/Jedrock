package com.jedrock.utils;

import java.util.function.Supplier;

/**
 * Ultra-lightweight logging facade. The default backend is a zero-dependency console logger
 * ({@link SimpleConsoleLogger}), but the whole facade is pluggable: call
 * {@link #setProvider(LoggerProvider)} once at startup to route every logger through a custom
 * backend (JUL, SLF4J, a test collector, or {@code /dev/null}). Designed so that log calls have
 * minimal cost when disabled — see {@link #debug(Supplier)}.
 */
public interface JLogger {

    void debug(String message);

    void debug(Supplier<String> messageSupplier);

    /**
     * Whether debug output would be emitted at all. The supplier form never <em>invokes</em> its
     * lambda when debug is off, but a lambda that captures anything is still allocated at every call —
     * so on a per-packet path, ask this first and skip the call entirely. Everywhere else the supplier
     * form is the readable choice.
     *
     * <p>The default is the global switch; a backend that scopes debug by logger name narrows it.
     */
    default boolean isDebugEnabled() {
        return Debug.enabled();
    }

    void info(String message);

    void warn(String message);

    void error(String message);

    void error(String message, Throwable throwable);

    /** Factory for {@link JLogger} instances by name. Install one via {@link #setProvider}. */
    @FunctionalInterface
    interface LoggerProvider {
        JLogger get(String name);
    }

    /** The active backend. Volatile so a startup swap is visible to every thread. */
    java.util.concurrent.atomic.AtomicReference<LoggerProvider> PROVIDER =
            new java.util.concurrent.atomic.AtomicReference<>(SimpleConsoleLogger::new);

    /**
     * Replace the logging backend for all loggers obtained afterwards. Intended to be called
     * once during startup. Passing {@code null} restores the default console backend.
     */
    static void setProvider(LoggerProvider provider) {
        PROVIDER.set(provider != null ? provider : SimpleConsoleLogger::new);
    }

    static JLogger getLogger(String name) {
        return PROVIDER.get().get(name);
    }

    static JLogger getLogger(Class<?> clazz) {
        return getLogger(clazz.getSimpleName());
    }

    /**
     * Default zero-dependency console logger, used unless {@link #setProvider} installs another.
     */
    final class SimpleConsoleLogger implements JLogger {
        private final String name;

        SimpleConsoleLogger(String name) {
            this.name = name;
        }

        @Override
        public boolean isDebugEnabled() {
            return Debug.enabled(name);
        }

        @Override
        public void debug(String message) {
            if (Debug.enabled(name)) {
                System.out.printf("[%s] [DEBUG] %s%n", name, message);
            }
        }

        @Override
        public void debug(Supplier<String> messageSupplier) {
            // Off by default: the supplier is never invoked, so the call is effectively free.
            if (Debug.enabled(name)) {
                System.out.printf("[%s] [DEBUG] %s%n", name, messageSupplier.get());
            }
        }

        @Override
        public void info(String message) {
            System.out.printf("[%s] [INFO] %s%n", name, message);
        }

        @Override
        public void warn(String message) {
            System.out.printf("[%s] [WARN] %s%n", name, message);
        }

        @Override
        public void error(String message) {
            System.err.printf("[%s] [ERROR] %s%n", name, message);
        }

        @Override
        public void error(String message, Throwable throwable) {
            System.err.printf("[%s] [ERROR] %s%n", name, message);
            throwable.printStackTrace();
        }
    }
}
