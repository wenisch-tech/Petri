package tech.wenisch.petri.web;

import java.security.Principal;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tech.wenisch.petri.packages.PackageEcosystem;
import tech.wenisch.petri.packages.PackageRemoteMode;
import tech.wenisch.petri.packages.PackageRemoteService;
import tech.wenisch.petri.project.ProjectAccessService;

/** The package remotes of one project: they replace the instance's remotes per package manager. */
@Controller
public class ProjectPackageSourcesController {
  private final ProjectAccessService access;
  private final PackageRemoteService remotes;

  public ProjectPackageSourcesController(ProjectAccessService access, PackageRemoteService remotes) {
    this.access = access;
    this.remotes = remotes;
  }

  @GetMapping("/projects/{projectId}/settings/package-sources")
  String page(
      @PathVariable UUID projectId,
      @RequestParam(defaultValue = "false") boolean embedded,
      Principal principal,
      Model model) {
    model.addAttribute("project", access.requireProject(projectId, principal.getName()));
    model.addAttribute("remotes", remotes.projectRemotes(projectId));
    model.addAttribute("inherited", remotes.instanceRemotes());
    model.addAttribute("ecosystems", PackageEcosystem.values());
    model.addAttribute("modes", PackageRemoteMode.values());
    return embedded ? "project-package-sources :: content" : "project-package-sources";
  }

  @PostMapping("/projects/{projectId}/settings/package-sources/remotes")
  String save(
      @PathVariable UUID projectId,
      @RequestParam(required = false) UUID id,
      @RequestParam PackageEcosystem ecosystem,
      @RequestParam String name,
      @RequestParam String url,
      @RequestParam(defaultValue = "REPLACE") PackageRemoteMode mode,
      @RequestParam(required = false) String username,
      @RequestParam(required = false) String password,
      @RequestParam(defaultValue = "false") boolean clearPassword,
      @RequestParam(required = false) String mirrorOf,
      @RequestParam(defaultValue = "false") boolean insecure,
      @RequestParam(defaultValue = "false") boolean enabled,
      @RequestParam(defaultValue = "0") int position,
      Principal principal,
      RedirectAttributes redirect) {
    access.requireProject(projectId, principal.getName());
    try {
      remotes.save(projectId, id, new PackageRemoteService.Save(ecosystem, name, url, mode, username,
          password, clearPassword, mirrorOf, insecure, enabled, position), principal.getName());
      redirect.addFlashAttribute("notice", id == null ? "Remote added" : "Remote saved");
    } catch (IllegalArgumentException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return back(projectId);
  }

  @PostMapping("/projects/{projectId}/settings/package-sources/remotes/{id}/test")
  String test(
      @PathVariable UUID projectId, @PathVariable UUID id, Principal principal,
      RedirectAttributes redirect) {
    access.requireProject(projectId, principal.getName());
    try {
      var result = remotes.test(projectId, id);
      redirect.addFlashAttribute(result.ok() ? "notice" : "error", result.message());
    } catch (IllegalArgumentException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return back(projectId);
  }

  @PostMapping("/projects/{projectId}/settings/package-sources/remotes/{id}/delete")
  String delete(
      @PathVariable UUID projectId, @PathVariable UUID id, Principal principal,
      RedirectAttributes redirect) {
    access.requireProject(projectId, principal.getName());
    try {
      remotes.delete(projectId, id, principal.getName());
      redirect.addFlashAttribute("notice", "Remote removed");
    } catch (IllegalArgumentException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return back(projectId);
  }

  private static String back(UUID projectId) {
    return "redirect:/projects/" + projectId + "/settings/package-sources";
  }
}
