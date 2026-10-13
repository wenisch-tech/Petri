package tech.wenisch.petri.security;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.security.oidc.UserIdentity;
import tech.wenisch.petri.security.oidc.UserIdentityRepository;

/** Instance administration of accounts: local users, linked OIDC users, roles and passwords. */
@Service
public class UserAdminService {
  public static final String ADMIN = "INSTANCE_ADMIN";
  public static final String MEMBER = "USER";
  private static final Set<String> ROLES = Set.of(ADMIN, MEMBER);
  private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
  private static final int MIN_PASSWORD = 10;
  private static final int MAX_PASSWORD = 128;

  private final AppUserRepository users;
  private final UserIdentityRepository identities;
  private final PasswordEncoder encoder;
  private final AuditLogService audit;

  public UserAdminService(
      AppUserRepository users, UserIdentityRepository identities, PasswordEncoder encoder,
      AuditLogService audit) {
    this.users = users;
    this.identities = identities;
    this.encoder = encoder;
    this.audit = audit;
  }

  /** One account as the administration page shows it; never carries a password hash. */
  public record UserView(
      UUID id, String email, String role, boolean admin, boolean enabled, boolean local,
      List<String> providers, Instant createdAt, boolean self) {}

  @Transactional(readOnly = true)
  public List<UserView> list(String actorEmail) {
    List<AppUser> all = users.findAllByOrderByEmailAsc();
    Map<UUID, List<String>> providers = identities
        .findByUserIdIn(all.stream().map(AppUser::getId).toList()).stream()
        .collect(Collectors.groupingBy(UserIdentity::getUserId,
            Collectors.mapping(UserIdentity::getProviderRegistrationId, Collectors.toList())));
    List<UserView> views = new ArrayList<>();
    for (AppUser user : all) {
      views.add(new UserView(
          user.getId(), user.getEmail(), user.getRole(), ADMIN.equals(user.getRole()),
          user.isEnabled(), user.hasLocalPassword(),
          providers.getOrDefault(user.getId(), List.of()).stream().distinct().sorted().toList(),
          user.getCreatedAt(), user.getEmail().equalsIgnoreCase(actorEmail)));
    }
    return views;
  }

  @Transactional
  public void create(String email, String password, String role, String actorEmail) {
    String normalized = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    if (!EMAIL.matcher(normalized).matches() || normalized.length() > 320) {
      throw new IllegalArgumentException("Enter a valid email address");
    }
    if (users.findByEmailIgnoreCase(normalized).isPresent()) {
      throw new IllegalArgumentException("A user with this email already exists");
    }
    String checkedRole = requireRole(role);
    AppUser user = new AppUser(UUID.randomUUID(), normalized, encoder.encode(checkedPassword(password)),
        checkedRole);
    users.save(user);
    audit.record(actorEmail, "USER_CREATED", "USER", user.getId(),
        "Created local user " + normalized + " as " + checkedRole);
  }

  @Transactional
  public void changeRole(UUID id, String role, String actorEmail) {
    AppUser user = require(id);
    String checkedRole = requireRole(role);
    if (ADMIN.equals(user.getRole()) && !ADMIN.equals(checkedRole)) requireAnotherAdmin(user);
    user.changeRole(checkedRole);
    users.save(user);
    audit.record(actorEmail, "USER_ROLE_CHANGED", "USER", id,
        "Set role of " + user.getEmail() + " to " + checkedRole);
  }

  @Transactional
  public void setEnabled(UUID id, boolean enabled, String actorEmail) {
    AppUser user = require(id);
    if (!enabled) {
      if (user.getEmail().equalsIgnoreCase(actorEmail)) {
        throw new IllegalStateException("You cannot disable your own account");
      }
      if (ADMIN.equals(user.getRole())) requireAnotherAdmin(user);
    }
    user.changeEnabled(enabled);
    users.save(user);
    audit.record(actorEmail, enabled ? "USER_ENABLED" : "USER_DISABLED", "USER", id,
        (enabled ? "Enabled " : "Disabled ") + user.getEmail());
  }

  @Transactional
  public void setPassword(UUID id, String password, String actorEmail) {
    AppUser user = require(id);
    if (!user.hasLocalPassword()) {
      throw new IllegalStateException(
          "This account signs in through an identity provider and has no local password");
    }
    user.changePassword(encoder.encode(checkedPassword(password)));
    users.save(user);
    audit.record(actorEmail, "USER_PASSWORD_RESET", "USER", id,
        "Set a new password for " + user.getEmail());
  }

  @Transactional
  public void delete(UUID id, String actorEmail) {
    AppUser user = require(id);
    if (user.getEmail().equalsIgnoreCase(actorEmail)) {
      throw new IllegalStateException("You cannot delete your own account");
    }
    if (ADMIN.equals(user.getRole())) requireAnotherAdmin(user);
    identities.deleteAll(identities.findByUserId(id));
    users.delete(user);
    users.flush(); // surfaces foreign-key violations here, not at commit
    audit.record(actorEmail, "USER_DELETED", "USER", id, "Deleted user " + user.getEmail());
  }

  private AppUser require(UUID id) {
    return users.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown user"));
  }

  private void requireAnotherAdmin(AppUser user) {
    long admins = users.countByRoleAndEnabledTrue(ADMIN);
    boolean countsAsAdmin = user.isEnabled() && ADMIN.equals(user.getRole());
    if (admins - (countsAsAdmin ? 1 : 0) < 1) {
      throw new IllegalStateException("Petri needs at least one enabled administrator");
    }
  }

  private static String requireRole(String role) {
    if (role == null || !ROLES.contains(role)) throw new IllegalArgumentException("Unknown role");
    return role;
  }

  private static String checkedPassword(String password) {
    if (password == null || password.length() < MIN_PASSWORD) {
      throw new IllegalArgumentException("The password needs at least " + MIN_PASSWORD + " characters");
    }
    if (password.length() > MAX_PASSWORD) throw new IllegalArgumentException("The password is too long");
    return password;
  }
}
