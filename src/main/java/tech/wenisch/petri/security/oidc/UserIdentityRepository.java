package tech.wenisch.petri.security.oidc;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, UUID> {
  Optional<UserIdentity> findByProviderRegistrationIdAndSubject(
      String providerRegistrationId, String subject);
}
