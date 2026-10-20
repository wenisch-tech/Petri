package tech.wenisch.petri.automation;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.agent.model.ModelCredentialService;
import tech.wenisch.petri.agent.model.ModelCredentialStatus;
import tech.wenisch.petri.agent.orchestration.AgentRunExecutor;
import tech.wenisch.petri.agent.orchestration.AgentRunService;
import tech.wenisch.petri.agent.orchestration.AgentRunStatus;
import tech.wenisch.petri.agent.orchestration.AgentRunView;
import tech.wenisch.petri.agent.orchestration.AgentWorkflowRole;
import tech.wenisch.petri.agent.orchestration.CreateAgentRunCommand;
import tech.wenisch.petri.agent.orchestration.OrchestrationProfileService;
import tech.wenisch.petri.agent.orchestration.OrchestrationRoleProfileView;
import tech.wenisch.petri.agent.skill.SkillService;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.git.ProjectMirrorService;
import tech.wenisch.petri.git.ProjectWorktreeService;
import tech.wenisch.petri.git.ProjectWorktreeView;
import tech.wenisch.petri.git.PullRequestDiffService;
import tech.wenisch.petri.git.provider.GitAccountConnectionService;
import tech.wenisch.petri.git.provider.ProjectProviderSource;
import tech.wenisch.petri.git.provider.ProjectProviderSourceService;
import tech.wenisch.petri.git.provider.PullRequestRef;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectRepository;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;
import tech.wenisch.petri.vulnerability.VulnerabilityReportService;

/**
 * Runs one code review: fetches the pull request head, checks it out in a throw-away worktree,
 * lets a read-only agent review it with the {@code code-review} skill and publishes the result.
 */
@Service
public class CodeReviewAutomation {
  private static final Logger log = LoggerFactory.getLogger(CodeReviewAutomation.class);
  private static final int MAX_LISTED_FILES = 300;

  /** Everything a review needs; the settings are snapshotted when the job is queued. */
  public record Job(
      UUID runId, UUID projectId, PullRequestRef pr, AutomationPostMode postMode,
      UUID providerInstanceId, String model, int maxModelTurns, AutomationKind kind,
      boolean manual) {
    /** A scheduled or polled run. */
    public Job(
        UUID runId, UUID projectId, PullRequestRef pr, AutomationPostMode postMode,
        UUID providerInstanceId, String model, int maxModelTurns, AutomationKind kind) {
      this(runId, projectId, pr, postMode, providerInstanceId, model, maxModelTurns, kind, false);
    }

    /** A review that comments, which is what the original automation did. */
    public Job(
        UUID runId, UUID projectId, PullRequestRef pr, AutomationPostMode postMode,
        UUID providerInstanceId, String model, int maxModelTurns) {
      this(runId, projectId, pr, postMode, providerInstanceId, model, maxModelTurns,
          AutomationKind.CODE_REVIEW_AND_COMMENT);
    }
  }

  private final AutomationRunService runs;
  private final AutomationReviewService reviews;
  private final ProjectRepository projects;
  private final AppUserRepository users;
  private final ProjectProviderSourceService sources;
  private final GitAccountConnectionService connections;
  private final ProjectMirrorService mirrors;
  private final PullRequestDiffService diffs;
  private final ProjectWorktreeService worktrees;
  private final SkillService skills;
  private final AgentRunService agentRuns;
  private final AgentRunExecutor executor;
  private final ModelCredentialService models;
  private final OrchestrationProfileService orchestrationProfiles;
  private final AuditLogService audit;
  private final PullRequestImprovementService improvements;
  private final VulnerabilityReportService vulnerabilityReports;
  private final int maxDiffBytes;

