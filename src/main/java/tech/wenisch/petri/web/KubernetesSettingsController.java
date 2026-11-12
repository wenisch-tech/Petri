package tech.wenisch.petri.web;

import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tech.wenisch.petri.kubernetes.ClusterGateway;
import tech.wenisch.petri.kubernetes.ClusterConfig;
import tech.wenisch.petri.kubernetes.KubernetesSettingsService;

/** Administration of the default cluster and pod settings of Kubernetes workspaces. */
@Controller
public class KubernetesSettingsController {
  private final KubernetesSettingsService settings;
  private final ClusterGateway gateway;

  public KubernetesSettingsController(KubernetesSettingsService settings, ClusterGateway gateway) {
    this.settings = settings;
    this.gateway = gateway;
  }

  @GetMapping("/admin/kubernetes")
  String page(Model model) {
    model.addAttribute("cfg", settings.instance());
    return "kubernetes-settings";
  }

  @PostMapping("/admin/kubernetes")
  String save(
      @ModelAttribute KubernetesForm form, Principal principal, RedirectAttributes redirect) {
    try {
      settings.saveInstance(form.toSave(), principal.getName());
      redirect.addFlashAttribute("notice", "Kubernetes workspace defaults saved");
    } catch (IllegalArgumentException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/admin/kubernetes";
  }

  @PostMapping("/admin/kubernetes/test")
  String test(RedirectAttributes redirect) {
    ClusterConfig cluster = settings.clusterFor(null);
    if (cluster == null) {
      redirect.addFlashAttribute("error", "Save a cluster first");
    } else {
      var result = gateway.check(cluster, settings.instance().getNamespace());
      redirect.addFlashAttribute(result.ok() ? "notice" : "error", result.message());
    }
    return "redirect:/admin/kubernetes";
  }
}
