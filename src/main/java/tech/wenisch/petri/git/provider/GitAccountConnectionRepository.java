package tech.wenisch.petri.git.provider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

public interface GitAccountConnectionRepository
    extends JpaRepository<GitAccountConnection, UUID> {
  List<GitAccountConnection> findByOwnerIdOrderByCreatedAtDesc(UUID ownerId);
  Optional<GitAccountConnection> findByIdAndOwnerId(UUID id, UUID ownerId);
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<GitAccountConnection> findLockedByIdAndOwnerId(UUID id, UUID ownerId);
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<GitAccountConnection> findLockedById(UUID id);
  Optional<GitAccountConnection> findByOwnerIdAndProviderInstanceIdAndExternalAccountId(
      UUID ownerId, UUID providerInstanceId, String externalAccountId);
}
