package tech.wenisch.petri.web;

import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tech.wenisch.petri.agent.run.TestBuildSystem;
import tech.wenisch.petri.agent.run.TestSandboxProfileService;

@Controller
public class TestSandboxProfileController {
  private final TestSandboxProfileService profiles;

  public TestSandboxProfileController(TestSandboxProfileService profiles) {
    this.profiles = profiles;
  }

  @GetMapping("/sandbox-profiles")
  String profiles(Model model) {
    var all = profiles.all();
    model.addAttribute("profiles", all);
    var environments = new java.util.HashMap<UUID, String>();
    all.forEach(profile -> environments.put(profile.getId(), profiles.maskedEnvironment(profile)));
    model.addAttribute("environments", environments);
    model.addAttribute("buildSystems", TestBuildSystem.values());
    return "sandbox-profiles";
  }

  @PostMapping("/sandbox-profiles")
  String save(
      @RequestParam(required = false) UUID id,
      @RequestParam String name,
      @RequestParam String image,
      @RequestParam List<TestBuildSystem> buildSystems,
      @RequestParam String cpuLimit,
      @RequestParam String memoryLimit,
      @RequestParam int pidsLimit,
      @RequestParam int timeoutSeconds,
      @RequestParam int outputLimitBytes,
      @RequestParam(defaultValue = "false") boolean networkEnabled,
      @RequestParam(defaultValue = "false") boolean enabled,
      @RequestParam(required = false) String environment,
      Principal principal,
      RedirectAttributes redirect) {
    try {
      profiles.save(id, name, image, Set.copyOf(buildSystems), cpuLimit, memoryLimit,
          pidsLimit, timeoutSeconds, outputLimitBytes, networkEnabled, enabled, environment,
          principal.getName());
      redirect.addFlashAttribute("notice", "Sandbox profile saved");
    } catch (RuntimeException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/sandbox-profiles";
  }
}
