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

@Controller
public class HomeController {
  private final ProjectRepository projects;
  private final ObjectProvider<ClientRegistrationRepository> clientRegistrations;

  public HomeController(
      ProjectRepository projects, ObjectProvider<ClientRegistrationRepository> clientRegistrations) {
    this.projects = projects;
    this.clientRegistrations = clientRegistrations;
  }

  @GetMapping("/")
  String index(Principal principal, Model model) {
    model.addAttribute("username", principal.getName());
    model.addAttribute("projectCount", projects.count());
    model.addAttribute("recentProjects", projects.findTop5ByOrderByCreatedAtDesc());
    return "index";
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
