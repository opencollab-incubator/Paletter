package org.geysermc.paletter.tools.behaviour;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads files of a git checkout at any commit through the {@code git} command line (partial clones fetch blobs on
 * demand). The repositories are untrusted data: only {@code log} and {@code show} run, never their content.
 */
final class GitHistory {
    record Commit(String hash, String date, String subject) {
        String shortHash() {
            return hash.substring(0, 9);
        }
    }

    private final Path repository;

    GitHistory(Path repository) {
        this.repository = repository;
    }

    /** @return the commits that changed any of the paths, newest first */
    List<Commit> commits(String... paths) {
        List<String> command = new ArrayList<>(List.of("log", "--format=%H%x09%cs%x09%s", "--"));
        command.addAll(List.of(paths));
        List<Commit> commits = new ArrayList<>();
        for (String line : new String(run(command), StandardCharsets.UTF_8).split("\n")) {
            String[] parts = line.split("\t", 3);
            if (parts.length == 3) {
                commits.add(new Commit(parts[0], parts[1], parts[2]));
            }
        }
        return commits;
    }

    /** @return true if the file exists at the commit */
    boolean exists(Commit commit, String path) {
        return !new String(run(List.of("ls-tree", "--name-only", commit.hash(), "--", path)), StandardCharsets.UTF_8).isBlank();
    }

    /** @return the file at the commit */
    String text(Commit commit, String path) {
        return new String(run(List.of("show", commit.hash() + ":" + path)), StandardCharsets.UTF_8);
    }

    /** @return {@code owner/name@hash} for a GitHub remote, else the path and hash */
    String reference(Commit commit) {
        String url = new String(run(List.of("config", "--get", "remote.origin.url")), StandardCharsets.UTF_8).strip();
        String name = url.replaceFirst("^https://github.com/", "").replaceFirst("\\.git$", "");
        return (name.isEmpty() ? repository.getFileName().toString() : name) + "@" + commit.hash();
    }

    private byte[] run(List<String> arguments) {
        List<String> command = new ArrayList<>(List.of("git", "-C", repository.toString()));
        command.addAll(arguments);
        try {
            Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = process.getInputStream()) {
                in.transferTo(out);
            }
            int exit = process.waitFor();
            if (exit != 0 && !arguments.getFirst().equals("config")) {
                throw new IOException("git " + String.join(" ", arguments) + " failed with " + exit + " in " + repository);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
