package tech.wenisch.petri.project;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tech.wenisch.petri.git.ProjectWorktreeService;
import tech.wenisch.petri.git.ProjectWorktreeView;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;

@Service
public class ProjectAccessService {
  private final ProjectRepository projects;
  private final AppUserRepository users;
  private final ProjectWorktreeService worktrees;

  public ProjectAccessService(
      ProjectRepository projects, AppUserRepository users, ProjectWorktreeService worktrees) {
    this.projects = projects;
    this.users = users;
    this.worktrees = worktrees;
  }

  @Transactional(readOnly = true)
  public Project requireProject(UUID projectId, String actorEmail) {
    AppUser owner = owner(actorEmail);
    return requireProject(projectId, owner.getId());
  }

  @Transactional(readOnly = true)
  public Project requireProject(UUID projectId, UUID ownerId) {
    return projects.findByIdAndCreatedBy(projectId, ownerId).orElseThrow(ProjectAccessService::notFound);
  }

  @Transactional(readOnly = true)
  public ProjectWorktreeView requireWorktree(UUID worktreeId, String actorEmail) {
    return requireWorktree(worktreeId, owner(actorEmail).getId());
  }

  @Transactional(readOnly = true)
  public ProjectWorktreeView requireWorktree(UUID worktreeId, UUID ownerId) {
    ProjectWorktreeView worktree;
    try {
      worktree = worktrees.get(worktreeId);
    } catch (IllegalArgumentException exception) {
      throw notFound();
    }
    requireProject(worktree.projectId(), ownerId);
    return worktree;
  }

  @Transactional(readOnly = true)
  public List<Project> projects(String actorEmail) {
    return projects.findByCreatedByOrderByCreatedAtDesc(owner(actorEmail).getId());
  }

  @Transactional(readOnly = true)
  public AppUser owner(String actorEmail) {
    return users.findByEmailIgnoreCase(actorEmail).orElseThrow(ProjectAccessService::notFound);
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");
  }
}
