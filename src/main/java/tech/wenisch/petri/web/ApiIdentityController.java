package tech.wenisch.petri.web;

import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.wenisch.petri.apikey.ApiKeyPrincipal;

@RestController
@RequestMapping("/api/v1")
public class ApiIdentityController {
  @GetMapping("/identity")
  ApiKeyIdentityResponse identity(Authentication authentication) {
    ApiKeyPrincipal principal = (ApiKeyPrincipal) authentication.getPrincipal();
    return new ApiKeyIdentityResponse(
        principal.id(), principal.name(), principal.createdBy(), principal.expiresAt());
  }

  record ApiKeyIdentityResponse(UUID keyId, String keyName, UUID createdBy, Instant expiresAt) {}
}
