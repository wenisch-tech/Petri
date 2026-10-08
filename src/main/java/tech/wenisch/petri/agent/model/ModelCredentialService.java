package tech.wenisch.petri.agent.model;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.secret.SecretCipher;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;

@Service
public class ModelCredentialService {
  private static final int MAX_API_KEY_LENGTH = 12000;

  private final ModelProviderCredentialRepository credentials;
  private final AppUserRepository users;
  private final SecretCipher cipher;
  private final AuditLogService auditLog;
  private final Clock clock;

  @Autowired
  public ModelCredentialService(
      ModelProviderCredentialRepository credentials,
      AppUserRepository users,
      SecretCipher cipher,
      AuditLogService auditLog) {
    this(credentials, users, cipher, auditLog, Clock.systemUTC());
  }

  ModelCredentialService(
      ModelProviderCredentialRepository credentials,
      AppUserRepository users,
      SecretCipher cipher,
      AuditLogService auditLog,
      Clock clock) {
    this.credentials = credentials;
    this.users = users;
    this.cipher = cipher;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public ModelCredentialStatus status(ModelProvider provider) {
    return credentials.findById(provider)
        .map(value -> new ModelCredentialStatus(
            provider, true, value.model(), value.updatedAt()))
        .orElseGet(() -> ModelCredentialStatus.missing(provider));
  }

  @Transactional(readOnly = true)
  ModelCredentialMaterial requireMaterial(ModelProvider provider) {
    ModelProviderCredential credential = credentials.findById(provider)
        .orElseThrow(() -> new IllegalStateException(provider + " credentials are not configured"));
    return new ModelCredentialMaterial(
        provider, cipher.decrypt(credential.apiKeyCiphertext()), credential.model());
  }

  @Transactional
  public ModelCredentialStatus save(
      ModelProvider provider, SaveModelCredentialCommand command, String actorEmail) {
    if (provider == null) throw new IllegalArgumentException("Model provider is required");
    AppUser actor = actor(actorEmail);
    String apiKey = required(command.apiKey(), "API key", MAX_API_KEY_LENGTH);
    String model = required(command.model(), "Model", 200).strip();
    String encrypted = cipher.encrypt(apiKey);
    Instant now = Instant.now(clock);
    ModelProviderCredential credential = credentials.findById(provider).orElse(null);
    String action;
    if (credential == null) {
      credential = new ModelProviderCredential(provider, encrypted, model, actor.getId(), now);
      action = "MODEL_CREDENTIAL_CREATED";
    } else {
      credential.replace(encrypted, model, actor.getId(), now);
      action = "MODEL_CREDENTIAL_REPLACED";
    }
    credentials.save(credential);
    auditLog.record(
        actor.getEmail(), action, "MODEL_PROVIDER_CREDENTIAL", auditTarget(provider),
        "Configured credentials for " + provider);
    return new ModelCredentialStatus(provider, true, model, now);
  }

  @Transactional
  public void remove(ModelProvider provider, String actorEmail) {
    AppUser actor = actor(actorEmail);
    credentials.findById(provider).ifPresent(credential -> {
      credentials.delete(credential);
      auditLog.record(
          actor.getEmail(), "MODEL_CREDENTIAL_REMOVED", "MODEL_PROVIDER_CREDENTIAL",
          auditTarget(provider),
          "Removed credentials for " + provider);
    });
  }

  private AppUser actor(String actorEmail) {
    return users.findByEmailIgnoreCase(actorEmail)
        .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
  }

  private static String required(String value, String label, int maxLength) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
    if (value.length() > maxLength) throw new IllegalArgumentException(label + " is too long");
    return value;
  }

  private static java.util.UUID auditTarget(ModelProvider provider) {
    return java.util.UUID.nameUUIDFromBytes(
        ("MODEL_PROVIDER_CREDENTIAL:" + provider).getBytes(StandardCharsets.UTF_8));
  }
}
