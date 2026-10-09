package org.geysermc.paletter;

import java.io.PrintWriter;
import java.io.StringWriter;

public interface LogListener {
    void info(String message);

    void warn(String message);

    void error(String message);

    void debug(String message);

    void info(String message, Throwable t);

    void warn(String message, Throwable t);

    void error(String message, Throwable t);

    void debug(String message, Throwable t);

    final class Default implements LogListener {
        private static final boolean DEBUG = Boolean.getBoolean("Paletter.DebugLogging");

        @Override
        public void info(String message) {
            System.out.println("[\u001B[34mINF\u001B[0m] " + message);
        }

        @Override
        public void warn(String message) {
            System.out.println("[\u001B[33mWRN\u001B[0m] " + message);
        }

        @Override
        public void error(String message) {
            System.out.println("[\u001B[31mERR\u001B[0m] " + message);
        }

        @Override
        public void debug(String message) {
            if (!DEBUG) return;
            System.out.println("[\u001B[35mDBG\u001B[0m] " + message);
        }

        @Override
        public void info(String message, Throwable t) {
            System.out.println("[\u001B[34mINF\u001B[0m] " + message + " " + throwableToString(t));
        }

        @Override
        public void warn(String message, Throwable t) {
            System.out.println("[\u001B[33mWRN\u001B[0m] " + message + " " + throwableToString(t));
        }

        @Override
        public void error(String message, Throwable t) {
            System.out.println("[\u001B[31mERR\u001B[0m] " + message + " " + throwableToString(t));
        }

        @Override
        public void debug(String message, Throwable t) {
            if (!DEBUG) return;
            System.out.println("[\u001B[35mDBG\u001B[0m] " + message + " " + throwableToString(t));
        }

        private String throwableToString(Throwable t) {
            StringWriter writer = new StringWriter();
            PrintWriter printWriter = new PrintWriter(writer);
            t.printStackTrace(printWriter);
            return writer.toString();
        }
    }
}
