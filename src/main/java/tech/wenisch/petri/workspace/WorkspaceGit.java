package tech.wenisch.petri.workspace;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import tech.wenisch.petri.git.GitCommandResult;
import tech.wenisch.petri.git.GitCredentialMaterial;

/** Git execution on the host; the only place Git is spawned for that host. */
public interface WorkspaceGit {
  GitCommandResult run(Path workingDirectory, List<String> arguments) throws IOException;

  GitCommandResult run(
      Path workingDirectory, List<String> arguments, Map<String, String> environment,
      Collection<String> secrets) throws IOException;

  /**
   * Runs Git with the credential delivered through the host's own askpass helper; the secret never
   * appears on a command line and the helper is removed afterwards.
   */
  GitCommandResult runWithCredential(
      Path workingDirectory, List<String> arguments, GitCredentialMaterial credential)
      throws IOException;
}
