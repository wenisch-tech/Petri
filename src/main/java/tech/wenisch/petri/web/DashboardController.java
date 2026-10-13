package tech.wenisch.petri.web;

import java.security.Principal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.wenisch.petri.dashboard.DashboardService;

/** Live data behind the dashboard; the page polls it. */
@RestController
public class DashboardController {
  private final DashboardService dashboard;

  public DashboardController(DashboardService dashboard) {
    this.dashboard = dashboard;
  }

  @GetMapping("/dashboard/overview")
  DashboardService.Overview overview(Principal principal) {
    return dashboard.overview(principal.getName());
  }
}
