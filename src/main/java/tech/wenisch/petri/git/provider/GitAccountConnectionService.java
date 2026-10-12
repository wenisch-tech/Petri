package tech.wenisch.petri.git.provider;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.secret.SecretCipher;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;

@Service
public class GitAccountConnectionService {
  private final GitAccountConnectionRepository connections;
  private final GitProviderInstanceService instances;
  private final GitProviderAdapterRegistry adapters;
  private final ProjectProviderSourceRepository sources;
  private final AppUserRepository users;
  private final SecretCipher cipher;
  private final AuditLogService audit;
  private final ConcurrentHashMap<UUID, Object> refreshLocks = new ConcurrentHashMap<>();

  public GitAccountConnectionService(
      GitAccountConnectionRepository connections,
      GitProviderInstanceService instances,
      GitProviderAdapterRegistry adapters,
      ProjectProviderSourceRepository sources,
      AppUserRepository users,
      SecretCipher cipher,
      AuditLogService audit) {
    this.connections = connections;
    this.instances = instances;
    this.adapters = adapters;
    this.sources = sources;
    this.users = users;
    this.cipher = cipher;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public List<GitAccountConnectionView> list(String actorEmail) {
    AppUser owner = owner(actorEmail);
    return connections.findByOwnerIdOrderByCreatedAtDesc(owner.getId()).stream()
        .map(this::view)
        .toList();
  }

  @Transactional
  public GitAccountConnectionView connectPat(UUID instanceId, String token, String actorEmail) {
    if (token == null || token.isBlank()) throw new IllegalArgumentException("Personal access token is required");
    AppUser owner = owner(actorEmail);
    GitProviderInstance instance = activeInstance(instanceId, owner.getId());
    GitProviderAdapter adapter = adapters.require(instance.getProviderType());
    String rawToken = token.trim();
    ProviderIdentity identity = adapter.identity(instance, rawToken, GitConnectionAuthMethod.PAT);
    // Validation includes repository permission; an identity-only token is not useful to Petri.
    adapter.repositories(instance, rawToken, GitConnectionAuthMethod.PAT, 1, 1, null);
    GitAccountConnection connection = upsert(owner, instance, GitConnectionAuthMethod.PAT,
        identity, new ProviderToken(rawToken, null, null, null, null));
    audit.record(actorEmail, "GIT_ACCOUNT_CONNECTED", "GIT_ACCOUNT", connection.getId(),
        "Connected " + instance.getDisplayName() + " account " + identity.login());
    return view(connection);
  }

  @Transactional
  public GitAccountConnectionView connectOauth(
      UUID instanceId, String code, String redirectUri, String codeVerifier, String actorEmail) {
    AppUser owner = owner(actorEmail);
    GitProviderInstance instance = activeInstance(instanceId, owner.getId());
    GitProviderAdapter adapter = adapters.require(instance.getProviderType());
    ProviderToken token = adapter.exchangeCode(
        instance, instances.clientSecret(instance), code, redirectUri, codeVerifier);
    ProviderIdentity identity = adapter.identity(instance, token.accessToken(), GitConnectionAuthMethod.OAUTH);
    GitAccountConnection connection = upsert(
        owner, instance, GitConnectionAuthMethod.OAUTH, identity, token);
    audit.record(actorEmail, "GIT_ACCOUNT_CONNECTED", "GIT_ACCOUNT", connection.getId(),
        "Connected " + instance.getDisplayName() + " account " + identity.login() + " using OAuth");
    return view(connection);
  }

  /**
   * Replaces the access token of an existing token connection, for example after the token expired
   * or to give Petri a token with more permissions. The new token must belong to the same account.
   */
  @Transactional
  public GitAccountConnectionView replaceToken(UUID id, String token, String actorEmail) {
    if (token == null || token.isBlank()) throw new IllegalArgumentException("A new access token is required");
    GitAccountConnection connection = ownedLocked(id, actorEmail);
    if (connection.getAuthMethod() != GitConnectionAuthMethod.PAT) {
      throw new IllegalStateException(
          "This account signs in with OAuth; disconnect it and connect again to renew access");
    }
    GitProviderInstance instance = activeInstance(
        connection.getProviderInstanceId(), connection.getOwnerId());
    GitProviderAdapter adapter = adapters.require(instance.getProviderType());
    String rawToken = token.trim();
    ProviderIdentity identity = adapter.identity(instance, rawToken, GitConnectionAuthMethod.PAT);
    if (!connection.getExternalAccountId().equals(identity.id())) {
      throw new IllegalArgumentException("This token belongs to " + identity.login() + ", not to "
          + connection.getExternalLogin() + ". Connect it as a separate account instead.");
    }
    adapter.repositories(instance, rawToken, GitConnectionAuthMethod.PAT, 1, 1, null);
    connection.reconnect(GitConnectionAuthMethod.PAT, identity, cipher.encrypt(rawToken), null,
        null, null, null, Instant.now());
    audit.record(actorEmail, "GIT_ACCOUNT_TOKEN_REPLACED", "GIT_ACCOUNT", id,
        "Replaced the access token of " + instance.getDisplayName() + " account " + identity.login());
    return view(connection);
  }

  @Transactional(noRollbackFor = {GitProviderException.class, IllegalStateException.class})
  public GitAccountConnectionView validate(UUID id, String actorEmail) {
    GitAccountConnection connection = ownedLocked(id, actorEmail);
    try {
      refreshIfNeeded(connection);
      ResolvedConnection resolved = resolve(connection);
      ProviderIdentity identity = resolved.adapter().identity(
          resolved.instance(), resolved.token(), connection.getAuthMethod());
      connection.mark(GitConnectionStatus.ACTIVE, Instant.now());
      audit.record(actorEmail, "GIT_ACCOUNT_VALIDATED", "GIT_ACCOUNT", id,
          "Validated Git account " + identity.login());
    } catch (RuntimeException exception) {
      connection.mark(GitConnectionStatus.INVALID, Instant.now());
      throw exception;
    }
    return view(connection);
  }

  @Transactional
  public void disconnect(UUID id, String actorEmail) {
    GitAccountConnection connection = ownedLocked(id, actorEmail);
    connection.disconnect(Instant.now());
    audit.record(actorEmail, "GIT_ACCOUNT_DISCONNECTED", "GIT_ACCOUNT", id,
        "Disconnected Git account " + connection.getExternalLogin());
  }

  @Transactional(noRollbackFor = {GitProviderException.class, IllegalStateException.class})
  public ProviderRepositoryPage repositories(
      UUID id, int page, int pageSize, String query, String actorEmail) {
    GitAccountConnection connection = ownedLocked(id, actorEmail);
    refreshIfNeeded(connection);
    ResolvedConnection resolved = resolve(connection);
    ProviderRepositoryPage result = resolved.adapter().repositories(resolved.instance(), resolved.token(),
        connection.getAuthMethod(), boundedPage(page), boundedSize(pageSize), normalizedQuery(query));
    return new ProviderRepositoryPage(result.repositories().stream()
        .map(repository -> repository.withAlreadyImported(
            sources.existsByOwnerIdAndProviderInstanceIdAndExternalRepositoryId(
                connection.getOwnerId(), connection.getProviderInstanceId(), repository.id())))
        .toList(), result.page(), result.pageSize(), result.hasNext(), result.truncated());
  }

  @Transactional(noRollbackFor = {GitProviderException.class, IllegalStateException.class})
  public ProviderRepository repository(UUID id, String repositoryId, String actorEmail) {
    GitAccountConnection connection = ownedLocked(id, actorEmail);
    refreshIfNeeded(connection);
    ResolvedConnection resolved = resolve(connection);
    return resolved.adapter().repository(resolved.instance(), resolved.token(),
        connection.getAuthMethod(), repositoryId);
  }

  /** Open pull requests of a repository through the connection; used by automations. */
  @Transactional(noRollbackFor = {GitProviderException.class, IllegalStateException.class})
  public PullRequestListing openPullRequests(
      UUID id, String repositoryId, String fullName) {
    GitAccountConnection connection = connections.findLockedById(id)
        .orElseThrow(() -> new IllegalArgumentException("Git account connection not found"));
    refreshIfNeeded(connection);
    ResolvedConnection resolved = resolve(connection);
    return new PullRequestListing(connection.getExternalLogin(),
        resolved.adapter().openPullRequests(resolved.instance(), resolved.token(),
            connection.getAuthMethod(), repositoryId, fullName));
  }

  @Transactional(noRollbackFor = {GitProviderException.class, IllegalStateException.class})
  public String postPullRequestComment(
      UUID id, String repositoryId, String fullName, long number, String webUrl, String markdown) {
    GitAccountConnection connection = connections.findLockedById(id)
        .orElseThrow(() -> new IllegalArgumentException("Git account connection not found"));
    refreshIfNeeded(connection);
    ResolvedConnection resolved = resolve(connection);
    return resolved.adapter().postPullRequestComment(resolved.instance(), resolved.token(),
        connection.getAuthMethod(), repositoryId, fullName, number, webUrl, markdown);
  }

  /** Opens a pull request through the connection; used by the workspace's pull request dialog. */
  @Transactional(noRollbackFor = {GitProviderException.class, IllegalStateException.class})
  public CreatedPullRequest createPullRequest(
      UUID id, String repositoryId, String fullName, CreatePullRequest request) {
    GitAccountConnection connection = connections.findLockedById(id)
        .orElseThrow(() -> new IllegalArgumentException("Git account connection not found"));
    refreshIfNeeded(connection);
    ResolvedConnection resolved = resolve(connection);
    ProviderIdentity me = new ProviderIdentity(connection.getExternalAccountId(),
        connection.getExternalLogin(), connection.getDisplayName());
    return resolved.adapter().createPullRequest(resolved.instance(), resolved.token(),
        connection.getAuthMethod(), repositoryId, fullName, me, request);
  }

  @Transactional(noRollbackFor = {GitProviderException.class, IllegalStateException.class})
  public void reactToPullRequest(
      UUID id, String repositoryId, String fullName, long number, String reaction) {
    GitAccountConnection connection = connections.findLockedById(id)
        .orElseThrow(() -> new IllegalArgumentException("Git account connection not found"));
    refreshIfNeeded(connection);
    ResolvedConnection resolved = resolve(connection);
    resolved.adapter().reactToPullRequest(resolved.instance(), resolved.token(),
        connection.getAuthMethod(), repositoryId, fullName, number, reaction);
  }

  @Transactional(readOnly = true)
  public GitAccountConnection requireOwned(UUID id, String actorEmail) {
    return owned(id, actorEmail);
  }

  @Transactional(noRollbackFor = {GitProviderException.class, IllegalStateException.class})
  public GitConnectionCredential credential(UUID id) {
    Object lock = refreshLocks.computeIfAbsent(id, ignored -> new Object());
    synchronized (lock) {
      try {
        return credentialLocked(id);
      } finally {
        refreshLocks.remove(id, lock);
      }
    }
  }

  private GitConnectionCredential credentialLocked(UUID id) {
    GitAccountConnection connection = connections.findLockedById(id)
        .orElseThrow(() -> new IllegalArgumentException("Git account connection not found"));
    refreshIfNeeded(connection);
    ResolvedConnection resolved = resolve(connection);
    ProviderIdentity identity = new ProviderIdentity(connection.getExternalAccountId(),
        connection.getExternalLogin(), connection.getDisplayName());
    return new GitConnectionCredential(
        resolved.adapter().gitUsername(connection.getAuthMethod(), identity), resolved.token(),
        connection.getAuthMethod(), connection.getExternalLogin());
  }

  private GitAccountConnection upsert(
      AppUser owner, GitProviderInstance instance, GitConnectionAuthMethod method,
      ProviderIdentity identity, ProviderToken token) {
    Instant now = Instant.now();
    String encryptedAccess = cipher.encrypt(token.accessToken());
    String encryptedRefresh = encryptOptional(token.refreshToken());
    GitAccountConnection connection = connections
        .findByOwnerIdAndProviderInstanceIdAndExternalAccountId(
            owner.getId(), instance.getId(), identity.id())
        .orElseGet(() -> new GitAccountConnection(UUID.randomUUID(), instance.getId(), owner.getId(),
            method, identity, encryptedAccess, encryptedRefresh, token.accessExpiresAt(),
            token.refreshExpiresAt(), token.scopes(), now));
    if (connection.getCreatedAt() != null) {
      connection.reconnect(method, identity, encryptedAccess, encryptedRefresh,
          token.accessExpiresAt(), token.refreshExpiresAt(), token.scopes(), now);
    }
    return connections.save(connection);
  }

  private ResolvedConnection resolve(GitAccountConnection connection) {
    if (connection.getStatus() != GitConnectionStatus.ACTIVE) {
      throw new IllegalStateException("Git account connection is " + connection.getStatus().name().toLowerCase());
    }
    if (connection.accessTokenCiphertext() == null) {
      throw new IllegalStateException("Git account has no usable token; reconnect the account");
    }
    GitProviderInstance instance = activeInstance(
        connection.getProviderInstanceId(), connection.getOwnerId());
    return new ResolvedConnection(instance, adapters.require(instance.getProviderType()),
        cipher.decrypt(connection.accessTokenCiphertext()));
  }

  private void refreshIfNeeded(GitAccountConnection connection) {
    if (connection.getAccessExpiresAt() == null
        || connection.getAccessExpiresAt().isAfter(Instant.now().plus(60, ChronoUnit.SECONDS))) return;
    if (connection.refreshTokenCiphertext() == null) {
      connection.mark(GitConnectionStatus.EXPIRED, Instant.now());
      throw new IllegalStateException("Git account access has expired; reconnect the account");
    }
    ResolvedConnection resolved = resolve(connection);
    ProviderToken refreshed = resolved.adapter().refresh(resolved.instance(),
        instances.clientSecret(resolved.instance()), cipher.decrypt(connection.refreshTokenCiphertext()));
    connection.replaceTokens(cipher.encrypt(refreshed.accessToken()),
        encryptOptional(refreshed.refreshToken()), refreshed.accessExpiresAt(),
        refreshed.refreshExpiresAt(), refreshed.scopes(), Instant.now());
  }

  private GitAccountConnection owned(UUID id, String actorEmail) {
    AppUser owner = owner(actorEmail);
    return connections.findByIdAndOwnerId(id, owner.getId())
        .orElseThrow(GitAccountConnectionService::notFound);
  }

  private GitAccountConnection ownedLocked(UUID id, String actorEmail) {
    AppUser owner = owner(actorEmail);
    return connections.findLockedByIdAndOwnerId(id, owner.getId())
        .orElseThrow(GitAccountConnectionService::notFound);
  }

  private GitProviderInstance activeInstance(UUID id, UUID ownerId) {
    GitProviderInstance instance = instances.requireForOwner(id, ownerId);
    if (!instance.isEnabled()) throw new IllegalStateException("Git provider instance is disabled");
    return instance;
  }

  private GitAccountConnectionView view(GitAccountConnection connection) {
    GitProviderInstance instance = instances.requireForOwner(
        connection.getProviderInstanceId(), connection.getOwnerId());
    return new GitAccountConnectionView(connection.getId(), instance.getId(), instance.getDisplayName(),
        instance.getProviderType(), connection.getAuthMethod(), connection.getExternalLogin(),
        connection.getDisplayName(), connection.getStatus(), connection.getGrantedScopes(),
        connection.getAccessExpiresAt(), instance.getWebBaseUrl().startsWith("http://"),
        instance.getProviderType() == GitProviderType.FORGEJO
            && connection.getAuthMethod() == GitConnectionAuthMethod.OAUTH);
  }

  private AppUser owner(String email) {
    return users.findByEmailIgnoreCase(email)
        .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
  }

  private String encryptOptional(String value) {
    return value == null || value.isBlank() ? null : cipher.encrypt(value);
  }

  private static int boundedPage(int page) { return Math.max(1, page); }
  private static int boundedSize(int size) { return Math.max(1, Math.min(100, size)); }
  private static String normalizedQuery(String query) {
    if (query == null || query.isBlank()) return null;
    String result = query.trim();
    if (result.length() > 200) throw new IllegalArgumentException("Search query is too long");
    return result;
  }

  private record ResolvedConnection(
      GitProviderInstance instance, GitProviderAdapter adapter, String token) {}

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");
  }
}
