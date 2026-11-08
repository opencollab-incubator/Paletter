package org.geysermc.paletter.tools.bds;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * One headless Bedrock Dedicated Server process: writes {@code server.properties}, starts {@code bedrock_server},
 * copies its console to a log file, waits for lines and stops it again. Every process started here is killed when the
 * JVM exits, so no server outlives a failed run.
 */
public final class BdsServer implements AutoCloseable {
    static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    private static final Set<Process> RUNNING = ConcurrentHashMap.newKeySet();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> RUNNING.forEach(process -> {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        })));
    }

    private final Path directory;
    private final List<String> lines = new ArrayList<>();
    private final boolean echo;
    private Process process;
    private PrintWriter input;
    private Thread reader;

    public BdsServer(Path directory, boolean echo) {
        this.directory = directory;
        this.echo = echo;
    }

    public static Path executable(Path server) {
        return server.resolve(WINDOWS ? "bedrock_server.exe" : "bedrock_server");
    }

    /**
     * Sets properties in {@code server.properties}, appending the ones it does not have.
     */
    public static void configure(Path server, Map<String, String> properties) throws IOException {
        Map<String, String> overrides = new LinkedHashMap<>(properties);
        Path file = server.resolve("server.properties");
        List<String> lines = new ArrayList<>(Files.readAllLines(file));
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int equals = line.indexOf('=');
            if (equals > 0 && !line.startsWith("#")) {
                String value = overrides.remove(line.substring(0, equals));
                if (value != null) {
                    lines.set(i, line.substring(0, equals + 1) + value);
                }
            }
        }
        overrides.forEach((key, value) -> lines.add(key + "=" + value));
        Files.write(file, lines);
    }

    /**
     * Starts the server; its console goes to {@code log} (and to stdout with {@code echo}, except addon dump chunks).
     */
    public void start(Path log) throws IOException {
        Path executable = executable(directory);
        if (!WINDOWS) {
            executable.toFile().setExecutable(true);
        }
        ProcessBuilder builder = new ProcessBuilder(executable.toAbsolutePath().toString())
            .directory(directory.toFile()).redirectErrorStream(true);
        if (!WINDOWS) {
            builder.environment().put("LD_LIBRARY_PATH", ".");
        }
        Files.createDirectories(log.toAbsolutePath().getParent());
        process = builder.start();
        RUNNING.add(process);
        input = new PrintWriter(process.getOutputStream(), true, StandardCharsets.UTF_8);
        Writer writer = Files.newBufferedWriter(log, StandardCharsets.UTF_8);
        reader = Thread.ofPlatform().daemon().name("bds-console").start(() -> {
            try (BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                 writer) {
                String line;
                while ((line = output.readLine()) != null) {
                    writer.write(line);
                    writer.write('\n');
                    writer.flush();
                    synchronized (lines) {
                        lines.add(line);
                        lines.notifyAll();
                    }
                    if (echo && !line.contains("PALETTER ")) {
                        System.out.println("  | " + line);
                    }
                }
            } catch (IOException ignored) {
                // process gone
            } finally {
                synchronized (lines) {
                    lines.notifyAll();
                }
            }
        });
    }

    /**
     * Waits until a console line matches, the process exits or the timeout passes.
     *
     * @return the matching line, or null
     */
    String await(Predicate<String> condition, long timeoutSeconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        int checked = 0;
        synchronized (lines) {
            while (true) {
                for (; checked < lines.size(); checked++) {
                    if (condition.test(lines.get(checked))) {
                        return lines.get(checked);
                    }
                }
                long left = deadline - System.nanoTime();
                if (left <= 0 || (!process.isAlive() && !reader.isAlive())) {
                    return null;
                }
                lines.wait(Math.max(1, Math.min(TimeUnit.NANOSECONDS.toMillis(left), 500)));
            }
        }
    }

    /** Waits for {@code Server started.}; fails if the server does not get there. */
    public void awaitStarted(long timeoutSeconds) throws InterruptedException, IOException {
        if (await(line -> line.contains("Server started.") || line.contains("PALETTER-DONE"), timeoutSeconds) == null) {
            throw new IOException("BDS did not start within " + timeoutSeconds + "s:\n  " + String.join("\n  ", tail(20)));
        }
    }

    public boolean sawLine(Predicate<String> condition) {
        synchronized (lines) {
            return lines.stream().anyMatch(condition);
        }
    }

    public List<String> lines(Predicate<String> condition) {
        synchronized (lines) {
            return lines.stream().filter(condition).toList();
        }
    }

    List<String> tail(int count) {
        synchronized (lines) {
            return List.copyOf(lines.subList(Math.max(0, lines.size() - count), lines.size()));
        }
    }

    void command(String command) {
        input.println(command);
    }

    /** Sends {@code stop} and kills the process (and its children) if it does not exit in time. */
    void stop() throws InterruptedException {
        if (process == null) {
            return;
        }
        if (process.isAlive()) {
            input.println("stop");
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
        reader.join(5000);
        RUNNING.remove(process);
        process = null;
    }

    @Override
    public void close() throws InterruptedException {
        stop();
    }
}
