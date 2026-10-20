package tech.wenisch.petri.web;

import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tech.wenisch.petri.agent.skill.SkillService;
import tech.wenisch.petri.automation.AutomationActionService;
import tech.wenisch.petri.automation.AutomationActionService.CustomAction;
import tech.wenisch.petri.automation.AutomationPollSettingsService;

/** Administration of the actions that automations can run: the built-in ones and custom ones. */
@Controller
public class AutomationActionsController {
  private final AutomationActionService actions;
  private final SkillService skills;
  private final AutomationPollSettingsService pollSettings;

  public AutomationActionsController(
      AutomationActionService actions, SkillService skills, AutomationPollSettingsService pollSettings) {
    this.pollSettings = pollSettings;
    this.actions = actions;
    this.skills = skills;
  }

  @GetMapping("/admin/actions")
  String page(Model model) {
    model.addAttribute("actions", actions.list());
    model.addAttribute("poll", pollSettings.view());
    model.addAttribute("skills", skills.list());
    model.addAttribute("installable", skills.installable());
    model.addAttribute("installed", skills.listInstalled());
    return "automation-actions";
  }

  @PostMapping("/admin/actions")
  String save(
      @RequestParam(required = false) String id,
      @RequestParam String title,
      @RequestParam(required = false) String description,
      @RequestParam String skill,
      @RequestParam(defaultValue = "PULL_REQUEST") String context,
      @RequestParam(defaultValue = "false") boolean vulnerability,
      @RequestParam(defaultValue = "false") boolean comments,
      @RequestParam(defaultValue = "false") boolean improves,
      @RequestParam(defaultValue = "false") boolean merges,
      Principal principal,
      RedirectAttributes redirect) {
    try {
      actions.saveCustom(id, new CustomAction(title, description, skill, "BRANCH".equals(context),
          vulnerability, comments, improves, merges), principal.getName());
      redirect.addFlashAttribute("notice", id == null || id.isBlank() ? "Action added" : "Action saved");
    } catch (IllegalArgumentException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/admin/actions";
  }

  @PostMapping("/admin/actions/polling")
  String savePolling(@RequestParam int intervalMinutes, Principal principal, RedirectAttributes redirect) {
    try {
      pollSettings.save(intervalMinutes, principal.getName());
      redirect.addFlashAttribute("notice", "Pull request check interval saved");
    } catch (IllegalArgumentException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/admin/actions";
  }

  @PostMapping("/admin/actions/delete")
  String delete(@RequestParam String id, Principal principal, RedirectAttributes redirect) {
    try {
      actions.deleteCustom(id, principal.getName());
      redirect.addFlashAttribute("notice", "Action removed");
    } catch (IllegalArgumentException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/admin/actions";
  }

  @PostMapping("/admin/actions/skills")
  String install(
      @RequestParam(required = false) MultipartFile file,
      @RequestParam(required = false) String content,
      RedirectAttributes redirect) {
    try {
      String text = file != null && !file.isEmpty()
          ? new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8) : content;
      var installed = skills.install(text);
      redirect.addFlashAttribute("notice", "Skill " + installed.name() + " installed");
    } catch (IllegalArgumentException | IllegalStateException | java.io.IOException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/admin/actions";
  }

  @PostMapping("/admin/actions/skills/remove")
  String removeSkill(@RequestParam String name, RedirectAttributes redirect) {
    try {
      skills.remove(name);
      redirect.addFlashAttribute("notice", "Skill removed");
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/admin/actions";
  }

  @GetMapping("/admin/actions/skills/{name}/export")
  ResponseEntity<String> export(@PathVariable String name) {
    String content = skills.load(name).content();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
        .header("Content-Disposition", "attachment; filename=\"SKILL.md\"")
        .body(content);
  }
}
