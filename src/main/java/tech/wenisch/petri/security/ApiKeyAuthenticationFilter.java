package tech.wenisch.petri.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tech.wenisch.petri.apikey.ApiKeyPrincipal;
import tech.wenisch.petri.apikey.ApiKeyService;

public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {
  private final ApiKeyService apiKeys;

  public ApiKeyAuthenticationFilter(ApiKeyService apiKeys) {
    this.apiKeys = apiKeys;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith(request.getContextPath() + "/api/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (authorization == null || authorization.isBlank()) {
      filterChain.doFilter(request, response);
      return;
    }
    if (!authorization.startsWith("Bearer ")) {
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }
    String rawKey = authorization.substring("Bearer ".length()).trim();
    ApiKeyPrincipal principal = apiKeys.authenticate(rawKey).orElse(null);
    if (principal == null) {
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
        principal,
        null,
        AuthorityUtils.createAuthorityList("ROLE_API_CLIENT")));
    SecurityContextHolder.setContext(context);
    filterChain.doFilter(request, response);
  }
}
