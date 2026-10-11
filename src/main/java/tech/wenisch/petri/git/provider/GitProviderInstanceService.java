package tech.wenisch.petri.git.provider;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.secret.SecretCipher;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;

@Service
public class GitProviderInstanceService {
  private final GitProviderInstanceRepository instances;
  private final AppUserRepository users;
  private final SecretCipher cipher;
  private final AuditLogService audit;

  public GitProviderInstanceService(
      GitProviderInstanceRepository instances,
      AppUserRepository users,
      SecretCipher cipher,
      AuditLogService audit) {
    this.instances = instances;
    this.users = users;
    this.cipher = cipher;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public List<GitProviderInstance> manageable(String actorEmail) {
    AppUser actor = actor(actorEmail);
    return isAdmin(actor)
        ? instances.findByOwnerIdIsNullOrderByDisplayNameAsc()
        : instances.findByOwnerIdOrderByDisplayNameAsc(actor.getId());
  }

  @Transactional(readOnly = true)
  public List<GitProviderInstance> enabledFor(String actorEmail) {
    return instances.findEnabledVisibleTo(actor(actorEmail).getId());
  }

  @Transactional(readOnly = true)
  public GitProviderInstance requireVisible(UUID id, String actorEmail) {
    return requireForOwner(id, actor(actorEmail).getId());
  }

  @Transactional(readOnly = true)
  public GitProviderInstance requireForOwner(UUID id, UUID ownerId) {
    return instances.findVisibleById(id, ownerId).orElseThrow(GitProviderInstanceService::notFound);
  }

  @Transactional
  public GitProviderInstance save(SaveGitProviderInstanceCommand command, String actorEmail) {
    AppUser actor = actor(actorEmail);
    String name = required(command.displayName(), "Display name", 120);
    String web = endpoint(command.webBaseUrl(), "Web URL");
    String api = endpoint(command.apiBaseUrl(), "API URL");
    String authorization = optionalEndpoint(command.oauthAuthorizationUrl(), "OAuth authorization URL");
    String token = optionalEndpoint(command.oauthTokenUrl(), "OAuth token URL");
    Instant now = Instant.now();
    GitProviderInstance instance;
    if (command.id() == null) {
      UUID ownerId = isAdmin(actor) ? null : actor.getId();
      rejectDuplicate(ownerId, web, null);
      instance = new GitProviderInstance(UUID.randomUUID(), command.providerType(), name, web, api,
          authorization, token, limited(command.oauthScopes(), 1000), command.enabled(), ownerId, now);
      instance.update(name, web, api, authorization, token, limited(command.oauthScopes(), 1000),
          limited(command.oauthClientId(), 500), secret(command.oauthClientSecret()),
          command.enabled(), now);
    } else {
      instance = instances.findById(command.id()).orElseThrow(GitProviderInstanceService::notFound);
      assertManageable(instance, actor);
      rejectDuplicate(instance.getOwnerId(), web, instance.getId());
      instance.update(name, web, api, authorization, token, limited(command.oauthScopes(), 1000),
          limited(command.oauthClientId(), 500), secret(command.oauthClientSecret()),
          command.enabled(), now);
    }
    instances.save(instance);
    audit.record(actorEmail, "GIT_PROVIDER_SAVED", "GIT_PROVIDER", instance.getId(),
        "Saved " + (instance.isGlobal() ? "global" : "personal")
            + " Git provider " + instance.getDisplayName());
    return instance;
  }

  String clientSecret(GitProviderInstance instance) {
    if (!instance.isOauthConfigured()) throw new IllegalArgumentException("OAuth is not configured");
    return cipher.decrypt(instance.oauthClientSecretCiphertext());
  }

  private String secret(String value) {
    return value == null || value.isBlank() ? null : cipher.encrypt(value.trim());
  }

  private void rejectDuplicate(UUID ownerId, String webBaseUrl, UUID currentId) {
    var duplicate = ownerId == null
        ? instances.findByOwnerIdIsNullAndWebBaseUrlIgnoreCase(webBaseUrl)
        : instances.findByOwnerIdAndWebBaseUrlIgnoreCase(ownerId, webBaseUrl);
    if (duplicate.isPresent() && !duplicate.get().getId().equals(currentId)) {
      throw new IllegalArgumentException("This Git instance is already configured");
    }
  }

  private static void assertManageable(GitProviderInstance instance, AppUser actor) {
    if (instance.isGlobal()) {
      if (!isAdmin(actor)) throw notFound();
      return;
    }
    if (!instance.getOwnerId().equals(actor.getId())) throw notFound();
  }

  private AppUser actor(String email) {
    return users.findByEmailIgnoreCase(email)
        .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
  }

  private static boolean isAdmin(AppUser actor) {
    return "INSTANCE_ADMIN".equals(actor.getRole());
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");
  }

  private static String endpoint(String value, String label) {
    String normalized = required(value, label, 2048).replaceFirst("/+$", "");
    AbstractHttpGitProviderAdapter.validateEndpoint(URI.create(normalized));
    return normalized;
  }

  private static String optionalEndpoint(String value, String label) {
    return value == null || value.isBlank() ? null : endpoint(value, label);
  }

  private static String required(String value, String label, int length) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
    String result = value.trim();
    if (result.length() > length) throw new IllegalArgumentException(label + " is too long");
    return result;
  }

  private static String limited(String value, int length) {
    if (value == null || value.isBlank()) return null;
    String result = value.trim();
    if (result.length() > length) throw new IllegalArgumentException("Value is too long");
    return result;
  }

  public record SaveGitProviderInstanceCommand(
      UUID id,
      GitProviderType providerType,
      String displayName,
      String webBaseUrl,
      String apiBaseUrl,
      String oauthAuthorizationUrl,
      String oauthTokenUrl,
      String oauthScopes,
      String oauthClientId,
      String oauthClientSecret,
      boolean enabled) {}
}
