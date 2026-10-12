package tech.wenisch.petri.security.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class OidcPropertiesTest {
  @Test
  void resolvesMultipleProvidersAndSkipsIncompleteOnes() {
    OidcProperties properties = new OidcProperties();
    properties.setProviders(List.of(
        provider("Company SSO", "", "client-a", "https://sso.example.test/realms/a"),
        provider("", "", "", "https://ignored.example.test"),
        provider("Google", "google", "client-b", "https://accounts.example.test")));

    List<OidcProperties.ResolvedProvider> resolved = properties.resolvedProviders();

    assertThat(resolved).extracting(OidcProperties.ResolvedProvider::registrationId)
        .containsExactly("company-sso", "google");
    assertThat(resolved).extracting(OidcProperties.ResolvedProvider::name)
        .containsExactly("Company SSO", "Google");
  }

  @Test
  void fallsBackToLegacySingleProvider() {
    OidcProperties properties = new OidcProperties();
    properties.setClientId("petri");
    properties.setIssuerUri("https://sso.example.test/realms/a");
    properties.setProviderName("Keycloak");

    assertThat(properties.resolvedProviders()).singleElement().satisfies(provider -> {
      assertThat(provider.name()).isEqualTo("Keycloak");
      assertThat(provider.clientId()).isEqualTo("petri");
    });
  }

  @Test
  void rejectsDuplicateRegistrationIds() {
    OidcProperties properties = new OidcProperties();
    properties.setProviders(List.of(
        provider("A", "same", "c1", "https://a.example.test"),
        provider("B", "same", "c2", "https://b.example.test")));

    assertThatThrownBy(properties::resolvedProviders)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("same");
  }

  private static OidcProperties.Provider provider(
      String name, String registrationId, String clientId, String issuer) {
    OidcProperties.Provider provider = new OidcProperties.Provider();
    provider.setName(name);
    provider.setRegistrationId(registrationId);
    provider.setClientId(clientId);
    provider.setIssuerUri(issuer);
    return provider;
  }
}
