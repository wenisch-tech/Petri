package tech.wenisch.petri.agent.run;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.git.ProjectWorktreeService;
import tech.wenisch.petri.workspace.WorkspacePaths;

/**
 * Approval-gated installation of Alpine packages into Petri's unprivileged toolchain prefix.
 * Names are validated by {@link PackageNamePolicy}; apk is run without a shell.
 */
@Service
public class PackageInstallService {
  private static final Logger log = LoggerFactory.getLogger(PackageInstallService.class);
  private static final int MAX_OUTPUT_BYTES = 200_000;
  private static final String SCRIPTLESS_MARKER = ".petri-noscripts";

  private final PackageInstallStateService state;
  private final LocalToolchain toolchain;
  private final WorkspacePaths paths;
  private final ProjectWorktreeService worktrees;
  private final ProjectSandboxSettingRepository settings;
  private final Duration timeout;
  private final boolean enabled;
  private final ReentrantLock installLock = new ReentrantLock();

  PackageInstallService(
      PackageInstallStateService state, LocalToolchain toolchain, WorkspacePaths paths,
      ProjectWorktreeService worktrees, ProjectSandboxSettingRepository settings,
      @Value("${petri.tests.install-timeout:10m}") Duration timeout,
      @Value("${petri.tests.executor:podman}") String executor) {
    this.state = state;
    this.toolchain = toolchain;
    this.paths = paths;
    this.worktrees = worktrees;
    this.settings = settings;
    this.timeout = timeout;
    this.enabled = "local".equalsIgnoreCase(executor);
  }

  public PackageInstallView request(
      UUID worktreeId, UUID agentRunId, List<String> packages, String reason) {
    if (!enabled) {
      throw new IllegalStateException(
          "Package installs are disabled: tests run in an isolated Podman sandbox "
              + "(set PETRI_TESTS_EXECUTOR=local to run them in Petri's own Alpine container)");
    }
    List<String> names = PackageNamePolicy.validate(packages);
    String safeReason = reason == null ? null : reason.strip();
    if (safeReason != null && safeReason.length() > 1000) safeReason = safeReason.substring(0, 1000);
    return state.request(worktreeId, agentRunId, names, safeReason);
  }

  public boolean enabled() { return enabled; }

  public PackageInstallView get(UUID id) { return state.get(id); }

  public List<PackageInstallView> listForAgentRun(UUID agentRunId) {
    return state.listForAgentRun(agentRunId);
  }

  public boolean hasOpenForAgentRun(UUID agentRunId) { return state.hasOpenForAgentRun(agentRunId); }

  public Set<String> installedPackages() { return state.installedPackages(); }

  public PackageInstallView approve(UUID id, String actorEmail) { return state.approve(id, actorEmail); }

  public PackageInstallView reject(UUID id, String actorEmail) { return state.reject(id, actorEmail); }

  /** Whether the project owning the worktree auto-approves package installs. */
  public boolean autoApprove(UUID worktreeId) {
    UUID projectId = worktrees.get(worktreeId).projectId();
    return settings.findById(projectId).map(ProjectSandboxSetting::autoApprovePackageInstalls).orElse(false);
  }

  /** Installs an approved request. Never holds a database transaction while apk runs. */
  public PackageInstallView execute(UUID id) {
    PackageInstallView started = state.start(id);
    try {
      TestCommandResult result = runApk(started.packages(), text -> state.progress(id, text));
      return state.complete(id, result);
    } catch (IOException | RuntimeException exception) {
      log.warn("Approved package install {} could not run", id, exception);
      return state.error(id, "Package installation could not be started: "
          + (exception.getMessage() == null ? "unknown error" : exception.getMessage()));
    }
  }

  /** Re-installs recorded packages when this container's toolchain is empty (e.g. no volume). */
  @EventListener(ApplicationReadyEvent.class)
  void reconcile() {
    try {
      Set<String> recorded = state.installedPackages();
      if (recorded.isEmpty() || java.nio.file.Files.exists(toolchain.root().resolve("lib/apk/db/installed"))) {
        return;
      }
      log.info("Restoring {} previously approved packages into the toolchain", recorded.size());
      TestCommandResult result = runApk(new ArrayList<>(recorded), null);
      if (result.exitCode() != 0 || result.timedOut()) {
        log.warn("Toolchain restore failed: {}", result.output());
      }
    } catch (IOException | RuntimeException exception) {
      log.warn("Toolchain restore skipped", exception);
    }
  }

  private TestCommandResult runApk(
      List<String> requested, java.util.function.Consumer<String> progress) throws IOException {
    toolchain.ensureRoot();
    List<String> packages = new ArrayList<>(requested);
    if (resetLegacyToolchain()) {
      // A toolchain built before --no-scripts holds packages flagged as failed; rebuild it clean.
      for (String name : state.installedPackages()) if (!packages.contains(name)) packages.add(name);
    }
    List<String> command = new ArrayList<>(List.of(
        toolchain.apkExecutable(), "--usermode", "--root", toolchain.root().toString(),
        "--initdb", "--keys-dir", "/etc/apk/keys", "--repositories-file", "/etc/apk/repositories",
        "--no-cache", "--no-progress", "--no-scripts", "add", "--"));
    command.addAll(PackageNamePolicy.validate(packages));
    java.nio.file.Path home = java.nio.file.Files.createTempDirectory(paths.temporaryDirectory(), "petri-apk-");
    installLock.lock();
    try {
      TestCommandResult result = BoundedProcess.run(command, toolchain.root(),
          toolchain.environment(home, Map.of()), timeout, MAX_OUTPUT_BYTES, "apk", progress);
      if (result.exitCode() == 0 && !result.timedOut()) {
        java.nio.file.Files.writeString(toolchain.root().resolve(SCRIPTLESS_MARKER), "1\n");
      }
      return result;
    } finally {
      installLock.unlock();
      home.toFile().delete();
    }
  }

  /** Wipes a toolchain that was created without the marker, returning whether it did. */
  private boolean resetLegacyToolchain() throws IOException {
    java.nio.file.Path root = toolchain.root();
    if (java.nio.file.Files.exists(root.resolve(SCRIPTLESS_MARKER)) || root.getNameCount() < 2
        || !java.nio.file.Files.exists(root.resolve("lib/apk/db"))) {
      return false;
    }
    log.info("Rebuilding toolchain {} without package scripts", root);
    installLock.lock();
    try (var children = java.nio.file.Files.list(root)) {
      for (java.nio.file.Path child : children.toList()) {
        try (var walk = java.nio.file.Files.walk(child)) {
          walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
      }
    } finally {
      installLock.unlock();
    }
    return true;
  }
}
