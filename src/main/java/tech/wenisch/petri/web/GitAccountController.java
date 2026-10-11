package tech.wenisch.petri.web;

import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;
import tech.wenisch.petri.git.provider.GitAccountConnectionService;
import tech.wenisch.petri.git.provider.GitProviderInstance;
import tech.wenisch.petri.git.provider.GitProviderInstanceService;
import tech.wenisch.petri.git.provider.GitProviderType;

@Controller
public class GitAccountController {
  private static final String OAUTH_SESSION = "petri.git.oauth";
  private static final SecureRandom RANDOM = new SecureRandom();

  private final GitAccountConnectionService connections;
  private final GitProviderInstanceService instances;

  public GitAccountController(
      GitAccountConnectionService connections, GitProviderInstanceService instances) {
    this.connections = connections;
    this.instances = instances;
  }

  @GetMapping("/git-accounts")
  String accounts(Principal principal, Model model) {
    model.addAttribute("connections", connections.list(principal.getName()));
    model.addAttribute("providerInstances", instances.enabledFor(principal.getName()));
    return "git-accounts";
  }

  @PostMapping("/git-accounts/pat")
  String connectPat(
      @RequestParam UUID instanceId,
      @RequestParam String token,
      Principal principal,
      RedirectAttributes redirect) {
    try {
      var connection = connections.connectPat(instanceId, token, principal.getName());
      redirect.addFlashAttribute("notice", "Connected " + connection.providerName()
          + " as " + connection.login());
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/git-accounts";
  }

  @PostMapping("/git-accounts/{id}/validate")
  String validate(@PathVariable UUID id, Principal principal, RedirectAttributes redirect) {
    try {
      connections.validate(id, principal.getName());
      redirect.addFlashAttribute("notice", "Connection validated");
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/git-accounts";
  }

  @PostMapping("/git-accounts/{id}/disconnect")
  String disconnect(@PathVariable UUID id, Principal principal, RedirectAttributes redirect) {
    connections.disconnect(id, principal.getName());
    redirect.addFlashAttribute("notice", "Account disconnected and token material removed");
    return "redirect:/git-accounts";
  }

  @PostMapping("/git-accounts/oauth/{instanceId}/start")
  String oauthStart(
      @PathVariable UUID instanceId,
      @RequestParam(defaultValue = "false") boolean forgejoRightsAcknowledged,
      Principal principal,
      HttpSession session,
      RedirectAttributes redirect) {
    GitProviderInstance instance = instances.requireVisible(instanceId, principal.getName());
    if (!instance.isEnabled() || !instance.isOauthConfigured()) {
      redirect.addFlashAttribute("error", "OAuth is not configured for this provider");
      return "redirect:/git-accounts";
    }
    if (instance.getProviderType() == GitProviderType.FORGEJO && !forgejoRightsAcknowledged) {
      redirect.addFlashAttribute("error",
          "Confirm that Forgejo OAuth grants all account permissions before continuing");
      return "redirect:/git-accounts";
    }
    String state = randomUrlSafe(32);
    String verifier = randomUrlSafe(64);
    String redirectUri = ServletUriComponentsBuilder.fromCurrentContextPath()
        .path("/git-accounts/oauth/callback").build().toUriString();
    session.setAttribute(OAUTH_SESSION, new OauthAttempt(
        state, verifier, principal.getName(), instanceId, redirectUri,
        Instant.now().plus(10, ChronoUnit.MINUTES)));
    URI target = UriComponentsBuilder.fromUriString(instance.getOauthAuthorizationUrl())
        .queryParam("client_id", instance.getOauthClientId())
        .queryParam("redirect_uri", redirectUri)
        .queryParam("response_type", "code")
        .queryParam("scope", instance.getOauthScopes() == null ? "" : instance.getOauthScopes())
        .queryParam("state", state)
        .queryParam("code_challenge", challenge(verifier))
        .queryParam("code_challenge_method", "S256")
        .build().encode().toUri();
    return "redirect:" + target;
  }

  @GetMapping("/git-accounts/oauth/callback")
  String oauthCallback(
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String error,
      Principal principal,
      HttpSession session,
      RedirectAttributes redirect) {
    Object stored = session.getAttribute(OAUTH_SESSION);
    session.removeAttribute(OAUTH_SESSION);
    if (!(stored instanceof OauthAttempt attempt)
        || attempt.expiresAt().isBefore(Instant.now())
        || !attempt.actorEmail().equalsIgnoreCase(principal.getName())
        || state == null || !MessageDigest.isEqual(
            attempt.state().getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8))) {
      redirect.addFlashAttribute("error", "OAuth state is invalid or expired");
      return "redirect:/git-accounts";
    }
    if (error != null || code == null || code.isBlank()) {
      redirect.addFlashAttribute("error", "Provider rejected OAuth authorization");
      return "redirect:/git-accounts";
    }
    try {
      var connection = connections.connectOauth(attempt.instanceId(), code,
          attempt.redirectUri(), attempt.verifier(), principal.getName());
      redirect.addFlashAttribute("notice", "Connected " + connection.providerName()
          + " as " + connection.login());
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/git-accounts";
  }

  private static String randomUrlSafe(int bytes) {
    byte[] value = new byte[bytes];
    RANDOM.nextBytes(value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private static String challenge(String verifier) {
    try {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(
          MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private record OauthAttempt(
      String state,
      String verifier,
      String actorEmail,
      UUID instanceId,
      String redirectUri,
      Instant expiresAt) implements Serializable {}
}
