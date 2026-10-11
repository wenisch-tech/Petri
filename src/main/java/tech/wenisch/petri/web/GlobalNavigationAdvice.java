package tech.wenisch.petri.web;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Common navigation state used by both owner and instance-administration pages. */
@ControllerAdvice
public class GlobalNavigationAdvice {
  @ModelAttribute("isInstanceAdmin")
  public boolean isInstanceAdmin(Authentication authentication) {
    return authentication != null && authentication.getAuthorities().stream()
        .anyMatch(authority -> "ROLE_INSTANCE_ADMIN".equals(authority.getAuthority()));
  }
}
