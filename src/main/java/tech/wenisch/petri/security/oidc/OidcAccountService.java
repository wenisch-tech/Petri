package tech.wenisch.petri.security.oidc;

import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;

@Service
public class OidcAccountService {
  private final UserIdentityRepository identities;
  private final AppUserRepository users;
  private final AuditLogService auditLog;
  private final boolean createUsers;

  @Autowired
  public OidcAccountService(
      UserIdentityRepository identities,
      AppUserRepository users,
      AuditLogService auditLog,
      OidcProperties properties) {
    this(identities, users, auditLog, properties.isCreateUsers());
  }

  public OidcAccountService(
      UserIdentityRepository identities,
      AppUserRepository users,
      AuditLogService auditLog,
      boolean createUsers) {
    this.identities = identities;
    this.users = users;
    this.auditLog = auditLog;
    this.createUsers = createUsers;
  }

  @Transactional
  public AppUser resolveUser(String providerRegistrationId, OidcUser oidcUser) {
    String provider = required(providerRegistrationId, "provider registration", 100);
    String subject = required(oidcUser.getSubject(), "subject", 255);
    UserIdentity knownIdentity = identities
        .findByProviderRegistrationIdAndSubject(provider, subject)
        .orElse(null);
    if (knownIdentity != null) {
      AppUser user = users.findById(knownIdentity.getUserId())
          .orElseThrow(() -> rejected("The linked Petri account no longer exists"));
      if (!user.isEnabled()) throw rejected("This Petri account is disabled");
      auditLogin(user, provider);
      return user;
    }

    if (!Boolean.TRUE.equals(oidcUser.getEmailVerified())) {
      throw rejected("OIDC login requires a verified email address");
    }
    String email = required(oidcUser.getEmail(), "verified email", 320);
    AppUser user = users.findByEmailIgnoreCase(email).orElse(null);
    if (user == null) {
      if (!createUsers) throw rejected("No Petri account exists for this identity");
      user = users.save(AppUser.oidcUser(UUID.randomUUID(), email, "USER"));
    }
    if (!user.isEnabled()) throw rejected("This Petri account is disabled");
    identities.save(new UserIdentity(UUID.randomUUID(), user.getId(), provider, subject, Instant.now()));
    auditLog.record(
        user.getEmail(),
        "OIDC_ACCOUNT_LINKED",
        "USER",
        user.getId(),
        "Linked OIDC provider " + provider);
    auditLogin(user, provider);
    return user;
  }

  private void auditLogin(AppUser user, String provider) {
    auditLog.record(
        user.getEmail(),
        "OIDC_LOGIN",
        "USER",
        user.getId(),
        "Signed in through OIDC provider " + provider);
  }

  private static String required(String value, String label, int maxLength) {
    if (value == null || value.isBlank()) throw rejected("OIDC " + label + " is required");
    String trimmed = value.trim();
    if (trimmed.length() > maxLength) throw rejected("OIDC " + label + " is too long");
    return trimmed;
  }

  private static OAuth2AuthenticationException rejected(String message) {
    return new OAuth2AuthenticationException(new OAuth2Error("invalid_token"), message);
  }
}
