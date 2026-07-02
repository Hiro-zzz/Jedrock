package com.jedrock.utils;

import java.util.function.Supplier;

/**
 * Ultra-lightweight logging facade.
 * Implementations can delegate to JUL, System.out, or SLF4J.
 * Designed so that log calls have minimal cost when disabled.
 */
public interface JLogger {

    void debug(String message);

    void debug(Supplier<String> messageSupplier);

    void info(String message);

    void warn(String message);

    void error(String message);

    void error(String message, Throwable throwable);

    static JLogger getLogger(String name) {
        return new SimpleConsoleLogger(name);
    }

    static JLogger getLogger(Class<?> clazz) {
        return getLogger(clazz.getSimpleName());
    }

    /**
     * Default zero-dependency console logger (can be replaced at runtime).
     */
    final class SimpleConsoleLogger implements JLogger {
        private final String name;

        SimpleConsoleLogger(String name) {
            this.name = name;
        }

        @Override
        public void debug(String message) {
            // Disabled by default for performance
        }

        @Override
        public void debug(Supplier<String> messageSupplier) {
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
