package tech.wenisch.petri.git;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProjectGitCredentialRepository extends JpaRepository<ProjectGitCredential, UUID> {
  Optional<ProjectGitCredential> findByProjectId(UUID projectId);
}
