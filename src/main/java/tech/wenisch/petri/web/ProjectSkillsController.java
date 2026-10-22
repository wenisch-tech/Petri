package tech.wenisch.petri.web;

import java.security.Principal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import tech.wenisch.petri.agent.run.TrivyScanService;
import tech.wenisch.petri.agent.skill.SkillService;
import tech.wenisch.petri.agent.skill.SkillSummary;
import tech.wenisch.petri.automation.AutomationKind;
import tech.wenisch.petri.project.ProjectAccessService;

/** The skills available to a project's agents, and a way to run them in the workspace. */
@Controller
public class ProjectSkillsController {
  public record SkillCard(
      String name, String description, boolean pullRequest, String requirement, boolean ready) {}

  /** Started with Analyze on a pipeline, which names the run; it has nothing to run on its own. */
  private static final String PIPELINE_SKILL = "pipeline-log-analysis";

  private final ProjectAccessService access;
  private final SkillService skills;
  private final TrivyScanService trivy;

  public ProjectSkillsController(
      ProjectAccessService access, SkillService skills, TrivyScanService trivy) {
    this.access = access;
    this.skills = skills;
    this.trivy = trivy;
  }

  @GetMapping("/projects/{projectId}/skills")
  String skills(
      @PathVariable UUID projectId,
      @RequestParam(defaultValue = "false") boolean embedded,
      Principal principal,
      Model model) {
    model.addAttribute("project", access.requireProject(projectId, principal.getName()));
    Set<String> pullRequestSkills = Arrays.stream(AutomationKind.values())
        .filter(kind -> !kind.branchBased()).map(AutomationKind::skill).collect(Collectors.toSet());
    Set<String> branchSkills = Arrays.stream(AutomationKind.values())
        .filter(AutomationKind::branchBased).map(AutomationKind::skill).collect(Collectors.toSet());
    branchSkills.add(AutomationKind.FIX_METHOD_SKILL);
    branchSkills.add("cve-fix");
    List<SkillCard> cards = skills.list().stream().map(skill -> card(skill, pullRequestSkills)).toList();
    List<SkillCard> branchCards = cards.stream().filter(card -> branchSkills.contains(card.name())).toList();
    model.addAttribute("branchSkills", branchCards);
    model.addAttribute("runnable", cards.stream()
        .filter(card -> !card.pullRequest() && !branchSkills.contains(card.name())
            && !PIPELINE_SKILL.equals(card.name())).toList());
    model.addAttribute("pullRequestSkills", cards.stream().filter(SkillCard::pullRequest).toList());
    return embedded ? "project-skills :: content" : "project-skills";
  }

  private SkillCard card(SkillSummary skill, Set<String> pullRequestSkills) {
    boolean pullRequest = pullRequestSkills.contains(skill.name());
    if ("vulnerability-scan".equals(skill.name()) || "cve-scan".equals(skill.name())) {
      boolean ready = trivy.available();
      return new SkillCard(skill.name(), skill.description(), false,
          ready ? "Uses Trivy, which is installed. The first scan downloads its vulnerability database."
              : "Needs Trivy, which is not installed in this Petri instance.", ready);
    }
    return new SkillCard(skill.name(), skill.description(), pullRequest, null, true);
  }
}
