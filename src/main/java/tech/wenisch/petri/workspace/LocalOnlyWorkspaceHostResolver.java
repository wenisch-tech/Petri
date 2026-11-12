package tech.wenisch.petri.workspace;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** Default resolver: every project lives on the local host. */
@Component
public class LocalOnlyWorkspaceHostResolver implements WorkspaceHostResolver {
  private final LocalWorkspaceHost local;

  public LocalOnlyWorkspaceHostResolver(LocalWorkspaceHost local) {
    this.local = local;
  }

  @Override
  public WorkspaceHost forProject(UUID projectId) {
    return local;
  }

  @Override
  public WorkspaceHost forWorktree(UUID worktreeId) {
    return local;
  }
}
