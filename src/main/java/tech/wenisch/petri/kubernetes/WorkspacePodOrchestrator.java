package tech.wenisch.petri.kubernetes;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.kubernetes.ClusterGateway.PodInfo;
import tools.jackson.databind.JsonNode;

/**
 * Creates, adopts, stops and removes the workspace pod of each project. The cluster is the source of
 * truth; the database records what Petri last saw so the UI and the idle reaper need no cluster call.
 */
@Service
public class WorkspacePodOrchestrator implements PodSessions {
  private static final Logger log = LoggerFactory.getLogger(WorkspacePodOrchestrator.class);
  private static final Duration HEALTH_FRESH = Duration.ofSeconds(30);
  private static final java.util.Set<String> FATAL_REASONS =
      java.util.Set.of("InvalidImageName", "CreateContainerConfigError", "CreateContainerError");

  private final class SessionImpl implements Session {
    private final PodHandle handle;
    private boolean closed;

    private SessionImpl(PodHandle handle) {
      this.handle = handle;
    }

    @Override
    public PodHandle handle() {
      return handle;
    }

    @Override
    public void close() {
      if (closed) return;
      closed = true;
      inFlight.get(handle.projectId()).decrementAndGet();
      touch(handle.projectId());
    }
  }

  private final KubernetesSettingsService settings;
  private final WorkspacePodStateRepository states;
  private final ClusterGateway gateway;
  private final tech.wenisch.petri.secret.SecretCipher cipher;
  private final AuditLogService audit;
  private final AgentBinaries binaries;
  private final ToolchainBundles toolchains;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private final Duration startTimeout;
  private final long pollMillis;
  private final SecureRandom random = new SecureRandom();
  private final ConcurrentHashMap<UUID, CompletableFuture<PodHandle>> starting = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, Instant> healthyAt = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, AtomicInteger> inFlight = new ConcurrentHashMap<>();

  @Autowired
  public WorkspacePodOrchestrator(
      KubernetesSettingsService settings, WorkspacePodStateRepository states, ClusterGateway gateway,
      tech.wenisch.petri.secret.SecretCipher cipher, AuditLogService audit, AgentBinaries binaries,
      ToolchainBundles toolchains, PlatformTransactionManager transactionManager,
      @Value("${petri.kubernetes.pod-start-timeout:5m}") Duration startTimeout,
      @Value("${petri.kubernetes.poll-interval-ms:1000}") long pollMillis) {
    this(settings, states, gateway, cipher, audit, binaries, toolchains,
        new TransactionTemplate(transactionManager), Clock.systemUTC(), startTimeout, pollMillis);
  }

  /** Without a toolchain bundle, as when Petri ships none. */
  WorkspacePodOrchestrator(
      KubernetesSettingsService settings, WorkspacePodStateRepository states, ClusterGateway gateway,
      tech.wenisch.petri.secret.SecretCipher cipher, AuditLogService audit, AgentBinaries binaries,
      TransactionTemplate transactions, Clock clock, Duration startTimeout, long pollMillis) {
    this(settings, states, gateway, cipher, audit, binaries, null, transactions, clock, startTimeout,
        pollMillis);
  }

  WorkspacePodOrchestrator(
      KubernetesSettingsService settings, WorkspacePodStateRepository states, ClusterGateway gateway,
      tech.wenisch.petri.secret.SecretCipher cipher, AuditLogService audit, AgentBinaries binaries,
      ToolchainBundles toolchains, TransactionTemplate transactions, Clock clock,
      Duration startTimeout, long pollMillis) {
    this.toolchains = toolchains;
    this.settings = settings;
    this.states = states;
    this.gateway = gateway;
    this.cipher = cipher;
    this.audit = audit;
    this.binaries = binaries;
    this.transactions = transactions;
    this.clock = clock;
    this.startTimeout = startTimeout;
    this.pollMillis = pollMillis;
  }

  // ---- use ---------------------------------------------------------------------------------

  /** Makes sure the pod runs and marks it in use until the session is closed. */
  @Override
  public Session use(UUID projectId) {
    PodHandle handle = ensureRunning(projectId);
    inFlight.computeIfAbsent(projectId, ignored -> new AtomicInteger()).incrementAndGet();
    return new SessionImpl(handle);
  }

