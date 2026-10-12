package tech.wenisch.petri.git;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.agent.model.ModelClientRegistry;
import tech.wenisch.petri.agent.model.ModelCredentialRef;
import tech.wenisch.petri.agent.model.ModelCredentialService;
import tech.wenisch.petri.agent.model.ModelCredentialStatus;
import tech.wenisch.petri.agent.model.ModelProvider;
import tech.wenisch.petri.agent.model.ModelRequest;
import tech.wenisch.petri.agent.model.ModelResponse;
import tech.wenisch.petri.agent.skill.SkillService;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;
import tech.wenisch.petri.workspace.WorkspaceModelPreferenceService;
import tech.wenisch.petri.workspace.WorkspaceModelPreferenceView;

/**
 * Suggests a commit message for the changes a commit would contain, using the
 * {@code conventional-commit} skill. A single model call without tools: nothing is changed and
 * the user reviews and edits the text before committing.
 */
@Service
public class CommitMessageService {
  static final String SKILL = "conventional-commit";
  private static final String DELIMITER = "PETRI_UNTRUSTED_CHANGES";
  private static final int MAX_DIFF_CHARS = 30_000;
  private static final int MAX_OUTPUT_TOKENS = 600;

  private final WorkspaceGitService git;
  private final SkillService skills;
  private final ModelCredentialService models;
  private final ModelClientRegistry clients;
  private final AppUserRepository users;
  private final AuditLogService audit;
  private final ProjectWorktreeService worktrees;
  private final WorkspaceModelPreferenceService preferences;

  public CommitMessageService(
      WorkspaceGitService git, SkillService skills, ModelCredentialService models,
      ModelClientRegistry clients, AppUserRepository users, AuditLogService audit,
      ProjectWorktreeService worktrees, WorkspaceModelPreferenceService preferences) {
    this.worktrees = worktrees;
    this.preferences = preferences;
    this.git = git;
    this.skills = skills;
    this.models = models;
    this.clients = clients;
    this.users = users;
    this.audit = audit;
  }

  public String generate(UUID worktreeId, boolean includeUnstaged, String actorEmail) {
    AppUser actor = users.findByEmailIgnoreCase(actorEmail)
        .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
    WorkspaceGitService.CommitContext context =
        git.commitContext(worktreeId, includeUnstaged, MAX_DIFF_CHARS);
    if (context.files().isEmpty()) {
      throw new IllegalStateException(includeUnstaged
          ? "There are no changes to describe" : "Nothing is staged; tick stage all to include every change");
    }
    UUID projectId = worktrees.get(worktreeId).projectId();
    ModelCredentialStatus model = workspaceModel(projectId, actor.getId());
    WorkspaceModelPreferenceView preference = preferences.get(projectId, actor.getId());
    String modelName = preference != null && preference.model() != null && !preference.model().isBlank()
        && model.id().equals(preference.providerInstanceId()) ? preference.model() : model.model();
    ModelResponse response = clients.require(model.provider()).respond(new ModelRequest(
        modelName, instructions(), prompt(context), null, List.of(), List.of(),
        MAX_OUTPUT_TOKENS, null, null).withCredential(new ModelCredentialRef(model.id(), actor.getId())));
    if (!"completed".equals(response.status()) || response.text() == null
        || response.text().isBlank()) {
      throw new IllegalStateException("The model did not return a commit message");
    }
    audit.record(actorEmail, "GIT_COMMIT_MESSAGE_GENERATED", "PROJECT_WORKTREE", worktreeId,
        "Generated a commit message suggestion");
    return clean(response.text());
  }

  /**
   * The model the workspace uses for chats: the user's saved choice for this project while they can
   * still call it, otherwise the same fallback the chat uses (Ollama first, then any usable one).
   */
  private ModelCredentialStatus workspaceModel(UUID projectId, UUID userId) {
    List<ModelCredentialStatus> usable = models.usable(userId);
    WorkspaceModelPreferenceView preference = preferences.get(projectId, userId);
    if (preference != null) {
      for (ModelCredentialStatus candidate : usable) {
        boolean matches = preference.providerInstanceId() != null
            ? candidate.id().equals(preference.providerInstanceId())
            : candidate.provider() == preference.provider();
        if (matches) return candidate;
      }
    }
    return usable.stream().filter(value -> value.provider() == ModelProvider.OLLAMA).findFirst()
        .or(() -> usable.stream().findFirst())
        .orElseThrow(() -> new IllegalStateException("No usable model provider is available"));
  }

  private String instructions() {
    return "You write Git commit messages.\n\n" + body(skills.load(SKILL).content());
  }

  static String prompt(WorkspaceGitService.CommitContext context) {
    StringBuilder out = new StringBuilder("Write the commit message for these changes.\n\n");
    if (!context.recentSubjects().isEmpty()) {
      out.append("Recent commit subjects of this repository (for style and scopes):\n");
      context.recentSubjects().forEach(subject -> out.append("- ").append(subject).append('\n'));
      out.append('\n');
    }
    out.append('<').append(DELIMITER).append(">\n").append("Changed files:\n");
    for (WorkspaceChangeView file : context.files()) {
      out.append(file.status()).append(' ');
      if (file.previousPath() != null) out.append(file.previousPath()).append(" -> ");
      out.append(file.path());
      if (file.additions() != null || file.deletions() != null) {
        out.append(" (+").append(file.additions() == null ? 0 : file.additions())
            .append(" -").append(file.deletions() == null ? 0 : file.deletions()).append(')');
      }
      out.append('\n');
    }
    out.append("\nDiff:\n").append(neutralize(context.diff()));
    if (context.truncated()) out.append("\n[diff truncated]\n");
    out.append("\n</").append(DELIMITER).append(">\n");
    return out.toString();
  }

  /** Drops code fences and surrounding quotes a model may add, and bounds the length. */
  static String clean(String raw) {
    String value = raw.strip();
    if (value.startsWith("```")) {
      int firstBreak = value.indexOf('\n');
      value = firstBreak < 0 ? "" : value.substring(firstBreak + 1);
      int fence = value.lastIndexOf("```");
      if (fence >= 0) value = value.substring(0, fence);
      value = value.strip();
    }
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
        && value.indexOf('\n') < 0) {
      value = value.substring(1, value.length() - 1).strip();
    }
    return value.length() > 5_000 ? value.substring(0, 5_000).strip() : value;
  }

  private static String body(String markdown) {
    String value = markdown.strip();
    if (value.startsWith("---")) {
      int end = value.indexOf("\n---", 3);
      if (end > 0) value = value.substring(end + 4);
    }
    return value.strip();
  }

  private static String neutralize(String text) {
    return text == null ? "" : text.replace(DELIMITER, "PETRI-UNTRUSTED-CHANGES");
  }
}
