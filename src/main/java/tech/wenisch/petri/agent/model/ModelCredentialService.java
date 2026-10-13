package tech.wenisch.petri.agent.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.config.PetriProperties;
import tech.wenisch.petri.secret.SecretCipher;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;

/**
 * Model provider instances and the keys used to call them.
 *
 * <p>Administrators define global instances; any number may share a kind. A user supplies their own
 * API key per instance, which takes precedence over an optional shared key on the instance. When
 * the administrator allows it, users may also define personal instances that only they can use.
 * Methods taking only a {@link ModelProvider} address the oldest global instance of that kind and
 * exist for rows and clients that predate instances.
 */
@Service
public class ModelCredentialService {
  private static final int MAX_API_KEY_LENGTH = 12000;
  private static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 32768;
  private static final int MIN_CONTEXT_INPUT_RESERVE = 2048;
  private static final String ADMIN_ROLE = "INSTANCE_ADMIN";
  private static final String TARGET_TYPE = "MODEL_PROVIDER_INSTANCE";

  private final ModelProviderInstanceRepository instances;
  private final ModelUserApiKeyRepository userKeys;
  private final ModelProviderPolicyRepository policies;
  private final AppUserRepository users;
  private final SecretCipher cipher;
  private final AuditLogService auditLog;
  private final int defaultMaxOutputTokens;
  private final String defaultOpenAiBaseUrl;
  private final Clock clock;

  @Autowired
  public ModelCredentialService(
      ModelProviderInstanceRepository instances,
      ModelUserApiKeyRepository userKeys,
      ModelProviderPolicyRepository policies,
      AppUserRepository users,
      SecretCipher cipher,
      AuditLogService auditLog,
      PetriProperties properties) {
    this(
        instances,
        userKeys,
        policies,
        users,
        cipher,
        auditLog,
        properties.agent().maxOutputTokens(),
        properties.agent().openAiBaseUrl(),
        Clock.systemUTC());
  }

  ModelCredentialService(
      ModelProviderInstanceRepository instances,
      ModelUserApiKeyRepository userKeys,
      ModelProviderPolicyRepository policies,
      AppUserRepository users,
      SecretCipher cipher,
      AuditLogService auditLog,
      int defaultMaxOutputTokens,
      String defaultOpenAiBaseUrl,
      Clock clock) {
    this.instances = instances;
    this.userKeys = userKeys;
    this.policies = policies;
    this.users = users;
    this.cipher = cipher;
    this.auditLog = auditLog;
    this.defaultMaxOutputTokens = defaultMaxOutputTokens;
    this.defaultOpenAiBaseUrl = defaultOpenAiBaseUrl;
    this.clock = clock;
  }

  // ---------------------------------------------------------------------------------------------
  // Catalogue
  // ---------------------------------------------------------------------------------------------

  /** Every global instance, including disabled ones. Administrators only. */
  @Transactional(readOnly = true)
  public List<ModelCredentialStatus> globalInstances(String actorEmail) {
    AppUser actor = requireAdmin(actor(actorEmail));
    Set<UUID> own = keyedInstances(actor.getId());
    return instances.findByOwnerIdIsNullOrderByDisplayNameAsc().stream()
        .map(value -> toStatus(value, own.contains(value.id())))
        .toList();
  }

  /** The viewer's own instances, regardless of the policy so they can still be listed and removed. */
  @Transactional(readOnly = true)
  public List<ModelCredentialStatus> ownInstances(String actorEmail) {
    AppUser actor = actor(actorEmail);
    Set<UUID> own = keyedInstances(actor.getId());
    return instances.findByOwnerIdOrderByDisplayNameAsc(actor.getId()).stream()
        .map(value -> toStatus(value, own.contains(value.id())))
        .toList();
  }

  /**
   * Instances the viewer may select: enabled global instances, plus their own while personal
   * providers are allowed. Unusable ones (missing key) are included so the UI can ask for a key.
   */
  @Transactional(readOnly = true)
  public List<ModelCredentialStatus> catalog(String actorEmail) {
    return catalogFor(actor(actorEmail));
  }

