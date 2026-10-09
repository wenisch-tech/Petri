package tech.wenisch.petri.agent.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.config.PetriProperties;
import tech.wenisch.petri.secret.SecretCipher;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;

@Service
public class ModelCredentialService {
  private static final int MAX_API_KEY_LENGTH = 12000;
  private static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 32768;
  private static final int MIN_CONTEXT_INPUT_RESERVE = 2048;

  private final ModelProviderCredentialRepository credentials;
  private final AppUserRepository users;
  private final SecretCipher cipher;
  private final AuditLogService auditLog;
  private final int defaultMaxOutputTokens;
  private final String defaultOpenAiBaseUrl;
  private final Clock clock;

  @Autowired
  public ModelCredentialService(
      ModelProviderCredentialRepository credentials,
      AppUserRepository users,
      SecretCipher cipher,
      AuditLogService auditLog,
      PetriProperties properties) {
    this(
        credentials,
        users,
        cipher,
        auditLog,
        properties.agent().maxOutputTokens(),
        properties.agent().openAiBaseUrl(),
        Clock.systemUTC());
  }

  ModelCredentialService(
      ModelProviderCredentialRepository credentials,
      AppUserRepository users,
      SecretCipher cipher,
      AuditLogService auditLog,
      int defaultMaxOutputTokens,
      String defaultOpenAiBaseUrl,
      Clock clock) {
    this.credentials = credentials;
    this.users = users;
    this.cipher = cipher;
    this.auditLog = auditLog;
    this.defaultMaxOutputTokens = defaultMaxOutputTokens;
    this.defaultOpenAiBaseUrl = defaultOpenAiBaseUrl;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public ModelCredentialStatus status(ModelProvider provider) {
    return credentials.findById(provider)
        .map(value -> new ModelCredentialStatus(
            provider,
            true,
            value.apiKeyCiphertext() != null,
            value.baseUrl() == null ? defaultBaseUrl(provider) : value.baseUrl(),
            value.model(),
            value.maxOutputTokens(),
            value.contextWindowTokens(),
            value.reasoningEffort(),
            value.textVerbosity(),
            value.updatedAt()))
        .orElseGet(() -> new ModelCredentialStatus(
            provider,
            false,
            false,
            defaultBaseUrl(provider),
            null,
            defaultMaxOutputTokens,
            DEFAULT_CONTEXT_WINDOW_TOKENS,
            null,
            null,
            null));
  }

  @Transactional(readOnly = true)
  ModelCredentialMaterial requireMaterial(ModelProvider provider) {
    ModelProviderCredential credential = credentials.findById(provider)
        .orElseThrow(() -> new IllegalStateException(provider + " credentials are not configured"));
    String apiKey = credential.apiKeyCiphertext() == null
        ? null
        : cipher.decrypt(credential.apiKeyCiphertext());
    return new ModelCredentialMaterial(
        provider,
        apiKey,
        credential.baseUrl() == null ? defaultBaseUrl(provider) : credential.baseUrl(),
        credential.model());
  }

  @Transactional
  public ModelCredentialStatus save(
      ModelProvider provider, SaveModelCredentialCommand command, String actorEmail) {
    if (provider == null) throw new IllegalArgumentException("Model provider is required");
    AppUser actor = actor(actorEmail);
    String model = required(command.model(), "Model", 200).strip();
    String baseUrl = normalizeBaseUrl(
        command.baseUrl() == null || command.baseUrl().isBlank()
            ? defaultBaseUrl(provider)
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
    Instant now = Instant.now(clock);
    ModelProviderCredential credential = credentials.findById(provider).orElse(null);
    String action;
    if (credential == null) {
      String apiKey = optionalApiKey(command.apiKey(), provider.apiKeyRequired());
      credential = new ModelProviderCredential(
          provider,
          apiKey == null ? null : cipher.encrypt(apiKey),
          baseUrl,
          model,
          maxOutputTokens,
          contextWindowTokens,
          command.reasoningEffort(),
          command.textVerbosity(),
          actor.getId(),
          now);
      action = "MODEL_CREDENTIAL_CREATED";
    } else {
      String encrypted = command.apiKey() == null || command.apiKey().isBlank()
          ? credential.apiKeyCiphertext()
          : cipher.encrypt(required(command.apiKey(), "API key", MAX_API_KEY_LENGTH));
      if (provider.apiKeyRequired() && encrypted == null) {
        throw new IllegalArgumentException("API key is required");
      }
      credential.replace(
          encrypted,
          baseUrl,
          model,
          maxOutputTokens,
          contextWindowTokens,
          command.reasoningEffort(),
          command.textVerbosity(),
          actor.getId(),
          now);
      action = "MODEL_CREDENTIAL_REPLACED";
    }
    credentials.save(credential);
    auditLog.record(
        actor.getEmail(), action, "MODEL_PROVIDER_CREDENTIAL", auditTarget(provider),
        "Configured credentials for " + provider);
    return new ModelCredentialStatus(
        provider,
        true,
        credential.apiKeyCiphertext() != null,
        baseUrl,
        model,
        maxOutputTokens,
        contextWindowTokens,
        command.reasoningEffort(),
        command.textVerbosity(),
        now);
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

  private static String optionalApiKey(String value, boolean required) {
    if (value == null || value.isBlank()) {
      if (required) throw new IllegalArgumentException("API key is required");
      return null;
    }
    if (value.length() > MAX_API_KEY_LENGTH) {
      throw new IllegalArgumentException("API key is too long");
    }
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

  private static java.util.UUID auditTarget(ModelProvider provider) {
    return java.util.UUID.nameUUIDFromBytes(
        ("MODEL_PROVIDER_CREDENTIAL:" + provider).getBytes(StandardCharsets.UTF_8));
  }
}
