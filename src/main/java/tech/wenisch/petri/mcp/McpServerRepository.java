package tech.wenisch.petri.mcp;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface McpServerRepository extends JpaRepository<McpServer, UUID> {
  List<McpServer> findByOwnerIdOrderByNameAsc(UUID ownerId);

  Optional<McpServer> findByIdAndOwnerId(UUID id, UUID ownerId);

  boolean existsByOwnerIdAndNameIgnoreCase(UUID ownerId, String name);

  Optional<McpServer> findByOwnerIdAndNameIgnoreCase(UUID ownerId, String name);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<McpServer> findLockedById(UUID id);
}