  private List<ModelCredentialStatus> catalogFor(AppUser actor) {
    Set<UUID> own = keyedInstances(actor.getId());
    List<ModelProviderInstance> visible = new ArrayList<>();
    instances.findByOwnerIdIsNullOrderByDisplayNameAsc().stream()
        .filter(ModelProviderInstance::enabled)
        .forEach(visible::add);
    if (allowUserProviders()) {
      instances.findByOwnerIdOrderByDisplayNameAsc(actor.getId()).stream()
          .filter(ModelProviderInstance::enabled)
          .forEach(visible::add);
    }
    return visible.stream().map(value -> toStatus(value, own.contains(value.id()))).toList();
  }

  /** The instances a user can call right now, addressed by user id. */
  @Transactional(readOnly = true)
  public List<ModelCredentialStatus> usable(UUID userId) {
    AppUser user = users.findById(userId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
    return catalogFor(user).stream().filter(ModelCredentialStatus::usable).toList();
  }

  /** The catalogue narrowed to instances the viewer can call right now. */
  @Transactional(readOnly = true)
  public List<ModelCredentialStatus> usable(String actorEmail) {
    return catalog(actorEmail).stream().filter(ModelCredentialStatus::usable).toList();
  }

  /** Status of the oldest global instance of a kind; the pre-instance view of a provider. */
  @Transactional(readOnly = true)
  public ModelCredentialStatus status(ModelProvider provider) {
    return instances.findFirstByKindAndOwnerIdIsNullOrderByCreatedAtAsc(provider)
        .map(value -> toStatus(value, false))
        .orElseGet(() -> missing(provider));
  }

  /**
   * Resolves the instance a run should use and checks that the viewer may use it now. An absent
   * instance id falls back to the oldest global instance of the kind.
   */
  @Transactional(readOnly = true)
  public ModelCredentialStatus resolve(UUID viewerId, ModelProvider kind, UUID instanceId) {
    ModelProviderInstance instance;
    if (instanceId != null) {
      instance = instances.findById(instanceId).orElseThrow(() ->
          new IllegalStateException("The selected model provider no longer exists"));
      requireVisible(instance, viewerId);
    } else {
      ModelProvider effective = kind == null ? ModelProvider.OPENAI : kind;
      instance = instances.findFirstByKindAndOwnerIdIsNullOrderByCreatedAtAsc(effective)
          .orElseThrow(() ->
              new IllegalStateException(effective + " credentials are not configured"));
    }
    if (!instance.enabled()) {
      throw new IllegalStateException(instance.displayName() + " is disabled");
    }
    ModelCredentialStatus status = toStatus(
        instance, userKeys.findByInstanceIdAndUserId(instance.id(), viewerId).isPresent());
    if (!status.usable()) {
      throw new IllegalStateException(
          "Add your API key for " + instance.displayName() + " under LLM providers first");
    }
    return status;
  }

  // ---------------------------------------------------------------------------------------------
  // Managing instances
  // ---------------------------------------------------------------------------------------------

  /** Creates or updates a global instance. Administrators only. */
  @Transactional
  public ModelCredentialStatus saveGlobal(
      UUID id, SaveModelProviderCommand command, String actorEmail) {
    AppUser actor = requireAdmin(actor(actorEmail));
    ModelProviderInstance instance;
    boolean created = id == null;
    if (created) {
      if (command.provider() == null) throw new IllegalArgumentException("Provider kind is required");
      instance = new ModelProviderInstance(
          UUID.randomUUID(), command.provider(), null, actor.getId(), Instant.now(clock));
    } else {
      instance = instances.findById(id).filter(ModelProviderInstance::global)
          .orElseThrow(() -> new IllegalArgumentException("Unknown model provider"));
    }
    apply(instance, command, actor);
    if (command.clearApiKey()) {
      instance.replaceSharedKey(null);
    } else if (command.apiKey() != null && !command.apiKey().isBlank()) {
      instance.replaceSharedKey(cipher.encrypt(boundedKey(command.apiKey())));
    }
    instances.save(instance);
    auditLog.record(
        actor.getEmail(), created ? "MODEL_PROVIDER_CREATED" : "MODEL_PROVIDER_UPDATED",
        TARGET_TYPE, instance.id(),
        (created ? "Created" : "Updated") + " global model provider " + instance.displayName());
    return toStatus(instance, false);
  }

  /** Creates or updates one of the caller's own instances. Requires the administrator's consent. */
  @Transactional
  public ModelCredentialStatus savePersonal(
      UUID id, SaveModelProviderCommand command, String actorEmail) {
    AppUser actor = actor(actorEmail);
    ModelProviderInstance instance;
    boolean created = id == null;
    if (created) {
      requirePersonalProvidersAllowed();
      if (command.provider() == null) throw new IllegalArgumentException("Provider kind is required");
      instance = new ModelProviderInstance(
          UUID.randomUUID(), command.provider(), actor.getId(), actor.getId(), Instant.now(clock));
    } else {
      instance = instances.findById(id)
          .filter(value -> actor.getId().equals(value.ownerId()))
          .orElseThrow(() -> new IllegalArgumentException("Unknown model provider"));
      requirePersonalProvidersAllowed();
    }
    apply(instance, command, actor);
    instances.save(instance);
    if (command.clearApiKey()) {
      userKeys.findByInstanceIdAndUserId(instance.id(), actor.getId()).ifPresent(userKeys::delete);
    } else if (command.apiKey() != null && !command.apiKey().isBlank()) {
      storeUserKey(instance.id(), actor.getId(), command.apiKey());
    }
    auditLog.record(
        actor.getEmail(), created ? "MODEL_PROVIDER_CREATED" : "MODEL_PROVIDER_UPDATED",
        TARGET_TYPE, instance.id(),
        (created ? "Created" : "Updated") + " personal model provider " + instance.displayName());
    return toStatus(instance, userKeys.findByInstanceIdAndUserId(
        instance.id(), actor.getId()).isPresent());
  }

  /** Deletes a global instance (administrators) or one of the caller's own instances. */
  @Transactional
  public void delete(UUID id, String actorEmail) {
    AppUser actor = actor(actorEmail);
    ModelProviderInstance instance = instances.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("Unknown model provider"));
    if (instance.global()) {
      requireAdmin(actor);
    } else if (!actor.getId().equals(instance.ownerId())) {
      throw new IllegalArgumentException("Unknown model provider");
    }
    userKeys.deleteAll(userKeys.findByInstanceId(id));
    instances.delete(instance);
    auditLog.record(
        actor.getEmail(), "MODEL_PROVIDER_REMOVED", TARGET_TYPE, id,
        "Removed model provider " + instance.displayName());
  }

