package tech.wenisch.petri.git.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.secret.SecretCipher;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;

class GitAccountTokenReplacementTest {
  private final GitAccountConnectionRepository connections = mock(GitAccountConnectionRepository.class);
  private final GitProviderInstanceService instances = mock(GitProviderInstanceService.class);
  private final GitProviderAdapter adapter = mock(GitProviderAdapter.class);
  private final SecretCipher cipher = mock(SecretCipher.class);
  private final AuditLogService audit = mock(AuditLogService.class);
  private final UUID ownerId = UUID.randomUUID();
  private final UUID instanceId = UUID.randomUUID();
  private GitAccountConnectionService service;
  private GitAccountConnection connection;
  private GitProviderInstance instance;

  @BeforeEach
  void wire() {
    AppUserRepository users = mock(AppUserRepository.class);
    when(users.findByEmailIgnoreCase("u@petri.local"))
        .thenReturn(Optional.of(AppUser.oidcUser(ownerId, "u@petri.local", "USER")));
    instance = mock(GitProviderInstance.class);
    when(instance.isEnabled()).thenReturn(true);
    when(instance.getProviderType()).thenReturn(GitProviderType.FORGEJO);
    when(instance.getDisplayName()).thenReturn("Devhub");
    when(instance.getWebBaseUrl()).thenReturn("https://devhub.test");
    when(instances.requireForOwner(instanceId, ownerId)).thenReturn(instance);
    GitProviderAdapterRegistry registry = mock(GitProviderAdapterRegistry.class);
    when(registry.require(GitProviderType.FORGEJO)).thenReturn(adapter);
    when(cipher.encrypt(any())).thenAnswer(call -> "enc:" + call.getArgument(0));
    connection = new GitAccountConnection(UUID.randomUUID(), instanceId, ownerId,
        GitConnectionAuthMethod.PAT, new ProviderIdentity("42", "alice", "Alice"),
        "enc:old-token", null, null, null, null, Instant.now());
    when(connections.findLockedByIdAndOwnerId(connection.getId(), ownerId))
        .thenReturn(Optional.of(connection));
    service = new GitAccountConnectionService(connections, instances, registry,
        mock(ProjectProviderSourceRepository.class), users, cipher, audit);
  }

  @Test
  void aNewTokenOfTheSameAccountReplacesTheStoredOneAndReactivatesIt() {
    when(adapter.identity(instance, "new-token", GitConnectionAuthMethod.PAT))
        .thenReturn(new ProviderIdentity("42", "alice", "Alice"));
    connection.mark(GitConnectionStatus.INVALID, Instant.now());

    service.replaceToken(connection.getId(), "  new-token  ", "u@petri.local");

    assertThat(connection.accessTokenCiphertext()).isEqualTo("enc:new-token");
    assertThat(connection.getStatus()).isEqualTo(GitConnectionStatus.ACTIVE);
    verify(adapter).repositories(eq(instance), eq("new-token"), eq(GitConnectionAuthMethod.PAT),
        anyInt(), anyInt(), any());
    verify(audit).record(eq("u@petri.local"), eq("GIT_ACCOUNT_TOKEN_REPLACED"), any(), any(), any());
  }

  @Test
  void aTokenOfAnotherAccountIsRejectedAndTheOldOneKept() {
    when(adapter.identity(instance, "other-token", GitConnectionAuthMethod.PAT))
        .thenReturn(new ProviderIdentity("99", "mallory", "M"));

    assertThatIllegalArgumentException().isThrownBy(
        () -> service.replaceToken(connection.getId(), "other-token", "u@petri.local"))
        .withMessageContaining("mallory").withMessageContaining("alice");

    assertThat(connection.accessTokenCiphertext()).isEqualTo("enc:old-token");
    verify(adapter, never()).repositories(any(), any(), any(), anyInt(), anyInt(), any());
  }

  @Test
  void aBlankTokenAndOauthAccountsAreRefused() {
    assertThatIllegalArgumentException().isThrownBy(
        () -> service.replaceToken(connection.getId(), "   ", "u@petri.local"));

    GitAccountConnection oauth = new GitAccountConnection(UUID.randomUUID(), instanceId, ownerId,
        GitConnectionAuthMethod.OAUTH, new ProviderIdentity("42", "alice", "Alice"),
        "enc:t", null, null, null, null, Instant.now());
    when(connections.findLockedByIdAndOwnerId(oauth.getId(), ownerId)).thenReturn(Optional.of(oauth));
    assertThatIllegalStateException().isThrownBy(
        () -> service.replaceToken(oauth.getId(), "token", "u@petri.local"))
        .withMessageContaining("OAuth");
  }
}
