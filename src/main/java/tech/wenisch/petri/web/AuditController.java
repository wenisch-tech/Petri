package tech.wenisch.petri.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import tech.wenisch.petri.audit.AuditEventRepository;

@Controller
public class AuditController {
  private final AuditEventRepository events;

  public AuditController(AuditEventRepository events) {
    this.events = events;
  }

  @GetMapping("/audit")
  String audit(Model model) {
    model.addAttribute("events", events.findTop100ByOrderByOccurredAtDesc());
    return "audit";
  }
}
