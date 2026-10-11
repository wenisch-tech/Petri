package tech.wenisch.petri.project;

import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.git.provider.GitAccountConnection;
import tech.wenisch.petri.git.provider.GitAccountConnectionService;
import tech.wenisch.petri.git.provider.ProjectProviderSource;
import tech.wenisch.petri.git.provider.ProjectProviderSourceRepository;
import tech.wenisch.petri.git.provider.ProviderRepository;

@Service
class ProjectImportItemService {
  private final GitAccountConnectionService connections;
  private final ProjectProviderSourceRepository sources;
  private final ProjectInitializationJobRepository jobs;
  private final ProjectService projects;
  private final ApplicationEventPublisher events;

  ProjectImportItemService(
      GitAccountConnectionService connections,
      ProjectProviderSourceRepository sources,
      ProjectInitializationJobRepository jobs,
      ProjectService projects,
      ApplicationEventPublisher events) {
    this.connections = connections;
    this.sources = sources;
    this.jobs = jobs;
    this.projects = projects;
    this.events = events;
  }

  @Transactional
  ProjectImportResult importOne(UUID connectionId, String repositoryId, String actorEmail) {
    GitAccountConnection connection = connections.requireOwned(connectionId, actorEmail);
    if (sources.existsByOwnerIdAndProviderInstanceIdAndExternalRepositoryId(
        connection.getOwnerId(), connection.getProviderInstanceId(), repositoryId)) {
      return new ProjectImportResult(repositoryId, null,
          ProjectImportResult.Status.ALREADY_EXISTS, null, "Repository is already imported");
    }
    ProviderRepository repository = connections.repository(connectionId, repositoryId, actorEmail);
    if (sources.existsByOwnerIdAndProviderInstanceIdAndExternalRepositoryId(
        connection.getOwnerId(), connection.getProviderInstanceId(), repository.id())) {
      return new ProjectImportResult(repository.id(), repository.fullName(),
          ProjectImportResult.Status.ALREADY_EXISTS, null, "Repository is already imported");
    }
    Project project = projects.create(new CreateProjectCommand(
        repository.name(), repository.cloneUrl(), defaultBranch(repository.defaultBranch())), actorEmail);
    sources.save(new ProjectProviderSource(project.getId(), connection.getProviderInstanceId(),
        connectionId, connection.getOwnerId(), repository, Instant.now()));
    ProjectInitializationJob job = jobs.save(
        new ProjectInitializationJob(UUID.randomUUID(), project.getId(), Instant.now()));
    events.publishEvent(new ProjectInitializationRequested(job.getId()));
    return new ProjectImportResult(repository.id(), repository.fullName(),
        ProjectImportResult.Status.CREATED, project.getId(), "Initialization queued");
  }

  private static String defaultBranch(String value) {
    return value == null || value.isBlank() ? "main" : value;
  }
}