  @Override
  public void forget(UUID projectId) {
    healthyAt.remove(projectId);
  }

  /** Returns the pod of the project, starting it first when it is not running. */
  public PodHandle ensureRunning(UUID projectId) {
    Optional<PodHandle> fast = fastPath(projectId);
    if (fast.isPresent()) return fast.get();
    CompletableFuture<PodHandle> mine = new CompletableFuture<>();
    CompletableFuture<PodHandle> existing = starting.putIfAbsent(projectId, mine);
    if (existing != null) return await(existing);
    try {
      PodHandle handle = start(projectId);
      mine.complete(handle);
      return handle;
    } catch (RuntimeException exception) {
      mine.completeExceptionally(exception);
      throw exception;
    } finally {
      starting.remove(projectId, mine);
    }
  }

  private PodHandle await(CompletableFuture<PodHandle> future) {
    try {
      return future.join();
    } catch (java.util.concurrent.CompletionException exception) {
      if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
      throw exception;
    }
  }

  private Optional<PodHandle> fastPath(UUID projectId) {
    Instant healthy = healthyAt.get(projectId);
    if (healthy == null || Duration.between(healthy, clock.instant()).compareTo(HEALTH_FRESH) > 0) {
      return Optional.empty();
    }
    return states.findById(projectId)
        .filter(state -> state.getPhase() == WorkspacePodPhase.RUNNING)
        .filter(state -> state.getAgentTokenCiphertext() != null)
        .map(state -> handle(state, settings.effective(projectId)));
  }

  private PodHandle handle(WorkspacePodState state, EffectiveSettings effective) {
    return new PodHandle(state.getProjectId(), effective.cluster(), state.getNamespace(),
        state.getPodName(), cipher.decrypt(state.getAgentTokenCiphertext()), effective.pod().toolchain());
  }

  // ---- start -------------------------------------------------------------------------------

  private PodHandle start(UUID projectId) {
    EffectiveSettings effective;
    try {
      effective = settings.effective(projectId);
    } catch (IllegalStateException exception) {
      throw new WorkspaceHostUnavailableException(exception.getMessage(), exception);
    }
    ClusterConfig cluster = effective.cluster();
    PodSettings pod = effective.pod();
    WorkspacePodState state = update(projectId, pod.namespace(), s -> {
      s.namespace(pod.namespace(), clock.instant());
      s.phase(WorkspacePodPhase.STARTING, clock.instant());
    });
    try {
      String token = state.getAgentTokenCiphertext() == null ? null : cipher.decrypt(state.getAgentTokenCiphertext());
      Optional<PodInfo> existing = gateway.pod(cluster, state.getNamespace(), state.getPodName());
      if (existing.isPresent() && token != null && matches(existing.get(), pod)) {
        PodHandle adopted = new PodHandle(projectId, cluster, state.getNamespace(), state.getPodName(),
            token, pod.toolchain());
        if (existing.get().running() && healthy(adopted)) {
          // A newer Petri may carry a newer bundle than the one this volume received.
          installToolchain(adopted, state.getAgentArch());   // a failure only matters at a fresh start
          return running(state, adopted, existing.get(), null);
        }
      }
      if (existing.isPresent()) {
        gateway.deletePod(cluster, state.getNamespace(), state.getPodName());
        waitGone(cluster, state);
      }
      if (token == null || existing.isPresent() && !matches(existing.get(), pod)) {
        token = newToken();
        String sealed = cipher.encrypt(token);
        update(projectId, pod.namespace(), s -> s.agentToken(sealed));
      }
      WorkspacePodState current = states.findById(projectId).orElseThrow();
      gateway.applySecret(cluster, WorkspacePodSpecs.secret(current, token));
      gateway.createPvcIfAbsent(cluster, WorkspacePodSpecs.pvc(current, pod));
      gateway.createPod(cluster, WorkspacePodSpecs.pod(current, pod));
      PodInfo info = awaitRunning(cluster, current);
      PodHandle handle = new PodHandle(projectId, cluster, current.getNamespace(), current.getPodName(),
          token, pod.toolchain());
      String arch = AgentBinaries.architecture(
          gateway.exec(cluster, current.getNamespace(), current.getPodName(),
              WorkspacePodSpecs.CONTAINER, Duration.ofSeconds(30), "uname", "-m"));
      byte[] binary = binaries.binary(arch);
      gateway.install(cluster, current.getNamespace(), current.getPodName(),
          WorkspacePodSpecs.CONTAINER, binary, WorkspacePodSpecs.installScript(binary.length),
          Duration.ofMinutes(3));
      awaitHealthy(handle);
      String toolchainProblem = installToolchain(handle, arch);
      verifyImage(handle, toolchainProblem);
      audit.record("system", "WORKSPACE_POD_STARTED", "PROJECT", projectId,
          "Started workspace pod in " + current.getNamespace() + " from image " + pod.image());
      return running(current, handle, info, arch);
    } catch (WorkspaceHostUnavailableException exception) {
      fail(projectId, cluster, state, exception.getMessage());
      throw exception;
    } catch (IOException | RuntimeException exception) {
      String message = "The workspace pod could not be started: " + FabricClusterGateway.brief(exception);
      log.warn("Workspace pod of project {} failed to start", projectId, exception);
      fail(projectId, cluster, state, message);
      throw new WorkspaceHostUnavailableException(message, exception);
    }
  }

