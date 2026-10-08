package tech.wenisch.petri.web;

import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import tech.wenisch.petri.project.ProjectRepository;

@Controller
public class HomeController {
  private final ProjectRepository projects;

  public HomeController(ProjectRepository projects) {
    this.projects = projects;
  }

  @GetMapping("/")
  String index(Principal principal, Model model) {
    model.addAttribute("username", principal.getName());
    model.addAttribute("projectCount", projects.count());
    model.addAttribute("recentProjects", projects.findTop5ByOrderByCreatedAtDesc());
    return "index";
  }

  @GetMapping("/login")
  String login() {
    return "login";
  }
}
