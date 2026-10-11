package tech.wenisch.petri.git;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.git.provider.GitAccountConnectionService;
import tech.wenisch.petri.git.provider.GitConnectionCredential;
import tech.wenisch.petri.git.provider.ProjectCredentialMode;
import tech.wenisch.petri.git.provider.ProjectProviderSource;
import tech.wenisch.petri.git.provider.ProjectProviderSourceRepository;

@Service
public class ProjectGitCredentialResolver {
  private final ProjectGitCredentialService simpleCredentials;
  private final ProjectProviderSourceRepository sources;
  private final GitAccountConnectionService connections;

  public ProjectGitCredentialResolver(
      ProjectGitCredentialService simpleCredentials,
      ProjectProviderSourceRepository sources,
      GitAccountConnectionService connections) {
    this.simpleCredentials = simpleCredentials;
    this.sources = sources;
    this.connections = connections;
  }

  Optional<GitCredentialMaterial> material(UUID projectId) {
    ProjectProviderSource source = sources.findByProjectId(projectId).orElse(null);
    if (source == null || source.getCredentialMode() == ProjectCredentialMode.SIMPLE_GIT) {
      return simpleCredentials.material(projectId);
    }
    GitConnectionCredential credential = connections.credential(source.getConnectionId());
    return Optional.of(new GitCredentialMaterial(
        GitAuthenticationType.TOKEN, credential.username(), credential.token()));
  }
}
