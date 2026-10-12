package tech.wenisch.petri.security.oidc;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

/**
 * Builds client registrations from {@code OIDC_*} settings. When {@code OIDC_ENABLED} is not set,
 * Spring Boot's standard {@code spring.security.oauth2.client.*} properties keep working.
 */
@Configuration
public class OidcConfiguration {
  @Bean
  @ConditionalOnProperty(name = "oidc.enabled", havingValue = "true")
  ClientRegistrationRepository clientRegistrationRepository(
      OidcProperties properties, OidcHttpSupport http) {
    List<OidcProperties.ResolvedProvider> providers = properties.resolvedProviders();
    if (providers.isEmpty()) {
      throw new IllegalStateException("OIDC is enabled but no valid OIDC provider is configured.");
    }
    List<ClientRegistration> registrations = new ArrayList<>();
    for (OidcProperties.ResolvedProvider provider : providers) {
      ClientRegistration.Builder builder = http.insecure()
          ? ClientRegistrations.fromOidcConfiguration(http.discover(provider.issuerUri()))
          : ClientRegistrations.fromIssuerLocation(provider.issuerUri());
      builder.registrationId(provider.registrationId())
          .clientName(provider.name())
          .clientId(provider.clientId())
          .scope("openid", "profile", "email")
          .userNameAttributeName(provider.userNameAttribute())
          .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
          .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}");
      if (!provider.clientSecret().isBlank()) builder.clientSecret(provider.clientSecret());
      registrations.add(builder.build());
    }
    return new InMemoryClientRegistrationRepository(registrations);
  }
}
