package tech.wenisch.petri.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.config.PetriProperties;

@Component
public class WorkspacePaths {
  private final Path configuredRoot;

  public WorkspacePaths(PetriProperties properties) {
    this.configuredRoot = Path.of(properties.workspace().root()).toAbsolutePath().normalize();
  }

  public Path root() throws IOException {
    Files.createDirectories(configuredRoot);
    return configuredRoot.toRealPath();
  }

  public Path mirror(UUID projectId) throws IOException {
    return directory("mirrors", projectId + ".git");
  }

  public Path runWorktree(UUID projectId, UUID runId) throws IOException {
    Path projectRuns = directory("runs", projectId.toString());
    Path candidate = projectRuns.resolve(runId.toString()).normalize();
    if (!candidate.getParent().equals(projectRuns)) {
      throw new IllegalArgumentException("Invalid run worktree path");
    }
    return candidate;
  }

  public Path temporaryDirectory() throws IOException {
    return directory("tmp");
  }

  /**
   * Scratch space of one worktree, for the {@code /tmp} role: it survives between commands (unlike
   * the per-command home), is private to that worktree and is removed with it. Named after the
   * worktree directory, which is its UUID.
   */
  public Path worktreeTemporaryDirectory(Path worktree) throws IOException {
    String name = worktree.getFileName().toString();
    UUID.fromString(name);
    return directory("worktree-tmp", name);
  }

  /** Best-effort removal of a worktree's scratch space once the worktree is gone. */
  public void removeWorktreeTemporaryDirectory(UUID worktreeId) {
    try {
      Path candidate = root().resolve("worktree-tmp").resolve(worktreeId.toString());
      if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) return;
      try (var walk = Files.walk(candidate)) {
        walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
      }
    } catch (IOException | RuntimeException ignored) {
      // scratch space only; a leftover directory is harmless
    }
  }

  public Path directory(String... segments) throws IOException {
    Path root = root();
    Path current = root;
    for (String segment : segments) {
      requireSingleSegment(segment);
      current = current.resolve(segment);
      if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        if (Files.isSymbolicLink(current)) {
          throw new IllegalStateException("Symbolic links are not allowed inside the Petri workspace");
        }
        if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
          throw new IllegalStateException("Workspace path is not a directory: " + current);
        }
      } else {
        Files.createDirectory(current);
      }
    }
    Path resolved = current.toRealPath(LinkOption.NOFOLLOW_LINKS);
    if (!resolved.startsWith(root)) {
      throw new IllegalStateException("Workspace path escaped its configured root");
    }
    return resolved;
  }

  public Path requireExistingPath(Path path) throws IOException {
    Path root = root();
    Path candidate = path.toAbsolutePath().normalize();
    if (!candidate.startsWith(root)) {
      throw new IllegalArgumentException("Path is outside the Petri workspace");
    }
    Path real = candidate.toRealPath();
    if (!real.startsWith(root)) {
      throw new IllegalArgumentException("Path resolves outside the Petri workspace");
    }
    return real;
  }

  private static void requireSingleSegment(String segment) {
    if (segment == null || segment.isBlank()) {
      throw new IllegalArgumentException("Workspace path segment is required");
    }
    Path value = Path.of(segment);
    if (value.isAbsolute() || value.getNameCount() != 1 || ".".equals(segment) || "..".equals(segment)) {
      throw new IllegalArgumentException("Invalid workspace path segment");
    }
  }
}