  CodeReviewAutomation(
      AutomationRunService runs, AutomationReviewService reviews, ProjectRepository projects,
      AppUserRepository users,
      ProjectProviderSourceService sources, GitAccountConnectionService connections,
      ProjectMirrorService mirrors, PullRequestDiffService diffs, ProjectWorktreeService worktrees,
      SkillService skills, AgentRunService agentRuns, AgentRunExecutor executor,
      ModelCredentialService models, OrchestrationProfileService orchestrationProfiles,
      AuditLogService audit, PullRequestImprovementService improvements,
      VulnerabilityReportService vulnerabilityReports,
      @Value("${petri.automation.max-diff-bytes:120000}") int maxDiffBytes) {
    this.runs = runs;
    this.reviews = reviews;
    this.projects = projects;
    this.users = users;
    this.sources = sources;
    this.connections = connections;
    this.mirrors = mirrors;
    this.diffs = diffs;
    this.worktrees = worktrees;
    this.skills = skills;
    this.agentRuns = agentRuns;
    this.executor = executor;
    this.models = models;
    this.orchestrationProfiles = orchestrationProfiles;
    this.audit = audit;
    this.improvements = improvements;
    this.vulnerabilityReports = vulnerabilityReports;
    this.maxDiffBytes = maxDiffBytes;
  }

  /** Never throws: every outcome is recorded on the automation run. */
  public void execute(Job job) {
    UUID worktreeId = null;
    Project project = null;
    AppUser owner = null;
    try {
      runs.running(job.runId());
      project = projects.findById(job.projectId())
          .orElseThrow(() -> new IllegalStateException("Project no longer exists"));
      owner = users.findById(project.getCreatedBy())
          .orElseThrow(() -> new IllegalStateException("Project owner no longer exists"));
      ProjectProviderSource source = sources.find(job.projectId());
      if (source == null) {
        throw new IllegalStateException("Project is no longer linked to a connected Git account");
      }
      // The pull request may have changed since it was queued: converted to a draft, closed or
      // pushed to. Look again before spending any work on it.
      String stale = staleReason(source, job.pr(), job.manual());
      if (stale != null) {
        runs.skipped(job.runId(), stale);
        return;
      }
      acknowledge(source, job.pr());
      audit.record("automation", "AUTOMATION_RUN_STARTED", "PROJECT", job.projectId(),
          "Code review of pull request #" + job.pr().number() + " started");

      mirrors.synchronize(job.projectId(), owner.getEmail());
      mirrors.fetchPullRequestRef(
          job.projectId(), job.pr().headFetchRef(), "refs/petri/pr/" + job.pr().number());
      PullRequestDiffService.PullRequestDiff diff = diffs.diff(
          job.projectId(), job.pr().number(), job.pr().baseRef(), maxDiffBytes);
      if (!diff.headSha().equals(job.pr().headSha())) {
        runs.skipped(job.runId(), "The pull request was updated while this review was queued; "
            + "the new head is reviewed on the next poll.");
        return;
      }
      if (diff.changedFiles().isEmpty()) {
        runs.skipped(job.runId(), "The pull request has no changes against its base branch.");
        return;
      }

      ProjectWorktreeView worktree = worktrees.create(job.projectId(), diff.headSha(), owner.getEmail());
      worktreeId = worktree.id();
      runs.workspace(job.runId(), worktreeId, diff.changedFiles());

      boolean scan = job.kind().vulnerability();
      // A scan has no earlier result to compare with: each head is scanned as it is.
      ReviewSubmission.Previous previous = scan ? null : reviews.previous(job.runId()).orElse(null);
      String sinceDiff = previous == null ? null : diffs.sinceCommit(
          job.projectId(), job.pr().number(), previous.headSha(), maxDiffBytes / 2);
      String instructions = scan
          ? VulnerabilityPrompts.instructions(skillText(job.kind()))
          : CodeReviewPrompts.instructions(skillText(job.kind()));
      String task = scan
          ? VulnerabilityPrompts.task(job.pr(), diff.changedFiles(), MAX_LISTED_FILES,
              job.kind().isCveOnly())
          : CodeReviewPrompts.task(job.pr(), diff.changedFiles(), diff.diff(),
              diff.truncated(), MAX_LISTED_FILES, previous, sinceDiff);
      AgentRunView created = agentRuns.createAutomationRun(
          worktreeId,
          command(job, owner.getId(), task),
          owner.getId(), "automation:" + job.runId(), job.runId(), instructions,
          tech.wenisch.petri.agent.tool.AutomationToolPolicy.forCapabilities(
              tech.wenisch.petri.agent.skill.SkillComposer.requires(
                  skills, job.kind().skill(), job.kind().baseSkill()), scan));
      runs.agentRun(job.runId(), created.id());
      AgentRunView finished = executor.execute(
          created.id(), worktreeId, owner.getId(), "automation:" + job.runId());

      AutomationRun result = runs.require(job.runId());
      if (scan && result.getReviewMarkdown() == null) {
        // The scan saved its table under this run; keep it for the branch (the Vulnerabilities
        // tab) and turn it into the comment and the findings an improvement works on.
        var report = vulnerabilityReports.find(job.projectId(), null, job.runId().toString())
            .orElse(null);
        if (report != null) {
          if (job.kind().isCveOnly()) {
            // Keeps the secrets and misconfigurations of an earlier full scan of this branch.
            vulnerabilityReports.save(job.projectId(), job.pr().headRef(), job.pr().headSha(),
                report.scanner(), report.summary(), report.complete(), report.completenessNote(),
                report.findings(), owner.getId(), false, true);
          } else {
            vulnerabilityReports.save(job.projectId(), job.pr().headRef(), job.pr().headSha(),
                report.scanner(), report.summary(), report.complete(), report.completenessNote(),
                report.findings(), owner.getId());
          }
          reviews.submitVulnerabilityReport(job.runId(), report);
          result = runs.require(job.runId());
        }
      }
      if (result.getReviewMarkdown() == null && finished.status() == AgentRunStatus.COMPLETED
          && finished.finalOutput() != null && !finished.finalOutput().isBlank()) {
        // The agent answered in text without calling submit_review (typically after the loop
        // guard took its tools away). Its answer is still the review.
        reviews.submitUnstructured(job.runId(), finished.finalOutput());
        result = runs.require(job.runId());
      }
      if (result.getReviewMarkdown() == null) {
        String reason = finished.status() == AgentRunStatus.COMPLETED
            ? "The review agent finished without submitting a review."
            : "The review agent ended as " + finished.status()
                + (finished.errorMessage() == null ? "." : ": " + finished.errorMessage());
        runs.failed(job.runId(), reason);
        audit.record("automation", "AUTOMATION_RUN_FAILED", "PROJECT", job.projectId(), reason);
        return;
      }
      if (!job.kind().comments()) {
        runs.reviewed(job.runId());
        audit.record("automation", "AUTOMATION_REVIEW_COMPLETED", "PROJECT", job.projectId(),
            "Reviewed pull request #" + job.pr().number() + " without publishing");
      } else if (job.postMode() == AutomationPostMode.DRAFT) {
        runs.awaitingApproval(job.runId());
      } else {
        publish(job.runId(), "automation");
      }
    } catch (RuntimeException exception) {
      log.warn("Code review run {} failed", job.runId(), exception);
      String reason = exception.getMessage() == null ? exception.getClass().getSimpleName()
          : exception.getMessage();
      runs.failed(job.runId(), reason);
      audit.record("automation", "AUTOMATION_RUN_FAILED", "PROJECT", job.projectId(),
          "Code review of pull request #" + job.pr().number() + " failed: " + reason);
    } finally {
      if (worktreeId != null && owner != null) {
        try {
          worktrees.remove(worktreeId, job.projectId(), owner.getEmail());
        } catch (RuntimeException exception) {
          log.warn("Could not remove review worktree {}", worktreeId, exception);
        }
      }
    }
  }

