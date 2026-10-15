package tech.wenisch.petri.security;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tech.wenisch.petri.apikey.ApiKeyService;
import tech.wenisch.petri.config.PetriProperties;
import tech.wenisch.petri.security.oidc.OidcHttpSupport;
import tech.wenisch.petri.security.oidc.PetriOidcUserService;

@Configuration
public class SecurityConfiguration {
  private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

  @Bean
  PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  @Bean
  UserDetailsService userDetailsService(AppUserRepository users) {
    return username -> {
      AppUser user = users.findByEmailIgnoreCase(username)
          .orElseThrow(() -> new UsernameNotFoundException(username));
      return User.withUsername(user.getEmail())
          .password(user.hasLocalPassword() ? user.getPasswordHash() : "{noop}unavailable")
          .roles(user.getRole())
          .disabled(!user.isEnabled())
          .build();
    };
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      ApiKeyService apiKeys,
      ObjectProvider<ClientRegistrationRepository> clientRegistrations,
      PetriOidcUserService oidcUsers,
      OidcHttpSupport oidcHttp) throws Exception {
    RequestMatcher apiRequests = request -> request.getRequestURI()
        .startsWith(request.getContextPath() + "/api/");
    if (clientRegistrations.getIfAvailable() != null) {
      http.oauth2Login(login -> {
        login.loginPage("/login")
            // Technical failures (state mismatch, TLS errors talking to the provider) would
            // otherwise redirect silently and leave nothing in the logs.
            .failureHandler((request, response, exception) -> {
              log.warn("OIDC login failed: {}", exception.getMessage(), exception);
              response.sendRedirect(request.getContextPath() + "/login?error");
            })
            .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUsers));
        var tokenClient = oidcHttp.tokenResponseClient();
        if (tokenClient != null) {
          login.tokenEndpoint(token -> token.accessTokenResponseClient(tokenClient));
        }
      });
    }
    return http
        .csrf(csrf -> csrf.ignoringRequestMatchers(apiRequests))
        .authorizeHttpRequests(authorize -> authorize
            .requestMatchers("/css/**", "/js/**", "/img/**", "/fonts/**", "/favicon.ico", "/actuator/health",
                "/actuator/health/**")
            .permitAll()
            .requestMatchers(apiRequests).hasRole("API_CLIENT")
            .requestMatchers("/admin", "/audit", "/audit/**", "/admin/users", "/admin/users/**",
                "/admin/vulnerability-scanning", "/admin/vulnerability-scanning/**",
                "/admin/actions", "/admin/actions/**", "/admin/package-sources",
                "/admin/package-sources/**")
            .hasRole("INSTANCE_ADMIN")
            .requestMatchers("/model-settings", "/model-settings/**").hasRole("INSTANCE_ADMIN")
            .requestMatchers("/sandbox-profiles", "/sandbox-profiles/**").hasRole("INSTANCE_ADMIN")
            .requestMatchers("/operations/model-credentials", "/operations/model-credentials/**")
            .hasRole("INSTANCE_ADMIN")
            .anyRequest().authenticated())
        .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
            new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
            apiRequests).defaultAuthenticationEntryPointFor(
                new LoginUrlAuthenticationEntryPoint("/login"),
                new NegatedRequestMatcher(apiRequests)))
        // The page that was asked for before signing in is remembered and opened after the login;
        // the dashboard is only the fallback when there was none.
        .requestCache(cache -> cache.requestCache(loginRedirectCache()))
        .formLogin(form -> form.loginPage("/login").defaultSuccessUrl("/").permitAll())
        .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
        .addFilterBefore(new ApiKeyAuthenticationFilter(apiKeys), UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  /**
   * Remembers only requests a person opened in the browser as a page. Background requests of the
   * workspace (fragments loaded with {@code embedded=true}, JSON and event streams) must not become
   * the landing page after a login: they would show a bare fragment.
   */
  static HttpSessionRequestCache loginRedirectCache() {
    HttpSessionRequestCache cache = new HttpSessionRequestCache();
    cache.setMatchingRequestParameterName(null);
    cache.setRequestMatcher(request -> {
      if (!"GET".equals(request.getMethod())) return false;
      String accept = request.getHeader("Accept");
      if (accept == null || !accept.contains("text/html")) return false;
      if ("XMLHttpRequest".equals(request.getHeader("X-Requested-With"))) return false;
      if ("true".equals(request.getParameter("embedded"))) return false;
      String path = request.getRequestURI().substring(request.getContextPath().length());
      return !path.equals("/login") && !path.equals("/favicon.ico")
          && !path.startsWith("/api/") && !path.startsWith("/workspace/api/")
          && !path.startsWith("/chat/") && !path.startsWith("/js/") && !path.startsWith("/css/");
    });
    return cache;
  }

  @Bean
  ApplicationRunner initializeAdmin(
      AppUserRepository users, PasswordEncoder encoder, PetriProperties properties) {
    return arguments -> {
      if (users.count() > 0) return;
      PetriProperties.Security security = properties.security();
      users.save(new AppUser(
          UUID.randomUUID(),
          security.initialAdminEmail(),
          encoder.encode(security.initialAdminPassword()),
          "INSTANCE_ADMIN"));
      if ("admin".equals(security.initialAdminPassword())) {
        log.warn("Petri uses the default initial admin password; set PETRI_ADMIN_PASSWORD before remote use");
      }
      log.info("Created initial Petri administrator {}", security.initialAdminEmail());
    };
  }
}