  private static boolean matches(PodInfo info, PodSettings pod) {
    return !info.deleting() && (info.image() == null || info.image().equals(pod.image()));
  }

  private PodHandle running(WorkspacePodState state, PodHandle handle, PodInfo info, String arch) {
    update(state.getProjectId(), state.getNamespace(), s -> s.started(
        arch != null ? arch : s.getAgentArch(), info.image(), info.imageId(), info.nodeName(),
        clock.instant()));
    healthyAt.put(state.getProjectId(), clock.instant());
    return handle;
  }

  private void fail(UUID projectId, ClusterConfig cluster, WorkspacePodState state, String message) {
    try {
      gateway.deletePod(cluster, state.getNamespace(), state.getPodName());
    } catch (RuntimeException ignored) {
      // the cluster may be unreachable; the state records the failure either way
    }
    healthyAt.remove(projectId);
    update(projectId, state.getNamespace(), s -> s.failed(message, clock.instant()));
  }

  private PodInfo awaitRunning(ClusterConfig cluster, WorkspacePodState state) {
    Instant deadline = Instant.now().plus(startTimeout);
    String last = null;
    while (Instant.now().isBefore(deadline)) {
      Optional<PodInfo> info = gateway.pod(cluster, state.getNamespace(), state.getPodName());
      if (info.isPresent()) {
        PodInfo value = info.get();
        if (value.running()) return value;
        if (value.waitingReason() != null) {
          last = value.waitingReason() + (value.waitingMessage() == null ? "" : ": " + value.waitingMessage());
          if (FATAL_REASONS.contains(value.waitingReason())) {
            throw new WorkspaceHostUnavailableException("The workspace pod cannot start (" + last + ")");
          }
        }
        if ("Failed".equals(value.phase())) {
          throw new WorkspaceHostUnavailableException("The workspace pod failed to start");
        }
      }
      sleep();
    }
    throw new WorkspaceHostUnavailableException("The workspace pod did not start within "
        + startTimeout.toMinutes() + " minutes" + (last == null ? "" : " (" + last + ")")
        + ". Check the image name and the registry access of the cluster.");
  }