  /**
   * The review method plus what the automation's own skill adds to it: the review automations share
   * one method (the {@code code-review} skill) and differ in what happens with the result.
   */
  private String skillText(AutomationKind kind) {
    return tech.wenisch.petri.agent.skill.SkillComposer.compose(
        skills, kind.skill(), kind.baseSkill());
  }

  /** Posts a stored review to its pull request. Used for automatic posting and for approvals. */
  public void publish(UUID runId, String actor) {
    AutomationRun run = runs.require(runId);
    if (run.getReviewMarkdown() == null) throw new IllegalStateException("The run has no review");
    ProjectProviderSource source = sources.find(run.getProjectId());
    if (source == null) throw new IllegalStateException("Project is no longer linked to a Git account");
    try {
      String markdown = run.getReviewMarkdown();
      PullRequestImprovementService.Intent intent = null;
      if (run.getKind().improves()) {
        intent = improvements.intent(run);
        if (intent.sentence() != null) markdown = markdown + "\n\n---\n\n" + intent.sentence();
      }
      String url = connections.postPullRequestComment(
          source.getConnectionId(), source.getExternalRepositoryId(), source.getFullName(),
          Long.parseLong(run.getExternalRef()), run.getWebUrl(), markdown);
      runs.posted(runId, url);
      audit.record(actor, "AUTOMATION_COMMENT_POSTED", "PROJECT", run.getProjectId(),
          "Posted review on pull request #" + run.getExternalRef());
      if (intent != null && intent.improve()) improvements.start(runId, intent, actor);
    } catch (RuntimeException exception) {
      String reason = "Could not post the review comment: "
          + (exception.getMessage() == null ? "unknown error" : exception.getMessage())
          + ". Check that the connected account's token may comment on pull requests.";
      log.warn("Posting the review of pull request #{} failed: {}", run.getExternalRef(),
          exception.getMessage());
      runs.failed(runId, reason);
      audit.record(actor, "AUTOMATION_RUN_FAILED", "PROJECT", run.getProjectId(), reason);
    }
  }

