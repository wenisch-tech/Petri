package tech.wenisch.petri.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitAskPassSessionTest {
  @TempDir Path temporaryDirectory;

  @Test
  void keepsSecretsOutOfHelperFileAndDeletesItAfterUse() throws Exception {
    GitCredentialMaterial credential =
        new GitCredentialMaterial(GitAuthenticationType.TOKEN, null, "high-value-token");
    Path helper;

    try (GitAskPassSession session = new GitAskPassSession(temporaryDirectory, credential)) {
      helper = Path.of(session.environment().get("GIT_ASKPASS"));
      assertThat(Files.readString(helper)).doesNotContain("high-value-token");
      assertThat(invoke(helper, "Username for 'https://git.example.test':", session))
          .isEqualTo("git");
      assertThat(invoke(helper, "Password for 'https://git.example.test':", session))
          .isEqualTo("high-value-token");
    }

    assertThat(helper).doesNotExist();
  }

  private static String invoke(Path helper, String prompt, GitAskPassSession session)
      throws Exception {
    ProcessBuilder builder = new ProcessBuilder(helper.toString(), prompt);
    builder.environment().putAll(session.environment());
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes(),
        java.nio.charset.StandardCharsets.UTF_8).strip();
    assertThat(process.waitFor()).isZero();
    return output;
  }
}
