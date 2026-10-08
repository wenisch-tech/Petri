package tech.wenisch.petri.web;

import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.wenisch.petri.agent.model.ModelCredentialService;
import tech.wenisch.petri.agent.model.ModelCredentialStatus;
import tech.wenisch.petri.agent.model.ModelProvider;
import tech.wenisch.petri.agent.model.SaveModelCredentialCommand;

@RestController
@RequestMapping("/operations/model-credentials/{provider}")
public class ModelCredentialController {
  private final ModelCredentialService credentials;

  public ModelCredentialController(ModelCredentialService credentials) {
    this.credentials = credentials;
  }

  @GetMapping
  public ModelCredentialStatus status(@PathVariable ModelProvider provider) {
    return credentials.status(provider);
  }

  @PutMapping
  public ModelCredentialStatus save(
      @PathVariable ModelProvider provider,
      @RequestBody SaveModelCredentialCommand command,
      Principal principal,
      HttpServletResponse response) {
    noStore(response);
    return credentials.save(provider, command, principal.getName());
  }

  @DeleteMapping
  public ModelCredentialStatus remove(
      @PathVariable ModelProvider provider,
      Principal principal,
      HttpServletResponse response) {
    noStore(response);
    credentials.remove(provider, principal.getName());
    return credentials.status(provider);
  }

  private static void noStore(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store, max-age=0");
    response.setHeader("Pragma", "no-cache");
  }
}