  /** Why the pull request should no longer be reviewed, or null when it still should. */
  private String staleReason(
      ProjectProviderSource source, PullRequestRef queued, boolean manual) {
    List<PullRequestRef> open = connections.openPullRequests(
        source.getConnectionId(), source.getExternalRepositoryId(), source.getFullName())
        .pullRequests();
    PullRequestRef current = open.stream()
        .filter(candidate -> candidate.number() == queued.number()).findFirst().orElse(null);
    if (current == null) return "The pull request is closed or merged.";
    if (current.draft() && !manual) {
      return "The pull request is a draft; it is reviewed once it is ready.";
    }
    if (!current.headSha().equals(queued.headSha())) {
      return "The pull request was updated while this review was queued; the new head is "
          + "reviewed on the next poll.";
    }
    return null;
  }

  /** Marks the pull request with an eyes reaction. Cosmetic, so a failure never stops the review. */
  private void acknowledge(ProjectProviderSource source, PullRequestRef pr) {
    try {
      connections.reactToPullRequest(source.getConnectionId(), source.getExternalRepositoryId(),
          source.getFullName(), pr.number(), "eyes");
    } catch (RuntimeException exception) {
      String message = exception.getMessage() == null ? "" : exception.getMessage();
      if (message.contains("HTTP 401") || message.contains("HTTP 403")) {
        // The token may not write to pull requests, so the review comment would be refused as
        // well. Stop before the model is used instead of discovering it afterwards.
        throw new IllegalStateException("The connected Git account is not allowed to write to "
            + "pull requests of " + source.getFullName() + " (" + message + "). Give its token "
            + "permission to write issues and pull requests (Forgejo: scope write:issue) in the "
            + "Git account settings and run the review again.");
      }
      log.info("Could not add the eyes reaction to pull request #{}: {}", pr.number(), message);
    }
  }

  /**
   * The automation's own provider and model when it names them; otherwise the model the project's
   * orchestrator is configured with (the workflow role profile of the project owner), so reviews
   * run on what the user selected for orchestration rather than on whichever provider is first.
   */
  private CreateAgentRunCommand command(Job job, UUID ownerId, String task) {
    String name = blankToNull(job.model());
    if (job.providerInstanceId() != null) {
      ModelCredentialStatus status = models.resolve(ownerId, null, job.providerInstanceId());
      return new CreateAgentRunCommand(task, status.provider(), job.maxModelTurns(),
          name != null ? name : status.model(), null, null, null, null, status.id());
    }
    OrchestrationRoleProfileView profile = orchestrationProfiles.get(job.projectId(), ownerId)
        .stream().filter(value -> value.role() == AgentWorkflowRole.ORCHESTRATOR).findFirst()
        .orElseThrow(() -> new IllegalStateException("The project has no orchestrator model"));
    return new CreateAgentRunCommand(task, profile.provider(), job.maxModelTurns(),
        name != null ? name : profile.model(), profile.maxOutputTokens(),
        profile.contextWindowTokens(), profile.reasoningEffort(), profile.textVerbosity(),
        profile.providerInstanceId());
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
