package tech.wenisch.petri.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "petri")
public record PetriProperties(Security security, Secrets secrets, Workspace workspace) {
  public PetriProperties {
    security = security == null ? new Security(null, null) : security;
    secrets = secrets == null ? new Secrets(null) : secrets;
    workspace = workspace == null ? new Workspace(null, null, null) : workspace;
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

  public record Secrets(String masterKey) {
    public boolean isConfigured() {
      return masterKey != null && !masterKey.isBlank();
    }
  }

  public record Workspace(String root, Duration gitCommandTimeout, Integer maxCommandOutputBytes) {
    public Workspace {
      root = root == null || root.isBlank() ? "./data/workspaces" : root;
      gitCommandTimeout = gitCommandTimeout == null ? Duration.ofMinutes(2) : gitCommandTimeout;
      maxCommandOutputBytes = maxCommandOutputBytes == null ? 1_000_000 : maxCommandOutputBytes;
      if (gitCommandTimeout.isZero() || gitCommandTimeout.isNegative()) {
        throw new IllegalArgumentException("Git command timeout must be positive");
      }
      if (maxCommandOutputBytes < 1024) {
        throw new IllegalArgumentException("Git command output limit must be at least 1024 bytes");
      }
    }
  }
}
