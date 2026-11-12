package tech.wenisch.petri.kubernetes;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.config.PetriProperties;
import tech.wenisch.petri.config.TlsSettings;
import tech.wenisch.petri.git.ProjectWorktreeService;
import tech.wenisch.petri.workspace.LocalWorkspaceHost;
import tech.wenisch.petri.workspace.WorkspaceHost;
import tech.wenisch.petri.workspace.WorkspaceHostResolver;

/** A project's workspace host: its Kubernetes pod when enabled, Petri's own container otherwise. */
@Component
@Primary
public class ConfiguredWorkspaceHostResolver implements WorkspaceHostResolver {
  private static final long TTL_NANOS = Duration.ofSeconds(5).toNanos();

  private record Choice(boolean remote, long at) {}

  private final KubernetesSettingsService settings;
  private final LocalWorkspaceHost local;
  private final WorkspacePodOrchestrator orchestrator;
  private final ClusterGateway gateway;
  private final PetriProperties properties;
  private final TlsSettings tls;
  private final ProjectWorktreeService worktrees;
  private final ConcurrentHashMap<UUID, Choice> choices = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, RemoteWorkspaceHost> remotes = new ConcurrentHashMap<>();

  public ConfiguredWorkspaceHostResolver(
      KubernetesSettingsService settings, LocalWorkspaceHost local,
      WorkspacePodOrchestrator orchestrator, ClusterGateway gateway, PetriProperties properties,
      TlsSettings tls, @Lazy ProjectWorktreeService worktrees) {
    this.settings = settings;
    this.local = local;
    this.orchestrator = orchestrator;
    this.gateway = gateway;
    this.properties = properties;
    this.tls = tls;
    this.worktrees = worktrees;
  }

  @Override
  public WorkspaceHost forProject(UUID projectId) {
    Choice choice = choices.get(projectId);
    if (choice == null || System.nanoTime() - choice.at() > TTL_NANOS) {
      choice = new Choice(settings.enabled(projectId), System.nanoTime());
      choices.put(projectId, choice);
    }
    if (!choice.remote()) return local;
    return remotes.computeIfAbsent(projectId,
        id -> new RemoteWorkspaceHost(id, orchestrator, gateway, properties, tls));
  }

  @Override
  public WorkspaceHost forWorktree(UUID worktreeId) {
    return forProject(worktrees.get(worktreeId).projectId());
  }

  /** Forgets the cached choice, for example after the project's settings changed. */
  public void invalidate(UUID projectId) {
    choices.remove(projectId);
  }
}
