package tech.wenisch.petri.web;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Common navigation state used by both owner and instance-administration pages. */
@ControllerAdvice
public class GlobalNavigationAdvice {
  private final String version;
  private final String repositoryUrl;

  public GlobalNavigationAdvice(
      @org.springframework.beans.factory.annotation.Value("${petri.version:dev}") String version,
      @org.springframework.beans.factory.annotation.Value(
          "${petri.repository-url:https://github.com/wenisch-tech/Petri}") String repositoryUrl) {
    this.version = version;
    this.repositoryUrl = repositoryUrl;
  }

  @ModelAttribute("petriVersion")
  public String petriVersion() {
    return version;
  }

  @ModelAttribute("petriRepository")
  public String petriRepository() {
    return repositoryUrl;
  }

  @ModelAttribute("isInstanceAdmin")
  public boolean isInstanceAdmin(Authentication authentication) {
    return authentication != null && authentication.getAuthorities().stream()
        .anyMatch(authority -> "ROLE_INSTANCE_ADMIN".equals(authority.getAuthority()));
  }

  @ModelAttribute("currentUserEmail")
  public String currentUserEmail(Authentication authentication) {
    return authentication == null ? null : authentication.getName();
  }
}
