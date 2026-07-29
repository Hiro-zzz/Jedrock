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
        return new Handle(name);
    }

    /**
     * What {@link #getLogger} actually hands out: a name, and whichever backend is installed <em>at the
     * moment something is logged</em>.
     *
     * <p>Almost every logger in this codebase is a {@code static final} field, so it is created when its
     * class loads — which is long before the server has read its config and decided whether there should
     * be a log file at all. Resolving the backend once, there and then, meant that swapping in the file
     * logger only affected classes that happened to load afterwards, and the startup lines you most want
     * on disk were exactly the ones that never reached it.
     *
     * <p>So the backend is resolved per call, and cached until the provider is replaced — which happens
     * once, at startup. The steady-state cost is a volatile read and a delegation; the debug paths that
     * care about cost are gated by {@link #isDebugEnabled()} before they get here.
     */
    final class Handle implements JLogger {

        private final String name;
        private volatile LoggerProvider resolvedFrom;
        private volatile JLogger target;

        Handle(String name) {
            this.name = name;
        }

        private JLogger target() {
            LoggerProvider provider = PROVIDER.get();
            JLogger current = target;
            if (current == null || provider != resolvedFrom) {
                current = provider.get(name);
                target = current;
                resolvedFrom = provider;
            }
            return current;
        }

        @Override public boolean isDebugEnabled() { return target().isDebugEnabled(); }
        @Override public void debug(String message) { target().debug(message); }
        @Override public void debug(Supplier<String> messageSupplier) { target().debug(messageSupplier); }
        @Override public void info(String message) { target().info(message); }
        @Override public void warn(String message) { target().warn(message); }
        @Override public void error(String message) { target().error(message); }
        @Override public void error(String message, Throwable throwable) { target().error(message, throwable); }
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
