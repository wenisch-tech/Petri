package tech.wenisch.petri.apikey;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {
  boolean existsByKeyIdentifier(String keyIdentifier);

  Optional<ApiKey> findByKeyIdentifier(String keyIdentifier);

  List<ApiKey> findAllByOrderByCreatedAtDesc();
}
