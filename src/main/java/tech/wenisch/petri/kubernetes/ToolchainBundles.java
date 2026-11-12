package tech.wenisch.petri.kubernetes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Finds the toolchain bundle built into the Petri image (see docker/workspace-toolchain). One
 * compressed archive exists per CPU architecture; its version changes whenever its content does.
 */
@Component
class ToolchainBundles {
  static final String FILE = "toolchain.tar.gz";
  static final String VERSION_FILE = "toolchain.version";

  /** A bundle on disk. */
  record Bundle(Path file, String version, long size) {
    java.io.InputStream open() throws IOException {
      return Files.newInputStream(file);
    }
  }

  private final List<Path> directories;

  @Autowired
  ToolchainBundles(@Value("${petri.kubernetes.toolchain-dir:/opt/petri/workspace-toolchain}") String directory) {
    // The second place is where scripts/build-workspace-toolchain.sh writes for development.
    this(List.of(Path.of(directory), Path.of("data/workspace-toolchain")));
  }

  /** Only these directories are searched; for tests. */
  ToolchainBundles(List<Path> directories) {
    this.directories = List.copyOf(directories);
  }

  /** The bundle for a Go architecture name (amd64 or arm64), if this Petri instance has one. */
  Optional<Bundle> find(String architecture) {
    for (Path directory : directories) {
      Path file = directory.resolve(architecture).resolve(FILE);
      Path versionFile = directory.resolve(architecture).resolve(VERSION_FILE);
      if (!Files.isRegularFile(file) || !Files.isRegularFile(versionFile)) continue;
      try {
        String version = Files.readString(versionFile, StandardCharsets.UTF_8).strip();
        if (!version.isEmpty()) return Optional.of(new Bundle(file, version, Files.size(file)));
      } catch (IOException ignored) {
        // unreadable: treat as absent
      }
    }
    return Optional.empty();
  }
}
