package tech.wenisch.petri.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.mockito.ArgumentCaptor;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.git.provider.CreatePullRequest;
import tech.wenisch.petri.git.provider.CreatedPullRequest;
import tech.wenisch.petri.git.provider.GitAccountConnectionService;
import tech.wenisch.petri.git.provider.GitProviderInstance;
import tech.wenisch.petri.git.provider.GitProviderInstanceRepository;
import tech.wenisch.petri.git.provider.GitProviderType;
import tech.wenisch.petri.git.provider.ProjectCredentialMode;
import tech.wenisch.petri.git.provider.ProjectProviderSource;
import tech.wenisch.petri.git.provider.ProjectProviderSourceService;

class PullRequestServiceTest {
  private final WorkspaceGitService git = mock(WorkspaceGitService.class);
  private final ProjectWorktreeService worktrees = mock(ProjectWorktreeService.class);
  private final ProjectProviderSourceService sources = mock(ProjectProviderSourceService.class);
  private final GitProviderInstanceRepository instances = mock(GitProviderInstanceRepository.class);
  private final GitAccountConnectionService connections = mock(GitAccountConnectionService.class);
  private final UUID worktreeId = UUID.randomUUID();
  private final UUID projectId = UUID.randomUUID();
  private final UUID connectionId = UUID.randomUUID();
  private PullRequestService service;
  private ProjectProviderSource source;

  private static WorkspaceGitService.PullRequestContext context(boolean published, int unpushed,
      int commits) {
    List<WorkspaceGitService.PullRequestCommit> list = new java.util.ArrayList<>();
    for (int index = 0; index < commits; index++) {
      list.add(new WorkspaceGitService.PullRequestCommit("abc" + index, "feat: change " + index,
          "Ada", Instant.now().toString()));
    }
    return new WorkspaceGitService.PullRequestContext("feature/x", "main", "main",
        List.of("main", "develop"), published, unpushed, 0, list,
        List.of(new WorkspaceGitService.PullRequestFile("A.java", "M", 3, 1)), "", false);
  }

  @BeforeEach
  void wire() {
    ProjectWorktreeView worktree = mock(ProjectWorktreeView.class);
    when(worktree.projectId()).thenReturn(projectId);
    when(worktrees.get(worktreeId)).thenReturn(worktree);
    source = mock(ProjectProviderSource.class);
    when(source.getConnectionId()).thenReturn(connectionId);
    when(source.getExternalRepositoryId()).thenReturn("42");
    when(source.getFullName()).thenReturn("acme/demo");
    when(source.getCredentialMode()).thenReturn(ProjectCredentialMode.CONNECTION);
    when(source.isCanWrite()).thenReturn(true);
    when(source.getProviderInstanceId()).thenReturn(UUID.randomUUID());
    when(sources.find(projectId)).thenReturn(source);
    GitProviderInstance instance = mock(GitProviderInstance.class);
    when(instance.getProviderType()).thenReturn(GitProviderType.GITLAB);
    when(instances.findById(any())).thenReturn(Optional.of(instance));
    service = new PullRequestService(git, worktrees, sources, instances, connections,
        mock(AuditLogService.class));
  }

  private PullRequestService.Command command(String title, String labels) {
    return new PullRequestService.Command(title, "Body", "main", true, true, labels, true, true);
  }

  @Test
  void createsThePullRequestFromAPushedBranchWithCleanedLabels() {
    when(git.pullRequestContext(eq(worktreeId), eq("main"), anyInt())).thenReturn(context(true, 0, 2));
    when(connections.createPullRequest(any(), anyString(), anyString(), any()))
        .thenReturn(new CreatedPullRequest(7, "https://git.test/pr/7", List.of()));

    CreatedPullRequest created = service.create(worktreeId,
        command("  Add the thing  ", " bug , ,needs-review,bug "), "u@petri.local");

    assertThat(created.number()).isEqualTo(7);
    ArgumentCaptor<CreatePullRequest> request = ArgumentCaptor.forClass(CreatePullRequest.class);
    verify(connections).createPullRequest(eq(connectionId), eq("42"), eq("acme/demo"), request.capture());
    assertThat(request.getValue().title()).isEqualTo("Add the thing");
    assertThat(request.getValue().head()).isEqualTo("feature/x");
    assertThat(request.getValue().base()).isEqualTo("main");
    assertThat(request.getValue().labels()).containsExactly("bug", "needs-review");
    assertThat(request.getValue().draft()).isTrue();
    assertThat(request.getValue().squash()).isTrue();
  }

  @Test
  void refusesWhenTheBranchIsNotOnTheRemoteOrHasUnpushedCommits() {
    when(git.pullRequestContext(eq(worktreeId), any(), anyInt())).thenReturn(context(false, 3, 3));
    assertThatIllegalStateException().isThrownBy(
        () -> service.create(worktreeId, command("T", null), "u@petri.local"))
        .withMessageContaining("Push feature/x first");

    when(git.pullRequestContext(eq(worktreeId), any(), anyInt())).thenReturn(context(true, 1, 3));
    assertThatIllegalStateException().isThrownBy(
        () -> service.create(worktreeId, command("T", null), "u@petri.local"))
        .withMessageContaining("Push feature/x first");
    verify(connections, never()).createPullRequest(any(), anyString(), anyString(), any());
  }

  @Test
  void refusesWithoutTitleCommitsOrAWritableConnectedAccount() {
    assertThatIllegalArgumentException().isThrownBy(
        () -> service.create(worktreeId, command("  ", null), "u@petri.local"))
        .withMessageContaining("title");

    when(git.pullRequestContext(eq(worktreeId), any(), anyInt())).thenReturn(context(true, 0, 0));
    assertThatIllegalStateException().isThrownBy(
        () -> service.create(worktreeId, command("T", null), "u@petri.local"))
        .withMessageContaining("no commits");

    when(source.isCanWrite()).thenReturn(false);
    when(git.pullRequestContext(eq(worktreeId), any(), anyInt())).thenReturn(context(true, 0, 1));
    assertThatIllegalStateException().isThrownBy(
        () -> service.create(worktreeId, command("T", null), "u@petri.local"))
        .withMessageContaining("no write access");

    when(sources.find(projectId)).thenReturn(null);
    assertThat(service.preview(worktreeId, "main").unavailable())
        .contains("connected Git account");
  }

  @Test
  void thePreviewTellsTheDialogWhatIsMissingAndWhichProviderItIs() {
    when(git.pullRequestContext(eq(worktreeId), any(), anyInt())).thenReturn(context(false, 2, 2));

    PullRequestService.Preview preview = service.preview(worktreeId, null);

    assertThat(preview.published()).isFalse();
    assertThat(preview.unpushedCommits()).isEqualTo(2);
    assertThat(preview.commits()).hasSize(2);
    assertThat(preview.provider()).isEqualTo("GITLAB");
    assertThat(preview.repository()).isEqualTo("acme/demo");
    assertThat(preview.unavailable()).isNull();
  }
}
