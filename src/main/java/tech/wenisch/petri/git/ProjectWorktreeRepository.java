package tech.wenisch.petri.git;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProjectWorktreeRepository extends JpaRepository<ProjectWorktree, UUID> {
  List<ProjectWorktree> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

  List<ProjectWorktree> findByProjectIdAndStatusOrderByCreatedAtDesc(
      UUID projectId, ProjectWorktreeStatus status);

  boolean existsByProjectIdAndBranchNameAndStatus(
      UUID projectId, String branchName, ProjectWorktreeStatus status);
}
