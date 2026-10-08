package tech.wenisch.petri.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "petri")
public record PetriProperties(Security security) {
  public PetriProperties {
    security = security == null ? new Security(null, null) : security;
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
}

