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

/** Administration of the package remotes (mirrors, registries) and the CA bundle for them. */
@Controller
public class PackageSourcesController {
  private final PackageRemoteService remotes;

  public PackageSourcesController(PackageRemoteService remotes) {
    this.remotes = remotes;
  }

  @GetMapping("/admin/package-sources")
  String page(Model model) {
    model.addAttribute("remotes", remotes.instanceRemotes());
    model.addAttribute("ecosystems", PackageEcosystem.values());
    model.addAttribute("modes", PackageRemoteMode.values());
    var tls = remotes.tlsSettings();
    model.addAttribute("caPem", tls.getCaPem());
    model.addAttribute("ignoreTlsErrors", tls.isIgnoreTlsErrors());
    return "package-sources";
  }

  @PostMapping("/admin/package-sources/remotes")
  String save(
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
    try {
      remotes.save(null, id, new PackageRemoteService.Save(ecosystem, name, url, mode, username,
          password, clearPassword, mirrorOf, insecure, enabled, position), principal.getName());
      redirect.addFlashAttribute("notice", id == null ? "Remote added" : "Remote saved");
    } catch (IllegalArgumentException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/admin/package-sources";
  }

  @PostMapping("/admin/package-sources/remotes/{id}/test")
  String test(@PathVariable UUID id, RedirectAttributes redirect) {
    try {
      var result = remotes.test(null, id);
      redirect.addFlashAttribute(result.ok() ? "notice" : "error", result.message());
    } catch (IllegalArgumentException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/admin/package-sources";
  }

  @PostMapping("/admin/package-sources/remotes/{id}/delete")
  String delete(@PathVariable UUID id, Principal principal, RedirectAttributes redirect) {
    try {
      remotes.delete(null, id, principal.getName());
      redirect.addFlashAttribute("notice", "Remote removed");
    } catch (IllegalArgumentException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/admin/package-sources";
  }

  @PostMapping("/admin/package-sources/tls")
  String saveTls(
      @RequestParam(required = false) String caPem,
      @RequestParam(defaultValue = "false") boolean ignoreTlsErrors,
      Principal principal,
      RedirectAttributes redirect) {
    try {
      remotes.saveTls(caPem, ignoreTlsErrors, principal.getName());
      redirect.addFlashAttribute("notice", "TLS settings saved");
    } catch (IllegalArgumentException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/admin/package-sources";
  }
}
