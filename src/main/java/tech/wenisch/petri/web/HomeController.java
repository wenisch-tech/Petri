package tech.wenisch.petri.web;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import tech.wenisch.petri.project.ProjectRepository;
import tech.wenisch.petri.security.AppUserRepository;

@Controller
public class HomeController {
  private final ProjectRepository projects;
  private final ObjectProvider<ClientRegistrationRepository> clientRegistrations;
  private final AppUserRepository users;

  public HomeController(
      ProjectRepository projects,
      ObjectProvider<ClientRegistrationRepository> clientRegistrations,
      AppUserRepository users) {
    this.projects = projects;
    this.clientRegistrations = clientRegistrations;
    this.users = users;
  }

  @GetMapping("/")
  String index(Principal principal, Model model) {
    model.addAttribute("username", principal.getName());
    var owner = users.findByEmailIgnoreCase(principal.getName())
        .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
    model.addAttribute("projectCount", projects.countByCreatedBy(owner.getId()));
    model.addAttribute("recentProjects", projects.findTop5ByCreatedByOrderByCreatedAtDesc(owner.getId()));
    return "index";
  }

  @GetMapping("/settings")
  String settings() {
    return "redirect:/git-accounts";
  }

  @GetMapping("/admin")
  String admin() {
    return "redirect:/model-settings";
  }

  @GetMapping("/login")
  String login(Model model) {
    model.addAttribute("oidcProviders", oidcProviders());
    return "login";
  }

  private List<OidcProvider> oidcProviders() {
    ClientRegistrationRepository repository = clientRegistrations.getIfAvailable();
    if (!(repository instanceof Iterable<?> registrations)) return List.of();
    List<OidcProvider> providers = new ArrayList<>();
    for (Object candidate : registrations) {
      if (!(candidate instanceof ClientRegistration registration)) continue;
      if (!registration.getScopes().contains("openid")) continue;
      providers.add(new OidcProvider(
          registration.getRegistrationId(), registration.getClientName()));
    }
    return providers;
  }

  record OidcProvider(String registrationId, String clientName) {}
}
