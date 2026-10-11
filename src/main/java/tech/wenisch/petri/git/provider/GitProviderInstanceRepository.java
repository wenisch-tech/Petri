package tech.wenisch.petri.git.provider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GitProviderInstanceRepository extends JpaRepository<GitProviderInstance, UUID> {
  List<GitProviderInstance> findByOwnerIdIsNullOrderByDisplayNameAsc();
  List<GitProviderInstance> findByOwnerIdOrderByDisplayNameAsc(UUID ownerId);

  @Query("""
      select instance from GitProviderInstance instance
      where instance.enabled = true
        and (instance.ownerId is null or instance.ownerId = :ownerId)
      order by instance.displayName
      """)
  List<GitProviderInstance> findEnabledVisibleTo(@Param("ownerId") UUID ownerId);

  @Query("""
      select instance from GitProviderInstance instance
      where instance.id = :id
        and (instance.ownerId is null or instance.ownerId = :ownerId)
      """)
  Optional<GitProviderInstance> findVisibleById(
      @Param("id") UUID id, @Param("ownerId") UUID ownerId);

  Optional<GitProviderInstance> findByOwnerIdAndWebBaseUrlIgnoreCase(UUID ownerId, String webBaseUrl);
  Optional<GitProviderInstance> findByOwnerIdIsNullAndWebBaseUrlIgnoreCase(String webBaseUrl);
}
