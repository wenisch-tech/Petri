package tech.wenisch.petri.security;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import tech.wenisch.petri.config.PetriProperties;

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
          .password(user.getPasswordHash())
          .roles(user.getRole())
          .disabled(!user.isEnabled())
          .build();
    };
  }

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http
        .authorizeHttpRequests(authorize -> authorize
            .requestMatchers("/css/**", "/js/**", "/actuator/health", "/actuator/health/**")
            .permitAll()
            .anyRequest().authenticated())
        .formLogin(form -> form.loginPage("/login").defaultSuccessUrl("/", true).permitAll())
        .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
        .build();
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

