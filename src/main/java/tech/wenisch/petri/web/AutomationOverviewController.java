package tech.wenisch.petri.web;

import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import tech.wenisch.petri.dashboard.AutomationOverviewService;

/** The latest automation runs of every project in one place. */
@Controller
public class AutomationOverviewController {
  private final AutomationOverviewService overview;

  public AutomationOverviewController(AutomationOverviewService overview) {
    this.overview = overview;
  }

  @GetMapping("/automations")
  String automations(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) java.util.UUID project,
      @RequestParam(defaultValue = "0") int page,
      Principal principal,
      Model model) {
    model.addAttribute("overview", overview.overview(principal.getName(), status, q, project, page));
    return "automations-overview";
  }
}
