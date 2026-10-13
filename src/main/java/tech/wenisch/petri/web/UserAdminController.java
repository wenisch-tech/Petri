package tech.wenisch.petri.web;

import java.security.Principal;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tech.wenisch.petri.security.UserAdminService;

/** User administration: local accounts, OIDC-linked accounts, roles and passwords. */
@Controller
public class UserAdminController {
  private final UserAdminService users;

  public UserAdminController(UserAdminService users) {
    this.users = users;
  }

  @GetMapping("/admin/users")
  String list(Principal principal, Model model) {
    model.addAttribute("users", users.list(principal.getName()));
    return "admin-users";
  }

  @PostMapping("/admin/users")
  String create(
      @RequestParam String email, @RequestParam String password,
      @RequestParam(defaultValue = "USER") String role, Principal principal,
      RedirectAttributes redirect) {
    return run(redirect, "User created", () -> users.create(email, password, role, principal.getName()));
  }

  @PostMapping("/admin/users/{id}/role")
  String role(
      @PathVariable UUID id, @RequestParam String role, Principal principal,
      RedirectAttributes redirect) {
    return run(redirect, "Role updated. It applies the next time the user signs in.",
        () -> users.changeRole(id, role, principal.getName()));
  }

  @PostMapping("/admin/users/{id}/enabled")
  String enabled(
      @PathVariable UUID id, @RequestParam boolean enabled, Principal principal,
      RedirectAttributes redirect) {
    return run(redirect, enabled ? "Account enabled" : "Account disabled",
        () -> users.setEnabled(id, enabled, principal.getName()));
  }

  @PostMapping("/admin/users/{id}/password")
  String password(
      @PathVariable UUID id, @RequestParam String password, Principal principal,
      RedirectAttributes redirect) {
    return run(redirect, "Password changed", () -> users.setPassword(id, password, principal.getName()));
  }

  @PostMapping("/admin/users/{id}/delete")
  String delete(@PathVariable UUID id, Principal principal, RedirectAttributes redirect) {
    try {
      users.delete(id, principal.getName());
      redirect.addFlashAttribute("notice", "User deleted");
    } catch (DataIntegrityViolationException exception) {
      redirect.addFlashAttribute("error",
          "This user owns projects or history and cannot be deleted. Disable the account instead.");
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/admin/users";
  }

  private static String run(RedirectAttributes redirect, String success, Runnable action) {
    try {
      action.run();
      redirect.addFlashAttribute("notice", success);
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/admin/users";
  }
}
