package tech.wenisch.petri.kubernetes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Finds the workspace agent binary for a pod's CPU architecture. */
@Component
class AgentBinaries {
  static final String FILE = "petri-workspace-agent";
  private final List<Path> directories;

  AgentBinaries(@Value("${petri.kubernetes.agent-dir:/opt/petri/workspace-agent}") String directory) {
    // The second place is where scripts/build-workspace-agent.sh writes for development.
    this.directories = List.of(Path.of(directory), Path.of("data/workspace-agent"));
  }

  /** Maps {@code uname -m} to the Go architecture name. */
  static String architecture(String unameMachine) {
    String value = unameMachine == null ? "" : unameMachine.strip().toLowerCase(java.util.Locale.ROOT);
    return switch (value) {
      case "x86_64", "amd64" -> "amd64";
      case "aarch64", "arm64" -> "arm64";
      default -> throw new WorkspaceHostUnavailableException(
          "The workspace pod runs on an unsupported CPU architecture (" + value
              + "). Petri ships its workspace agent for amd64 and arm64.");
    };
  }

  byte[] binary(String architecture) {
    for (Path directory : directories) {
      for (String name : new String[] {architecture, "dev"}) {
        Path file = directory.resolve(name).resolve(FILE);
        if (Files.isRegularFile(file)) {
          try {
            return Files.readAllBytes(file);
          } catch (IOException exception) {
            throw new WorkspaceHostUnavailableException(
                "The workspace agent could not be read from " + file, exception);
          }
        }
      }
    }
    throw new WorkspaceHostUnavailableException("The workspace agent for " + architecture
        + " is not installed in this Petri instance (looked in " + directories + "). The official "
        + "image includes it; for development run scripts/build-workspace-agent.sh and set "
        + "PETRI_KUBERNETES_AGENT_DIR.");
  }
}
