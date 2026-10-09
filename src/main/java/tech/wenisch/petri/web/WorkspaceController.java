package tech.wenisch.petri.web;

import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import tech.wenisch.petri.agent.change.ChangeProposalService;
import tech.wenisch.petri.agent.model.ModelCredentialService;
import tech.wenisch.petri.agent.model.ModelCredentialStatus;
import tech.wenisch.petri.agent.model.ModelProvider;
import tech.wenisch.petri.agent.model.ReasoningEffort;
import tech.wenisch.petri.agent.model.TextVerbosity;
import tech.wenisch.petri.agent.orchestration.AgentChatSummaryView;
import tech.wenisch.petri.agent.orchestration.AgentChatView;
import tech.wenisch.petri.agent.orchestration.AgentRunService;
import tech.wenisch.petri.agent.orchestration.AgentRunView;
import tech.wenisch.petri.agent.run.TestRunService;
import tech.wenisch.petri.git.ProjectWorktreeService;
import tech.wenisch.petri.git.ProjectWorktreeStatus;
import tech.wenisch.petri.git.ProjectWorktreeView;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectRepository;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;
import tech.wenisch.petri.workspace.WorkspaceModelPreferenceService;
import tech.wenisch.petri.workspace.WorkspaceModelPreferenceView;

/** The project-centric shell. Chat endpoints stay separate so their SSE contract remains stable. */
@Controller
@RequestMapping("/workspace")
public class WorkspaceController {
  private final ProjectRepository projects;
  private final ProjectWorktreeService worktrees;
  private final AgentRunService runs;
  private final ModelCredentialService modelCredentials;
  private final AppUserRepository users;
  private final ChangeProposalService changes;
  private final TestRunService testRuns;
  private final WorkspaceModelPreferenceService preferences;

  public WorkspaceController(
      ProjectRepository projects,
      ProjectWorktreeService worktrees,
      AgentRunService runs,
      ModelCredentialService modelCredentials,
      AppUserRepository users,
      ChangeProposalService changes,
      TestRunService testRuns,
      WorkspaceModelPreferenceService preferences) {
    this.projects = projects;
    this.worktrees = worktrees;
    this.runs = runs;
    this.modelCredentials = modelCredentials;
    this.users = users;
    this.changes = changes;
    this.testRuns = testRuns;
    this.preferences = preferences;
  }

  @GetMapping("/projects/{projectId}")
  String workspace(
      @PathVariable UUID projectId,
      @RequestParam(required = false) UUID worktree,
      @RequestParam(required = false) UUID chat,
      @RequestParam(required = false) String file,
      Principal principal,
      Model model) {
    Project project = projects.findById(projectId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown project"));
    AppUser actor = actor(principal);
    List<ProjectWorktreeView> activeWorktrees = worktrees.list(projectId).stream()
        .filter(value -> value.status() == ProjectWorktreeStatus.ACTIVE)
        .toList();
    ProjectWorktreeView selectedWorktree = selectWorktree(activeWorktrees, worktree);
    AgentChatView selectedChat = null;
    List<AgentChatView> chats = List.of();
    List<AgentRunView> chatRuns = List.of();
    List<AgentChatSummaryView> summaries = List.of();
    if (selectedWorktree != null) {
      chats = runs.listChats(selectedWorktree.id(), actor.getId());
      if (chat != null) {
        selectedChat = runs.chat(chat, actor.getId());
        if (!selectedWorktree.id().equals(selectedChat.worktreeId())) {
          throw new IllegalArgumentException("Chat belongs to another worktree");
        }
      } else if (!chats.isEmpty()) {
        selectedChat = chats.getFirst();
      }
      if (selectedChat != null) {
        chatRuns = runs.chatRuns(selectedChat.id());
        summaries = runs.chatSummaries(selectedChat.id());
      }
    }
    List<ModelCredentialStatus> configuredModels = java.util.Arrays.stream(ModelProvider.values())
        .map(modelCredentials::status).filter(ModelCredentialStatus::configured).toList();
    ModelCredentialStatus preferredModel = configuredModels.stream()
        .filter(value -> value.provider() == ModelProvider.OLLAMA).findFirst()
        .orElseGet(() -> configuredModels.isEmpty()
            ? modelCredentials.status(ModelProvider.OLLAMA) : configuredModels.getFirst());
    WorkspaceModelPreferenceView savedPreference = preferences.get(projectId, actor.getId());
    ModelCredentialStatus selectedModel = savedPreference != null
        ? configuredModels.stream().filter(value -> value.provider() == savedPreference.provider())
            .findFirst().orElse(preferredModel)
        : preferredModel;
    String selectedModelName = savedPreference != null
        && selectedModel.provider() == savedPreference.provider()
        ? savedPreference.model() : selectedModel.model();
    boolean savedModelUnavailable = savedPreference != null
        && configuredModels.stream().noneMatch(value -> value.provider() == savedPreference.provider());

    model.addAttribute("project", project);
    model.addAttribute("worktrees", activeWorktrees);
    model.addAttribute("selectedWorktree", selectedWorktree);
    model.addAttribute("selectedFile", file);
    model.addAttribute("chats", chats);
    model.addAttribute("selectedChat", selectedChat);
    model.addAttribute("chatRuns", chatRuns);
    model.addAttribute("summariesByThrough", summaries.stream().collect(java.util.stream.Collectors.groupingBy(
        AgentChatSummaryView::throughMessageIndex)));
    model.addAttribute("changeProposalsByRun", chatRuns.stream().collect(java.util.stream.Collectors.toMap(
        AgentRunView::id, value -> changes.listForAgentRun(value.id()))));
    model.addAttribute("testRunsByRun", chatRuns.stream().collect(java.util.stream.Collectors.toMap(
        AgentRunView::id, value -> testRuns.listForAgentRun(value.id()))));
    model.addAttribute("configuredModels", configuredModels);
    model.addAttribute("modelCredential", preferredModel);
    model.addAttribute("selectedModelCredential", selectedModel);
    model.addAttribute("selectedModelName", selectedModelName);
    model.addAttribute("workspaceModelPreference", savedPreference);
    model.addAttribute("savedModelUnavailable", savedModelUnavailable);
    model.addAttribute("modelConfigured", !configuredModels.isEmpty());
    model.addAttribute("reasoningEfforts", ReasoningEffort.values());
    model.addAttribute("textVerbosities", TextVerbosity.values());
    return "workspace";
  }

  private static ProjectWorktreeView selectWorktree(List<ProjectWorktreeView> worktrees, UUID id) {
    if (id == null) return worktrees.isEmpty() ? null : worktrees.getFirst();
    return worktrees.stream().filter(value -> value.id().equals(id)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Worktree belongs to another project or is inactive"));
  }

  private AppUser actor(Principal principal) {
    return users.findByEmailIgnoreCase(principal.getName())
        .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
  }
}
