package tech.wenisch.petri.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

final class GitAskPassSession implements AutoCloseable {
  private static final String SCRIPT = """
      #!/bin/sh
      case "$1" in
        *Username*) printf '%s\\n' "$PETRI_GIT_USERNAME" ;;
        *) printf '%s\\n' "$PETRI_GIT_SECRET" ;;
      esac
      """;

  private final Path helper;
  private final Map<String, String> environment;

  GitAskPassSession(Path temporaryDirectory, GitCredentialMaterial credential) throws IOException {
    helper = Files.createTempFile(temporaryDirectory, "petri-git-askpass-", ".sh");
    Files.writeString(helper, SCRIPT);
    try {
      Files.setPosixFilePermissions(helper, PosixFilePermissions.fromString("rwx------"));
    } catch (UnsupportedOperationException exception) {
      if (!helper.toFile().setReadable(false, false)
          || !helper.toFile().setReadable(true, true)
          || !helper.toFile().setWritable(false, false)
          || !helper.toFile().setWritable(true, true)
          || !helper.toFile().setExecutable(false, false)
          || !helper.toFile().setExecutable(true, true)) {
        close();
        throw new IOException("Could not restrict Git askpass helper permissions", exception);
      }
    }
    String username = credential.username();
    if (username == null && credential.authenticationType() == GitAuthenticationType.TOKEN) {
      username = "git";
    }
    environment = Map.of(
        "GIT_ASKPASS", helper.toString(),
        "GIT_ASKPASS_REQUIRE", "force",
        "PETRI_GIT_USERNAME", username == null ? "" : username,
        "PETRI_GIT_SECRET", credential.secret());
  }

  Map<String, String> environment() {
    return environment;
  }

  @Override
  public void close() throws IOException {
    Files.deleteIfExists(helper);
  }
}