  // ---------------------------------------------------------------------------------------------
  // Per-user keys
  // ---------------------------------------------------------------------------------------------

  @Transactional
  public void saveUserKey(UUID instanceId, String apiKey, String actorEmail) {
    AppUser actor = actor(actorEmail);
    ModelProviderInstance instance = instances.findById(instanceId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown model provider"));
    requireVisible(instance, actor.getId());
    if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("API key is required");
    storeUserKey(instanceId, actor.getId(), apiKey);
    auditLog.record(
        actor.getEmail(), "MODEL_USER_KEY_SAVED", TARGET_TYPE, instanceId,
        "Stored a personal API key for " + instance.displayName());
  }

  @Transactional
  public void removeUserKey(UUID instanceId, String actorEmail) {
    AppUser actor = actor(actorEmail);
    userKeys.findByInstanceIdAndUserId(instanceId, actor.getId()).ifPresent(key -> {
      userKeys.delete(key);
      auditLog.record(
          actor.getEmail(), "MODEL_USER_KEY_REMOVED", TARGET_TYPE, instanceId,
          "Removed a personal API key");
    });
  }

  // ---------------------------------------------------------------------------------------------
  // Policy
  // ---------------------------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public boolean allowUserProviders() {
    return policies.findById(ModelProviderPolicy.SINGLETON_ID)
        .map(ModelProviderPolicy::allowUserProviders).orElse(false);
  }

