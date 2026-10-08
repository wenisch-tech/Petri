package tech.wenisch.petri.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "petri")
public record PetriProperties(Security security, Secrets secrets) {
  public PetriProperties {
    security = security == null ? new Security(null, null) : security;
    secrets = secrets == null ? new Secrets(null) : secrets;
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
}
