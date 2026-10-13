package tech.wenisch.petri.web;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tech.wenisch.petri.agent.model.ModelCatalogService;
import tech.wenisch.petri.agent.model.ModelCredentialService;
import tech.wenisch.petri.agent.model.ModelCredentialStatus;
import tech.wenisch.petri.agent.model.ReasoningEffort;
import tech.wenisch.petri.agent.model.TextVerbosity;
import tech.wenisch.petri.agent.orchestration.AgentWorkflowRole;
import tech.wenisch.petri.agent.orchestration.OrchestrationProfileService;
import tech.wenisch.petri.agent.orchestration.OrchestrationRoleProfileView;
import tech.wenisch.petri.agent.orchestration.SaveOrchestrationRoleProfile;
import tech.wenisch.petri.project.ProjectAccessService;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;

/** The model, budgets and instructions of each gated workflow role: the project's model settings. */
@Controller
public class ProjectRolesController {
  private final ProjectAccessService access;
  private final OrchestrationProfileService profiles;
  private final ModelCredentialService credentials;
  private final ModelCatalogService catalog;
  private final AppUserRepository users;

  public ProjectRolesController(
      ProjectAccessService access,
      OrchestrationProfileService profiles,
      ModelCredentialService credentials,
      ModelCatalogService catalog,
      AppUserRepository users) {
    this.access = access;
    this.profiles = profiles;
    this.credentials = credentials;
    this.catalog = catalog;
    this.users = users;
  }

  @GetMapping("/projects/{projectId}/settings/roles")
  String roles(
      @PathVariable UUID projectId,
      @RequestParam(defaultValue = "false") boolean embedded,
      Principal principal,
      Model model) {
    var project = access.requireProject(projectId, principal.getName());
    AppUser actor = actor(principal);
    List<ModelCredentialStatus> usable = credentials.usable(actor.getId());
    List<RoleRow> rows = new ArrayList<>();
    boolean sameModel = false;
    if (!usable.isEmpty()) {
      for (OrchestrationRoleProfileView view : profiles.get(projectId, actor.getId())) {
        rows.add(new RoleRow(view.role().name(), label(view.role()), description(view.role()),
            icon(view.role()), view, selectedInstance(view, usable)));
      }
      var implementer = rows.stream().filter(row -> row.key().equals("IMPLEMENTER")).findFirst();
      var reviewer = rows.stream().filter(row -> row.key().equals("REVIEWER")).findFirst();
      sameModel = implementer.isPresent() && reviewer.isPresent()
          && java.util.Objects.equals(implementer.get().instanceId(), reviewer.get().instanceId())
          && implementer.get().profile().model().equals(reviewer.get().profile().model());
    }
    model.addAttribute("project", project);
    model.addAttribute("rows", rows);
    model.addAttribute("providers", usable);
    model.addAttribute("modelIds", catalog.modelIds(actor.getId(), usable));
    model.addAttribute("sameModel", sameModel);
    model.addAttribute("reasoningEfforts", ReasoningEffort.values());
    model.addAttribute("textVerbosities", TextVerbosity.values());
    return embedded ? "project-roles :: content" : "project-roles";
  }

  @PostMapping("/projects/{projectId}/settings/roles")
  String save(
      @PathVariable UUID projectId,
      @RequestParam Map<String, String> form,
      Principal principal,
      RedirectAttributes redirect) {
    access.requireProject(projectId, principal.getName());
    AppUser actor = actor(principal);
    try {
      List<SaveOrchestrationRoleProfile> commands = new ArrayList<>();
      for (AgentWorkflowRole role : AgentWorkflowRole.values()) {
        String key = role.name() + ".";
        commands.add(new SaveOrchestrationRoleProfile(
            role, null, uuid(form.get(key + "provider")), form.get(key + "model"),
            integer(form.get(key + "maxModelTurns")), integer(form.get(key + "maxOutputTokens")),
            integer(form.get(key + "contextWindowTokens")),
            blank(form.get(key + "reasoningEffort")) ? null
                : ReasoningEffort.valueOf(form.get(key + "reasoningEffort")),
            blank(form.get(key + "textVerbosity")) ? null
                : TextVerbosity.valueOf(form.get(key + "textVerbosity")),
            integer(form.get(key + "maxAttempts")), form.get(key + "promptAddendum")));
      }
      profiles.save(projectId, actor.getId(), actor.getEmail(), commands);
      redirect.addFlashAttribute("rolesSaved", true);
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("rolesError", exception.getMessage());
    }
    return "redirect:/projects/" + projectId + "/settings/roles";
  }

  private static UUID selectedInstance(
      OrchestrationRoleProfileView view, List<ModelCredentialStatus> usable) {
    if (view.providerInstanceId() != null) return view.providerInstanceId();
    // Rows saved before provider instances only know the kind.
    return usable.stream().filter(value -> value.provider() == view.provider())
        .map(ModelCredentialStatus::id).findFirst().orElse(null);
  }

  private static boolean blank(String value) { return value == null || value.isBlank(); }

  private static UUID uuid(String value) {
    if (blank(value)) throw new IllegalArgumentException("Choose a provider for every role");
    try {
      return UUID.fromString(value.strip());
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Choose a provider for every role", exception);
    }
  }

  private static Integer integer(String value) {
    if (blank(value)) return null;
    try {
      return Integer.valueOf(value.strip());
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("Budgets must be whole numbers", exception);
    }
  }

  private static String label(AgentWorkflowRole role) {
    String name = role.name();
    return name.charAt(0) + name.substring(1).toLowerCase();
  }

  private static String description(AgentWorkflowRole role) {
    return switch (role) {
      case ORCHESTRATOR -> "Coordinates the workflow and writes the summary for the final gate.";
      case PLANNER -> "Investigates the task, asks clarifying questions and proposes the plan.";
      case IMPLEMENTER -> "Makes the approved changes and proposes the tests to run.";
      case REVIEWER -> "Reviews the result against the plan before the final gate.";
    };
  }

  private static String icon(AgentWorkflowRole role) {
    return switch (role) {
      case ORCHESTRATOR -> "Workflow";
      case PLANNER -> "ScrollText";
      case IMPLEMENTER -> "FileCode2";
      case REVIEWER -> "Eye";
    };
  }

  private AppUser actor(Principal principal) {
    return users.findByEmailIgnoreCase(principal.getName())
        .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
  }

  public record RoleRow(
      String key, String label, String description, String icon,
      OrchestrationRoleProfileView profile, UUID instanceId) {}
}