  @Transactional
  public void setAllowUserProviders(boolean allow, String actorEmail) {
    AppUser actor = requireAdmin(actor(actorEmail));
    Instant now = Instant.now(clock);
    ModelProviderPolicy policy = policies.findById(ModelProviderPolicy.SINGLETON_ID).orElse(null);
    if (policy == null) policy = new ModelProviderPolicy(allow, actor.getId(), now);
    else policy.update(allow, actor.getId(), now);
    policies.save(policy);
    auditLog.record(
        actor.getEmail(), "MODEL_PROVIDER_POLICY_CHANGED", "MODEL_PROVIDER_POLICY",
        UUID.nameUUIDFromBytes("MODEL_PROVIDER_POLICY".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        (allow ? "Allowed" : "Disallowed") + " personal model providers");
  }

  // ---------------------------------------------------------------------------------------------
  // Call-time material
  // ---------------------------------------------------------------------------------------------

  /** Credentials for a call made on behalf of a user; never holds more than what that user may use. */
  @Transactional(readOnly = true)
  ModelCredentialMaterial requireMaterial(ModelCredentialRef ref) {
    ModelProviderInstance instance = instances.findById(ref.instanceId()).orElseThrow(() ->
        new IllegalStateException("The model provider used by this run no longer exists"));
    if (!instance.enabled()) {
      throw new IllegalStateException(instance.displayName() + " is disabled");
    }
    if (!instance.global()) {
      if (!instance.ownerId().equals(ref.userId())) {
        throw new IllegalStateException("The model provider is not available to this user");
      }
      if (!allowUserProviders()) {
        throw new IllegalStateException(
            "Personal model providers are disabled by the administrator");
      }
    }
    String ciphertext = ref.userId() == null
        ? null
        : userKeys.findByInstanceIdAndUserId(instance.id(), ref.userId())
            .map(ModelUserApiKey::apiKeyCiphertext).orElse(null);
    if (ciphertext == null) ciphertext = instance.sharedApiKeyCiphertext();
    if (ciphertext == null && instance.kind().apiKeyRequired()) {
      throw new IllegalStateException(
          "No API key is available for " + instance.displayName()
              + ". Add your key under LLM providers.");
    }
    return material(instance, ciphertext);
  }

  /** Credentials of the oldest global instance of a kind, using its shared key only. */
  @Transactional(readOnly = true)
  ModelCredentialMaterial requireMaterial(ModelProvider provider) {
    ModelProviderInstance instance = instances
        .findFirstByKindAndOwnerIdIsNullOrderByCreatedAtAsc(provider)
        .orElseThrow(() -> new IllegalStateException(provider + " credentials are not configured"));
    return material(instance, instance.sharedApiKeyCiphertext());
  }

  // ---------------------------------------------------------------------------------------------
  // Per-kind operations used by the operations API
  // ---------------------------------------------------------------------------------------------

  @Transactional
  public ModelCredentialStatus save(
      ModelProvider provider, SaveModelCredentialCommand command, String actorEmail) {
    if (provider == null) throw new IllegalArgumentException("Model provider is required");
    AppUser actor = requireAdmin(actor(actorEmail));
    ModelProviderInstance instance = instances
        .findFirstByKindAndOwnerIdIsNullOrderByCreatedAtAsc(provider).orElse(null);
    boolean created = instance == null;
    String existingKey = created ? null : instance.sharedApiKeyCiphertext();
    boolean hasNewKey = command.apiKey() != null && !command.apiKey().isBlank();
    if (provider.apiKeyRequired() && !hasNewKey && existingKey == null) {
      throw new IllegalArgumentException("API key is required");
    }
    if (created) {
      instance = new ModelProviderInstance(
          UUID.randomUUID(), provider, null, actor.getId(), Instant.now(clock));
    }
    apply(instance, new SaveModelProviderCommand(
        provider,
        created ? provider.displayName() : instance.displayName(),
        command.baseUrl(), command.model(), null, false,
        command.maxOutputTokens(), command.contextWindowTokens(),
        command.reasoningEffort(), command.textVerbosity(), true), actor);
    if (hasNewKey) instance.replaceSharedKey(cipher.encrypt(boundedKey(command.apiKey())));
    instances.save(instance);
    auditLog.record(
        actor.getEmail(),
        created ? "MODEL_CREDENTIAL_CREATED" : "MODEL_CREDENTIAL_REPLACED",
        TARGET_TYPE, instance.id(), "Configured credentials for " + provider);
    return toStatus(instance, false);
  }

  @Transactional
  public void remove(ModelProvider provider, String actorEmail) {
    AppUser actor = requireAdmin(actor(actorEmail));
    instances.findFirstByKindAndOwnerIdIsNullOrderByCreatedAtAsc(provider)
        .ifPresent(instance -> delete(instance.id(), actor.getEmail()));
  }

  // ---------------------------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------------------------

  private void apply(
      ModelProviderInstance instance, SaveModelProviderCommand command, AppUser actor) {
    String displayName = required(command.displayName(), "Name", 120).strip();
    String model = required(command.model(), "Model", 200).strip();
    ModelProvider kind = instance.kind();
    String baseUrl = normalizeBaseUrl(
        command.baseUrl() == null || command.baseUrl().isBlank()
            ? defaultBaseUrl(kind)
            : command.baseUrl());
    int maxOutputTokens = command.maxOutputTokens() == null
        ? defaultMaxOutputTokens
        : command.maxOutputTokens();
    if (maxOutputTokens < 16 || maxOutputTokens > 1_000_000) {
      throw new IllegalArgumentException("Maximum output tokens must be between 16 and 1000000");
    }
    int contextWindowTokens = command.contextWindowTokens() == null
        ? DEFAULT_CONTEXT_WINDOW_TOKENS
        : command.contextWindowTokens();
    validateContextWindow(contextWindowTokens, maxOutputTokens);
    instance.configure(
        displayName, baseUrl, model, maxOutputTokens, contextWindowTokens,
        command.reasoningEffort(), command.textVerbosity(), command.enabled(),
        actor.getId(), Instant.now(clock));
  }

  private ModelCredentialStatus toStatus(ModelProviderInstance instance, boolean hasUserKey) {
    boolean shared = instance.sharedApiKeyCiphertext() != null;
    boolean keyRequired = instance.kind().apiKeyRequired();
    boolean hasKey = hasUserKey || shared;
    return new ModelCredentialStatus(
        instance.id(),
        instance.kind(),
        instance.displayName(),
        instance.global(),
        instance.ownerId(),
        instance.enabled(),
        true,
        hasKey,
        shared,
        hasUserKey,
        keyRequired,
        instance.enabled() && (!keyRequired || hasKey),
        instance.baseUrl() == null ? defaultBaseUrl(instance.kind()) : instance.baseUrl(),
        instance.model(),
        instance.maxOutputTokens(),
        instance.contextWindowTokens(),
        instance.reasoningEffort(),
        instance.textVerbosity(),
        instance.updatedAt());
  }

  private ModelCredentialStatus missing(ModelProvider provider) {
    return new ModelCredentialStatus(
        null, provider, provider.displayName(), true, null, false,
        false, false, false, false, provider.apiKeyRequired(), false,
        defaultBaseUrl(provider), null, defaultMaxOutputTokens, DEFAULT_CONTEXT_WINDOW_TOKENS,
        null, null, null);
  }

  private ModelCredentialMaterial material(ModelProviderInstance instance, String ciphertext) {
    return new ModelCredentialMaterial(
        instance.kind(),
        ciphertext == null ? null : cipher.decrypt(ciphertext),
        instance.baseUrl() == null ? defaultBaseUrl(instance.kind()) : instance.baseUrl(),
        instance.model());
  }

  private void storeUserKey(UUID instanceId, UUID userId, String apiKey) {
    String ciphertext = cipher.encrypt(boundedKey(apiKey));
    Instant now = Instant.now(clock);
    ModelUserApiKey existing = userKeys.findByInstanceIdAndUserId(instanceId, userId).orElse(null);
    if (existing == null) {
      userKeys.save(new ModelUserApiKey(UUID.randomUUID(), instanceId, userId, ciphertext, now));
    } else {
      existing.replace(ciphertext, now);
      userKeys.save(existing);
    }
  }

  private Set<UUID> keyedInstances(UUID userId) {
    return userKeys.findByUserId(userId).stream()
        .map(ModelUserApiKey::instanceId).collect(Collectors.toCollection(HashSet::new));
  }

  /** An instance the viewer may select, for catalogue operations. */
  @Transactional(readOnly = true)
  ModelProviderInstance visibleInstance(UUID instanceId, UUID viewerId) {
    ModelProviderInstance instance = instances.findById(instanceId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown model provider"));
    requireVisible(instance, viewerId);
    return instance;
  }

  private void requireVisible(ModelProviderInstance instance, UUID viewerId) {
    if (instance.global()) return;
    if (!instance.ownerId().equals(viewerId) || !allowUserProviders()) {
      throw new IllegalStateException("The selected model provider is not available");
    }
  }

  private void requirePersonalProvidersAllowed() {
    if (!allowUserProviders()) {
      throw new IllegalStateException(
          "The administrator has not allowed personal model providers");
    }
  }

  private AppUser actor(String actorEmail) {
    return users.findByEmailIgnoreCase(actorEmail)
        .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
  }

  private static AppUser requireAdmin(AppUser actor) {
    if (!ADMIN_ROLE.equals(actor.getRole())) {
      throw new IllegalArgumentException("Administrator rights are required");
    }
    return actor;
  }

  private static String boundedKey(String value) {
    String key = value.strip();
    if (key.isEmpty()) throw new IllegalArgumentException("API key is required");
    if (key.length() > MAX_API_KEY_LENGTH) throw new IllegalArgumentException("API key is too long");
    return key;
  }

  private static String required(String value, String label, int maxLength) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
    if (value.length() > maxLength) throw new IllegalArgumentException(label + " is too long");
    return value;
  }

  private static void validateContextWindow(int contextWindowTokens, int maxOutputTokens) {
    if (contextWindowTokens < maxOutputTokens + MIN_CONTEXT_INPUT_RESERVE
        || Math.floor(contextWindowTokens * 0.85) < maxOutputTokens + MIN_CONTEXT_INPUT_RESERVE
        || contextWindowTokens > 10_000_000) {
      throw new IllegalArgumentException(
          "Context window must leave at least " + MIN_CONTEXT_INPUT_RESERVE
              + " input tokens after the safety reserve");
    }
  }

  private String defaultBaseUrl(ModelProvider provider) {
    return provider == ModelProvider.OPENAI
        ? stripTrailingSlash(defaultOpenAiBaseUrl)
        : provider.defaultBaseUrl();
  }

  private static String normalizeBaseUrl(String value) {
    String normalized = stripTrailingSlash(required(value, "Base URL", 2048).strip());
    try {
      URI uri = new URI(normalized);
      if (!("http".equalsIgnoreCase(uri.getScheme())
              || "https".equalsIgnoreCase(uri.getScheme()))
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null) {
        throw new IllegalArgumentException(
            "Base URL must be an HTTP(S) URL without credentials, query, or fragment");
      }
      return uri.toString();
    } catch (URISyntaxException exception) {
      throw new IllegalArgumentException("Base URL is invalid", exception);
    }
  }

  private static String stripTrailingSlash(String value) {
    int end = value.length();
    while (end > 0 && value.charAt(end - 1) == '/') end--;
    return value.substring(0, end);
  }
}
