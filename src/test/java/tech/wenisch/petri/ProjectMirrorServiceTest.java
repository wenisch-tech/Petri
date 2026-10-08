package tech.wenisch.petri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.audit.AuditEventRepository;
import tech.wenisch.petri.agent.change.ChangeProposalService;
import tech.wenisch.petri.agent.change.ChangeProposalStatus;
import tech.wenisch.petri.agent.change.ChangeProposalView;
import tech.wenisch.petri.agent.tool.AgentToolRegistry;
import tech.wenisch.petri.agent.tool.ReadFileResult;
import tech.wenisch.petri.agent.tool.ToolExecutionContext;
import tech.wenisch.petri.git.ProjectMirrorService;
import tech.wenisch.petri.git.ProjectWorktreeService;
import tech.wenisch.petri.git.ProjectWorktreeStatus;
import tech.wenisch.petri.project.CreateProjectCommand;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectService;
import tech.wenisch.petri.workspace.WorktreeLocator;

@SpringBootTest
@Transactional
class ProjectMirrorServiceTest {
  private static final String ADMIN_EMAIL = "test-admin@petri.local";

  @TempDir static Path temporaryDirectory;

  @DynamicPropertySource
  static void workspaceProperties(DynamicPropertyRegistry registry) {
    registry.add("petri.workspace.root", () -> temporaryDirectory.resolve("workspace").toString());
  }

  @Autowired ProjectService projectService;
  @Autowired ProjectMirrorService mirrors;
  @Autowired ProjectWorktreeService worktrees;
  @Autowired WorktreeLocator worktreeLocator;
  @Autowired AgentToolRegistry tools;
  @Autowired ChangeProposalService proposals;
  @Autowired AuditEventRepository auditEvents;

