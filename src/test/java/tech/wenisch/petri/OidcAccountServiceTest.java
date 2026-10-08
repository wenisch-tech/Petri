package tech.wenisch.petri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.audit.AuditEventRepository;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;
import tech.wenisch.petri.security.oidc.OidcAccountService;
import tech.wenisch.petri.security.oidc.PetriOidcUser;
import tech.wenisch.petri.security.oidc.UserIdentity;
import tech.wenisch.petri.security.oidc.UserIdentityRepository;

@SpringBootTest
@Transactional
class OidcAccountServiceTest {
  private static final String ADMIN_EMAIL = "test-admin@petri.local";

  @Autowired OidcAccountService accounts;
  @Autowired AppUserRepository users;
  @Autowired UserIdentityRepository identities;
  @Autowired AuditEventRepository auditEvents;

  @Test
  void verifiedIdentityCreatesAndThenReusesALocalUser() {
    AppUser first = accounts.resolveUser(
        "keycloak", oidcUser("subject-17", "new.user@petri.local", true));

    assertThat(first.getEmail()).isEqualTo("new.user@petri.local");
    assertThat(first.getRole()).isEqualTo("USER");
    assertThat(first.hasLocalPassword()).isFalse();
    UserIdentity identity = identities
        .findByProviderRegistrationIdAndSubject("keycloak", "subject-17")
        .orElseThrow();
    assertThat(identity.getUserId()).isEqualTo(first.getId());

    AppUser second = accounts.resolveUser(
        "keycloak", oidcUser("subject-17", "changed.email@petri.local", true));
    assertThat(second.getId()).isEqualTo(first.getId());
    assertThat(users.findByEmailIgnoreCase("changed.email@petri.local")).isEmpty();
    assertThat(auditEvents.findTop100ByOrderByOccurredAtDesc())
        .anySatisfy(event -> assertThat(event.getAction()).isEqualTo("OIDC_ACCOUNT_LINKED"));
  }

  @Test
  void verifiedIdentityLinksExistingAdminWithoutChangingItsRole() {
    AppUser admin = users.findByEmailIgnoreCase(ADMIN_EMAIL).orElseThrow();

    AppUser linked = accounts.resolveUser(
        "keycloak", oidcUser("admin-subject", ADMIN_EMAIL, true));
    PetriOidcUser principal = new PetriOidcUser(
        oidcUser("admin-subject", ADMIN_EMAIL, true), linked);

    assertThat(linked.getId()).isEqualTo(admin.getId());
    assertThat(linked.getRole()).isEqualTo("INSTANCE_ADMIN");
    assertThat(linked.hasLocalPassword()).isTrue();
    assertThat(principal.getName()).isEqualTo(ADMIN_EMAIL);
    assertThat(principal.getAuthorities())
        .extracting(authority -> authority.getAuthority())
        .contains("ROLE_INSTANCE_ADMIN");
  }

  @Test
  void unverifiedEmailCannotCreateOrLinkAnAccount() {
    assertThatThrownBy(() -> accounts.resolveUser(
        "keycloak", oidcUser("unverified-subject", "unverified@petri.local", false)))
        .isInstanceOf(OAuth2AuthenticationException.class);

    assertThat(users.findByEmailIgnoreCase("unverified@petri.local")).isEmpty();
    assertThat(identities.findByProviderRegistrationIdAndSubject("keycloak", "unverified-subject"))
        .isEmpty();
  }

  private OidcUser oidcUser(String subject, String email, boolean emailVerified) {
    Instant now = Instant.now();
    OidcIdToken idToken = new OidcIdToken(
        "test-token-" + subject,
        now,
        now.plusSeconds(300),
        Map.of(
            "sub", subject,
            "email", email,
            "email_verified", emailVerified,
            "iss", "https://issuer.example.test"));
    return new DefaultOidcUser(AuthorityUtils.createAuthorityList("OIDC_USER"), idToken);
  }
}
