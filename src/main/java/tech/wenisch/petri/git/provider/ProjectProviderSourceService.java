package tech.wenisch.petri.git.provider;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.project.ProjectAccessService;

@Service
public class ProjectProviderSourceService {
  private final ProjectProviderSourceRepository sources;
  private final ProjectAccessService access;
  private final AuditLogService audit;

  public ProjectProviderSourceService(
      ProjectProviderSourceRepository sources, ProjectAccessService access, AuditLogService audit) {
    this.sources = sources;
    this.access = access;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public ProjectProviderSource find(UUID projectId) {
    return sources.findByProjectId(projectId).orElse(null);
  }

  @Transactional
  public void useSimpleGitCredentials(UUID projectId, String actorEmail) {
    var project = access.requireProject(projectId, actorEmail);
    ProjectProviderSource source = sources.findByProjectId(projectId)
        .orElseThrow(() -> new IllegalArgumentException("Project is already a Simple Git project"));
    source.useSimpleGitCredentials();
    audit.record(actorEmail, "PROJECT_CREDENTIAL_MODE_CHANGED", "PROJECT", projectId,
        "Changed " + project.getName() + " to project-specific Simple Git credentials");
  }
}