  @Test
  void clonesAndFetchesLocalRepositoryIntoControlledMirror() throws Exception {
    Path source = createSourceRepository();
    Project project = projectService.create(
        new CreateProjectCommand(
            "Mirror test " + UUID.randomUUID(), source.toString(), "main"),
        ADMIN_EMAIL);

    assertThat(mirrors.synchronize(project.getId(), ADMIN_EMAIL).initialized()).isTrue();
    Path mirror = temporaryDirectory.resolve("workspace/mirrors/" + project.getId() + ".git");
    assertThat(mirror.resolve("HEAD")).isRegularFile();
    assertThat(git("--git-dir=" + mirror, "rev-parse", "refs/heads/main")).isNotBlank();
    assertThat(auditActions()).contains("PROJECT_MIRROR_CLONED");

    Files.writeString(source.resolve("README.md"), "second revision\n");
    git("-C", source.toString(), "add", "README.md");
    git("-C", source.toString(), "commit", "-m", "second");
    String sourceHead = git("-C", source.toString(), "rev-parse", "HEAD");

    assertThat(mirrors.synchronize(project.getId(), ADMIN_EMAIL).initialized()).isTrue();
    assertThat(git("--git-dir=" + mirror, "rev-parse", "refs/heads/main"))
        .isEqualTo(sourceHead);
    assertThat(auditActions()).contains("PROJECT_MIRROR_FETCHED");

    var worktree = worktrees.create(project.getId(), "main", ADMIN_EMAIL);
    Path worktreePath = temporaryDirectory.resolve(
        "workspace/runs/" + project.getId() + "/" + worktree.id());
    assertThat(worktree.status()).isEqualTo(ProjectWorktreeStatus.ACTIVE);
    assertThat(worktreeLocator.requireActiveWorktree(worktree.id())).isEqualTo(worktreePath);
    assertThat(worktreePath.resolve("README.md")).hasContent("second revision\n");
    ToolExecutionContext toolContext = new ToolExecutionContext(worktree.id());
    ReadFileResult read = (ReadFileResult) tools.execute(
        "read_file", toolContext, java.util.Map.of("path", "README.md")).output();
    ChangeProposalView proposal = (ChangeProposalView) tools.execute(
        "create_patch",
        toolContext,
        java.util.Map.of(
            "path", "README.md",
            "expectedSha256", read.sha256(),
            "replacementContent", "approved revision\n"))
        .output();
    assertThat(proposal.status()).isEqualTo(ChangeProposalStatus.PROPOSED);
    assertThat(proposal.unifiedDiff()).contains("-second revision", "+approved revision");
    ChangeProposalView shown = (ChangeProposalView) tools.execute(
        "show_diff", toolContext, java.util.Map.of("proposalId", proposal.id().toString())).output();
    assertThat(shown.id()).isEqualTo(proposal.id());
    assertThat(worktreePath.resolve("README.md")).hasContent("second revision\n");
    assertThatIllegalStateException().isThrownBy(() -> proposals.apply(proposal.id()));

    proposals.approve(proposal.id(), ADMIN_EMAIL);
    ChangeProposalView applied = proposals.apply(proposal.id());
    assertThat(applied.status()).isEqualTo(ChangeProposalStatus.APPLIED);
    assertThat(worktreePath.resolve("README.md")).hasContent("approved revision\n");
    assertThat(auditActions()).contains("CHANGE_PROPOSAL_APPROVED", "CHANGE_PROPOSAL_APPLIED");

    ReadFileResult nextRead = (ReadFileResult) tools.execute(
        "read_file", toolContext, java.util.Map.of("path", "README.md")).output();
    ChangeProposalView stale = (ChangeProposalView) tools.execute(
        "create_patch",
        toolContext,
        java.util.Map.of(
            "path", "README.md",
            "expectedSha256", nextRead.sha256(),
            "replacementContent", "must not overwrite\n"))
        .output();
    proposals.approve(stale.id(), ADMIN_EMAIL);
    Files.writeString(worktreePath.resolve("README.md"), "external edit\n");
    assertThat(proposals.apply(stale.id()).status()).isEqualTo(ChangeProposalStatus.CONFLICT);
    assertThat(worktreePath.resolve("README.md")).hasContent("external edit\n");
    assertThat(auditActions()).contains("CHANGE_PROPOSAL_CONFLICT");

    ReadFileResult rejectionRead = (ReadFileResult) tools.execute(
        "read_file", toolContext, java.util.Map.of("path", "README.md")).output();
    ChangeProposalView rejectedCandidate = (ChangeProposalView) tools.execute(
        "create_patch",
        toolContext,
        java.util.Map.of(
            "path", "README.md",
            "expectedSha256", rejectionRead.sha256(),
            "replacementContent", "rejected edit\n"))
        .output();
    assertThat(proposals.reject(rejectedCandidate.id(), ADMIN_EMAIL).status())
        .isEqualTo(ChangeProposalStatus.REJECTED);
    assertThatIllegalStateException().isThrownBy(() -> proposals.apply(rejectedCandidate.id()));
    assertThat(worktreePath.resolve("README.md")).hasContent("external edit\n");
    assertThat(auditActions()).contains("CHANGE_PROPOSAL_REJECTED");

    Files.writeString(worktreePath.resolve("untracked.txt"), "disposable\n");
    assertThat(auditActions()).contains("PROJECT_WORKTREE_CREATED");

    var removed = worktrees.remove(worktree.id(), ADMIN_EMAIL);
    assertThat(removed.status()).isEqualTo(ProjectWorktreeStatus.REMOVED);
    assertThat(worktreePath).doesNotExist();
    assertThat(auditActions()).contains("PROJECT_WORKTREE_REMOVED");
    assertThatIllegalStateException()
        .isThrownBy(() -> worktreeLocator.requireActiveWorktree(worktree.id()));
  }

  private Path createSourceRepository() throws Exception {
    Path source = temporaryDirectory.resolve("source-" + UUID.randomUUID());
    git("init", "--initial-branch=main", source.toString());
    git("-C", source.toString(), "config", "user.name", "Petri Test");
    git("-C", source.toString(), "config", "user.email", "petri@example.test");
    Files.writeString(source.resolve("README.md"), "first revision\n");
    git("-C", source.toString(), "add", "README.md");
    git("-C", source.toString(), "commit", "-m", "first");
    return source;
  }

  private static String git(String... arguments) throws Exception {
    java.util.ArrayList<String> command = new java.util.ArrayList<>(List.of("git"));
    command.addAll(List.of(arguments));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
    int exitCode = process.waitFor();
    if (exitCode != 0) throw new AssertionError("Git test setup failed: " + output);
    return output;
  }

  private List<String> auditActions() {
    return auditEvents.findTop100ByOrderByOccurredAtDesc().stream()
        .map(event -> event.getAction())
        .toList();
  }
}
