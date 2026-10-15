package tech.wenisch.petri.agent.run;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Wraps a command with {@code petri-confine} (Landlock): the process can read the system and the
 * toolchain, and write only the directories granted explicitly (the worktree, its caches, a private
 * home). Everything else, including Petri's data directory with the database and master key, is
 * denied for the same Unix user. Needs no root, setuid or user namespace, so it works with random
 * container UIDs (anyuid) and hardened pods.
 */
@Component
class Confinement {
  private static final List<String> SYSTEM_READ_ONLY = List.of(
      "/usr", "/bin", "/sbin", "/lib", "/etc", "/opt", "/proc");

  private final Path command;
  private final LocalToolchain toolchain;
  private final Path pkiDirectory;

  Confinement(
      @Value("${petri.terminal.confine-command:}") String command, LocalToolchain toolchain,
      @Value("${petri.packages.pki-dir:./data/pki}") String pkiDirectory) {
    this.pkiDirectory = Path.of(pkiDirectory).toAbsolutePath().normalize();
    this.command = command == null || command.isBlank() ? null : Path.of(command.strip());
    this.toolchain = toolchain;
  }

  boolean available() {
    return command != null && Files.isExecutable(command);
  }

  /** Builds {@code petri-confine ... -- argv}; {@code writable} are the only writable paths. */
  List<String> wrap(List<Path> writable, List<String> argv) {
    return wrap(List.of(), writable, argv);
  }

  /** As above, with additional paths the command may only read, such as a scanned worktree. */
  List<String> wrap(List<Path> readOnly, List<Path> writable, List<String> argv) {
    if (!available()) throw new IllegalStateException("Command confinement is not available");
    List<String> result = new ArrayList<>();
    result.add(command.toString());
    for (String path : SYSTEM_READ_ONLY) result.addAll(List.of("--ro", path));
    result.addAll(List.of("--ro", System.getProperty("java.home", "/opt/java/openjdk")));
    result.addAll(List.of("--ro", toolchain.root().toString()));
    // The CA bundle package tools trust, once an administrator has configured one.
    if (Files.isDirectory(pkiDirectory)) result.addAll(List.of("--ro", pkiDirectory.toString()));
    result.addAll(List.of("--rw", "/dev"));
    for (Path path : readOnly) result.addAll(List.of("--ro", path.toString()));
    for (Path path : writable) result.addAll(List.of("--rw", path.toString()));
    result.add("--");
    result.addAll(argv);
    return result;
  }
}
