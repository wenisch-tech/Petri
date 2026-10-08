package tech.wenisch.petri.apikey;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;

@Service
public class ApiKeyService {
  private static final String TOKEN_PREFIX = "petri_pk_";
  private static final SecureRandom RANDOM = new SecureRandom();

  private final ApiKeyRepository keys;
  private final AppUserRepository users;
  private final PasswordEncoder passwordEncoder;
  private final AuditLogService auditLog;

  public ApiKeyService(
      ApiKeyRepository keys,
      AppUserRepository users,
      PasswordEncoder passwordEncoder,
      AuditLogService auditLog) {
    this.keys = keys;
    this.users = users;
    this.passwordEncoder = passwordEncoder;
    this.auditLog = auditLog;
  }

  @Transactional
  public IssuedApiKey create(CreateApiKeyCommand command, String actorEmail) {
    String name = requiredName(command.name());
    AppUser actor = users.findByEmailIgnoreCase(actorEmail)
        .orElseThrow(() -> new IllegalArgumentException("Unknown API key owner"));
    Instant now = Instant.now();
    Instant expiresAt = expiration(command.expiresOn(), now);

    String identifier = nextIdentifier();
    String rawKey = TOKEN_PREFIX + identifier + "." + randomToken(32);
    ApiKey key = new ApiKey(
        UUID.randomUUID(),
        name,
        identifier,
        passwordEncoder.encode(rawKey),
        actor.getId(),
        now,
        expiresAt);
    keys.save(key);
    auditLog.record(
        actor.getEmail(),
        "API_KEY_CREATED",
        "API_KEY",
        key.getId(),
        "Created API key " + key.getName());
    return new IssuedApiKey(key.getId(), key.getName(), rawKey, identifier, expiresAt);
  }

  @Transactional
  public Optional<ApiKeyPrincipal> authenticate(String rawKey) {
    TokenParts token = TokenParts.parse(rawKey).orElse(null);
    if (token == null) return Optional.empty();
    ApiKey key = keys.findByKeyIdentifier(token.identifier()).orElse(null);
    Instant now = Instant.now();
    if (key == null || !key.isActiveAt(now) || !passwordEncoder.matches(rawKey, key.secretHash())) {
      return Optional.empty();
    }
    key.markUsed(now);
    return Optional.of(new ApiKeyPrincipal(
        key.getId(), key.getName(), key.getCreatedBy(), key.getExpiresAt()));
  }

  @Transactional
  public boolean revoke(UUID keyId, String actorEmail) {
    AppUser actor = users.findByEmailIgnoreCase(actorEmail)
        .orElseThrow(() -> new IllegalArgumentException("Unknown API key revoker"));
    ApiKey key = keys.findById(keyId)
        .orElseThrow(() -> new IllegalArgumentException("API key does not exist"));
    boolean revoked = key.revoke(Instant.now(), actor.getId());
    if (revoked) {
      auditLog.record(
          actor.getEmail(),
          "API_KEY_REVOKED",
          "API_KEY",
          key.getId(),
          "Revoked API key " + key.getName());
    }
    return revoked;
  }

  public java.util.List<ApiKey> findAll() {
    return keys.findAllByOrderByCreatedAtDesc();
  }

  private String nextIdentifier() {
    String identifier;
    do {
      identifier = randomToken(9);
    } while (keys.existsByKeyIdentifier(identifier));
    return identifier;
  }

  private static String randomToken(int byteCount) {
    byte[] value = new byte[byteCount];
    RANDOM.nextBytes(value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private static String requiredName(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("API key name is required");
    String name = value.trim();
    if (name.length() > 120) throw new IllegalArgumentException("API key name is too long");
    return name;
  }

  private static Instant expiration(LocalDate expiresOn, Instant now) {
    if (expiresOn == null) return null;
    if (expiresOn.isBefore(LocalDate.now(ZoneOffset.UTC))) {
      throw new IllegalArgumentException("API key expiration cannot be in the past");
    }
    Instant expiresAt = expiresOn.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    if (!expiresAt.isAfter(now)) throw new IllegalArgumentException("API key expiration must be in the future");
    return expiresAt;
  }

  private record TokenParts(String identifier) {
    static Optional<TokenParts> parse(String rawKey) {
      if (rawKey == null || !rawKey.startsWith(TOKEN_PREFIX)) return Optional.empty();
      String token = rawKey.substring(TOKEN_PREFIX.length());
      if (token.length() != 56 || token.charAt(12) != '.') {
        return Optional.empty();
      }
      return Optional.of(new TokenParts(token.substring(0, 12)));
    }
  }
}
