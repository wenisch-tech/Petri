package tech.wenisch.petri.security.oidc;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * OIDC sign-in configuration ({@code OIDC_*} environment variables). Supports several providers
 * through {@code OIDC_PROVIDERS_<n>_*} plus a legacy single-provider form
 * ({@code OIDC_CLIENT_ID}, {@code OIDC_ISSUER_URI}, ...).
 */
@Component
@ConfigurationProperties(prefix = "oidc")
public class OidcProperties {
  private boolean enabled;
  private boolean insecureSkipTlsVerify;
  private boolean createUsers = true;
  private String providerName = "Single Sign-On";
  private String clientId = "";
  private String clientSecret = "";
  private String issuerUri = "";
  private List<Provider> providers = new ArrayList<>();

  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean enabled) { this.enabled = enabled; }
  public boolean isInsecureSkipTlsVerify() { return insecureSkipTlsVerify; }
  public void setInsecureSkipTlsVerify(boolean value) { this.insecureSkipTlsVerify = value; }
  public boolean isCreateUsers() { return createUsers; }
  public void setCreateUsers(boolean createUsers) { this.createUsers = createUsers; }
  public String getProviderName() { return providerName; }
  public void setProviderName(String providerName) { this.providerName = providerName; }
  public String getClientId() { return clientId; }
  public void setClientId(String clientId) { this.clientId = clientId; }
  public String getClientSecret() { return clientSecret; }
  public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
  public String getIssuerUri() { return issuerUri; }
  public void setIssuerUri(String issuerUri) { this.issuerUri = issuerUri; }
  public List<Provider> getProviders() { return providers; }
  public void setProviders(List<Provider> providers) {
    this.providers = providers != null ? providers : new ArrayList<>();
  }

  public List<ResolvedProvider> resolvedProviders() {
    List<ResolvedProvider> resolved = new ArrayList<>();
    int index = 1;
    for (Provider provider : providers) {
      if (!hasText(provider.clientId) || !hasText(provider.issuerUri)) continue;
      resolved.add(new ResolvedProvider(
          provider.resolveName(index),
          provider.resolveRegistrationId(index),
          provider.clientId.trim(),
          trim(provider.clientSecret),
          provider.issuerUri.trim(),
          hasText(provider.userNameAttribute) ? provider.userNameAttribute.trim() : "sub"));
      index++;
    }
    if (resolved.isEmpty() && hasText(clientId) && hasText(issuerUri)) {
      resolved.add(new ResolvedProvider(
          hasText(providerName) ? providerName.trim() : "Single Sign-On",
          "oidc",
          clientId.trim(),
          trim(clientSecret),
          issuerUri.trim(),
          "sub"));
    }
    Set<String> ids = new LinkedHashSet<>();
    for (ResolvedProvider provider : resolved) {
      if (!ids.add(provider.registrationId())) {
        throw new IllegalStateException(
            "Duplicate OIDC registrationId configured: " + provider.registrationId());
      }
    }
    return List.copyOf(resolved);
  }

  private static boolean hasText(String value) { return value != null && !value.isBlank(); }

  private static String trim(String value) { return value == null ? "" : value.trim(); }

  public static class Provider {
    private String name = "";
    private String registrationId = "";
    private String clientId = "";
    private String clientSecret = "";
    private String issuerUri = "";
    private String userNameAttribute = "sub";

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getRegistrationId() { return registrationId; }
    public void setRegistrationId(String registrationId) { this.registrationId = registrationId; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    public String getIssuerUri() { return issuerUri; }
    public void setIssuerUri(String issuerUri) { this.issuerUri = issuerUri; }
    public String getUserNameAttribute() { return userNameAttribute; }
    public void setUserNameAttribute(String userNameAttribute) {
      this.userNameAttribute = userNameAttribute;
    }

    private String resolveName(int fallbackIndex) {
      return hasText(name) ? name.trim() : "Provider " + fallbackIndex;
    }

    private String resolveRegistrationId(int fallbackIndex) {
      if (hasText(registrationId)) return sanitize(registrationId);
      if (hasText(name)) return sanitize(name);
      return "provider-" + fallbackIndex;
    }

    private static String sanitize(String raw) {
      String sanitized = raw.trim().toLowerCase(Locale.ROOT)
          .replaceAll("[^a-z0-9]+", "-")
          .replaceAll("^-+|-+$", "");
      return sanitized.isBlank() ? "provider-1" : sanitized;
    }
  }

  public record ResolvedProvider(
      String name,
      String registrationId,
      String clientId,
      String clientSecret,
      String issuerUri,
      String userNameAttribute) {}
}