  private void waitGone(ClusterConfig cluster, WorkspacePodState state) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
    while (Instant.now().isBefore(deadline)) {
      if (gateway.pod(cluster, state.getNamespace(), state.getPodName()).isEmpty()) return;
      sleep();
    }
    throw new WorkspaceHostUnavailableException("The old workspace pod does not terminate");
  }

  private boolean healthy(PodHandle handle) {
    return failure(handle) == null;
  }

  /** Why the agent does not answer, or null when it does. */
  private String failure(PodHandle handle) {
    try {
      new AgentClient(gateway, handle).json("GET", "/info", null);
      return null;
    } catch (IOException | RuntimeException exception) {
      return FabricClusterGateway.brief(exception);
    }
  }

  private void awaitHealthy(PodHandle handle) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
    String last = null;
    while (Instant.now().isBefore(deadline)) {
      last = failure(handle);
      if (last == null) return;
      sleep();
    }
    throw new WorkspaceHostUnavailableException(
        "The workspace agent did not become ready (" + last + "). The image needs a POSIX shell (sh).");
  }

  private static boolean hasTool(JsonNode toolchain, String name) {
    for (JsonNode tool : toolchain.path("tools")) {
      if (name.equals(tool.asString(""))) return true;
    }
    return false;
  }

  /**
   * Copies the bundle of command line tools into the pod's volume when the volume has none or an
   * older one. The volume keeps it, so this costs a transfer once per volume and Petri release.
   * Failing to do so is not fatal: an image that has its own tools works without it.
   */
  private String installToolchain(PodHandle handle, String architecture) {
    if (!handle.toolchain().enabled() || toolchains == null || architecture == null) return null;
    var bundle = toolchains.find(architecture);
    if (bundle.isEmpty()) {
      log.info("No toolchain bundle for {} in this Petri instance; the pod uses its image's tools",
          architecture);
      return "this Petri instance has no bundle for " + architecture;
    }
    try {
      AgentClient client = new AgentClient(gateway, handle);
      JsonNode current = client.json("GET", "/toolchain", null);
      if (current.path("present").asBoolean(false)
          && bundle.get().version().equals(current.path("version").asString(""))) {
        return null;
      }
      long started = System.nanoTime();
      try (var input = bundle.get().open();
          var response = client.openStream(
              "POST", "/toolchain", input, bundle.get().size(), "application/gzip")) {
        response.body().readAllBytes();
      }
      audit.record("system", "WORKSPACE_TOOLCHAIN_INSTALLED", "PROJECT", handle.projectId(),
          "Copied the bundled tools (version " + bundle.get().version() + ") into the workspace pod");
      log.info("Copied the toolchain bundle {} into the pod of project {} in {} ms",
          bundle.get().version(), handle.projectId(), (System.nanoTime() - started) / 1_000_000);
      return null;
    } catch (IOException | RuntimeException exception) {
      log.warn("The toolchain bundle could not be copied into the pod of project {}",
          handle.projectId(), exception);
      return FabricClusterGateway.brief(exception);
    }
  }

  private void verifyImage(PodHandle handle, String toolchainProblem) throws IOException {
    JsonNode info = new AgentClient(gateway, handle).json("GET", "/info", null);
    boolean bundledGit = handle.toolchain().enabled() && hasTool(info.path("toolchain"), "git");
    if (!info.path("git").path("available").asBoolean(false) && !bundledGit) {
      throw new WorkspaceHostUnavailableException(
          "The image of the workspace pod has no git. Use an image with git installed"
              + (handle.toolchain().enabled()
                  ? ", or make the bundled tools available"
                      + (toolchainProblem == null ? "." : " (they could not be installed: " + toolchainProblem + ").")
                  : ", or allow the bundled tools in the Kubernetes workspace settings."));
    }
    if (!info.path("sh").path("available").asBoolean(false)) {
      throw new WorkspaceHostUnavailableException("The image of the workspace pod has no sh.");
    }
    try {
      new AgentClient(gateway, handle).json("POST", "/mkdir",
          java.util.Map.of("path", info.path("root").asString("/petri/workspace") + "/tmp"));
    } catch (IllegalArgumentException exception) {
      throw new WorkspaceHostUnavailableException("The workspace volume is not writable by user "
          + info.path("uid").asString("?") + ". Set a file system group or user ID in the "
          + "Kubernetes workspace settings.");
    }
  }

  private String newToken() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private void sleep() {
    try {
      Thread.sleep(pollMillis);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new WorkspaceHostUnavailableException("Interrupted while waiting for the workspace pod");
    }
  }

  // ---- state -------------------------------------------------------------------------------

  private WorkspacePodState update(UUID projectId, String namespace, Consumer<WorkspacePodState> change) {
    return transactions.execute(status -> {
      WorkspacePodState state = states.findById(projectId)
          .orElseGet(() -> new WorkspacePodState(projectId, namespace, clock.instant()));
      change.accept(state);
      return states.save(state);
    });
  }

  public void touch(UUID projectId) {
    states.findById(projectId).ifPresent(state ->
        update(projectId, state.getNamespace(), s -> s.touch(clock.instant())));
  }

  public Optional<WorkspacePodState> status(UUID projectId) {
    return states.findById(projectId);
  }

  // ---- stop, remove, reap --------------------------------------------------------------------

  /** Deletes the pod and keeps the volume. */
  public void stop(UUID projectId, String actor) {
    Optional<WorkspacePodState> state = states.findById(projectId);
    if (state.isEmpty()) return;
    ClusterConfig cluster = clusterOf(projectId);
    update(projectId, state.get().getNamespace(), s -> s.phase(WorkspacePodPhase.STOPPING, clock.instant()));
    try {
      gateway.deletePod(cluster, state.get().getNamespace(), state.get().getPodName());
    } finally {
      healthyAt.remove(projectId);
      update(projectId, state.get().getNamespace(), s -> s.stopped(clock.instant()));
    }
    audit.record(actor, "WORKSPACE_POD_STOPPED", "PROJECT", projectId, "Stopped the workspace pod");
  }

  /** Deletes pod, volume and credentials; the workspace content is gone. */
  public void deleteData(UUID projectId, String actor) {
    Optional<WorkspacePodState> state = states.findById(projectId);
    if (state.isEmpty()) return;
    ClusterConfig cluster = clusterOf(projectId);
    WorkspacePodState value = state.get();
    gateway.deletePod(cluster, value.getNamespace(), value.getPodName());
    gateway.deletePvc(cluster, value.getNamespace(), value.getPvcName());
    gateway.deleteSecret(cluster, value.getNamespace(), value.getSecretName());
    healthyAt.remove(projectId);
    transactions.executeWithoutResult(status -> states.deleteById(projectId));
    audit.record(actor, "WORKSPACE_POD_DATA_DELETED", "PROJECT", projectId,
        "Deleted the workspace pod, volume and credentials");
  }

  private ClusterConfig clusterOf(UUID projectId) {
    ClusterConfig cluster = settings.clusterFor(projectId);
    if (cluster == null) {
      throw new WorkspaceHostUnavailableException("No Kubernetes cluster is configured for this project");
    }
    return cluster;
  }

  /** Stops pods that nobody used for the project's idle period. */
  @Scheduled(fixedDelayString = "${petri.kubernetes.reaper-interval-ms:60000}",
      initialDelayString = "${petri.kubernetes.reaper-interval-ms:60000}")
  public void reapIdle() {
    for (WorkspacePodState state : states.findByPhaseIn(List.of(WorkspacePodPhase.RUNNING))) {
      UUID projectId = state.getProjectId();
      AtomicInteger busy = inFlight.get(projectId);
      if ((busy != null && busy.get() > 0) || starting.containsKey(projectId)) continue;
      int minutes;
      try {
        minutes = settings.effective(projectId).pod().idleTimeoutMinutes();
      } catch (RuntimeException exception) {
        minutes = PodSettings.DEFAULT_IDLE_MINUTES;
      }
      Instant last = state.getLastUsedAt() == null ? state.getUpdatedAt() : state.getLastUsedAt();
      if (Duration.between(last, clock.instant()).compareTo(Duration.ofMinutes(minutes)) < 0) continue;
      try {
        stop(projectId, "system");
        audit.record("system", "WORKSPACE_POD_REAPED", "PROJECT", projectId,
            "Stopped the idle workspace pod after " + minutes + " minutes");
      } catch (RuntimeException exception) {
        log.warn("Could not stop the idle workspace pod of project {}", projectId, exception);
      }
    }
  }

  /** After a restart, forget pods that disappeared while Petri was down. */
  @EventListener(ApplicationReadyEvent.class)
  public void reconcile() {
    for (WorkspacePodState state : states.findByPhaseIn(
        List.of(WorkspacePodPhase.RUNNING, WorkspacePodPhase.STARTING, WorkspacePodPhase.STOPPING))) {
      try {
        ClusterConfig cluster = settings.clusterFor(state.getProjectId());
        boolean alive = cluster != null
            && gateway.pod(cluster, state.getNamespace(), state.getPodName()).isPresent();
        if (!alive) update(state.getProjectId(), state.getNamespace(), s -> s.stopped(clock.instant()));
      } catch (RuntimeException exception) {
        log.warn("Could not reconcile the workspace pod of project {}", state.getProjectId(), exception);
      }
    }
  }
}
