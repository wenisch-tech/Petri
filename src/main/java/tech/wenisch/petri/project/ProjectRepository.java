package tech.wenisch.petri.project;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, UUID> {
  boolean existsBySlug(String slug);

  Optional<Project> findBySlug(String slug);

  List<Project> findAllByOrderByCreatedAtDesc();

  List<Project> findTop5ByOrderByCreatedAtDesc();
}
