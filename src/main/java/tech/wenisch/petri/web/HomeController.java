package tech.wenisch.petri.web;

import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {
  @GetMapping("/")
  String index(Principal principal, Model model) {
    model.addAttribute("username", principal.getName());
    return "index";
  }

  @GetMapping("/login")
  String login() {
    return "login";
  }
}

