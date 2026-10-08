package tech.wenisch.petri.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "petri")
public record PetriProperties(Security security, Secrets secrets, Workspace workspace) {
  public PetriProperties {
    security = security == null ? new Security(null, null) : security;
    secrets = secrets == null ? new Secrets(null, null) : secrets;
    workspace = workspace == null
        ? new Workspace(null, null, null, null, null, null, null, null)
        : workspace;
  }

  public record Security(String initialAdminEmail, String initialAdminPassword) {
    public Security {
      initialAdminEmail = blankToDefault(initialAdminEmail, "admin@petri.local");
      initialAdminPassword = blankToDefault(initialAdminPassword, "admin");
    }

    private static String blankToDefault(String value, String fallback) {
      return value == null || value.isBlank() ? fallback : value;
    }
  }

  public record Secrets(String masterKey, String masterKeyFile) {
    public Secrets {
      masterKeyFile = masterKeyFile == null || masterKeyFile.isBlank()
          ? "./data/petri-master.key"
          : masterKeyFile;
    }

    public boolean isConfigured() {
      return masterKey != null && !masterKey.isBlank();
    }
  }

  public record Workspace(
      String root,
      Duration gitCommandTimeout,
      Integer maxCommandOutputBytes,
      Integer maxToolFileBytes,
      Integer maxToolOutputBytes,
      Integer maxSearchResults,
      Duration testCommandTimeout,
      Integer maxTestOutputBytes) {
    public Workspace {
      root = root == null || root.isBlank() ? "./data/workspaces" : root;
      gitCommandTimeout = gitCommandTimeout == null ? Duration.ofMinutes(2) : gitCommandTimeout;
      maxCommandOutputBytes = maxCommandOutputBytes == null ? 1_000_000 : maxCommandOutputBytes;
      maxToolFileBytes = maxToolFileBytes == null ? 1_000_000 : maxToolFileBytes;
      maxToolOutputBytes = maxToolOutputBytes == null ? 1_000_000 : maxToolOutputBytes;
      maxSearchResults = maxSearchResults == null ? 200 : maxSearchResults;
      testCommandTimeout = testCommandTimeout == null ? Duration.ofMinutes(10) : testCommandTimeout;
      maxTestOutputBytes = maxTestOutputBytes == null ? 2_000_000 : maxTestOutputBytes;
      if (gitCommandTimeout.isZero() || gitCommandTimeout.isNegative()) {
        throw new IllegalArgumentException("Git command timeout must be positive");
      }
      if (maxCommandOutputBytes < 1024) {
        throw new IllegalArgumentException("Git command output limit must be at least 1024 bytes");
      }
      if (maxToolFileBytes < 1024 || maxToolOutputBytes < 1024) {
        throw new IllegalArgumentException("Agent tool byte limits must be at least 1024");
      }
      if (maxSearchResults < 1) {
        throw new IllegalArgumentException("Maximum search results must be positive");
      }
      if (testCommandTimeout.isZero() || testCommandTimeout.isNegative()) {
        throw new IllegalArgumentException("Test command timeout must be positive");
      }
      if (maxTestOutputBytes < 1024) {
        throw new IllegalArgumentException("Test command output limit must be at least 1024 bytes");
      }
    }
  }
}
