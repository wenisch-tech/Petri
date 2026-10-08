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
