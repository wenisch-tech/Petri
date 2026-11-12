package tech.wenisch.petri.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.secret.SecretCipher;

@SpringBootTest(properties =
    "petri.secrets.master-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")
class WorkspacePodOrchestratorTest {
  @Autowired KubernetesSettingsService settings;
  @Autowired WorkspacePodStateRepository states;
  @Autowired KubernetesWorkspaceConfigRepository configs;
  @Autowired SecretCipher cipher;
  @Autowired AuditLogService audit;
  @Autowired PlatformTransactionManager transactionManager;
  @TempDir Path agentDir;

  private final FakeClusterGateway cluster = new FakeClusterGateway();
  private final MutableClock clock = new MutableClock();
  private WorkspacePodOrchestrator orchestrator;
  private UUID projectId;

  /** A clock the test moves forward. */
  private static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-11-12T10:00:00Z");
    @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
  }

  @BeforeEach
  void setUp() throws Exception {
    Files.createDirectories(agentDir.resolve("amd64"));
    Files.write(agentDir.resolve("amd64").resolve(AgentBinaries.FILE), new byte[] {1, 2, 3, 4});
    orchestrator = new WorkspacePodOrchestrator(settings, states, cluster, cipher, audit,
        new AgentBinaries(agentDir.toString()), new TransactionTemplate(transactionManager), clock,
        Duration.ofSeconds(2), 10);
    settings.saveInstance(new KubernetesSettingsService.Save(false, false, "IN_CLUSTER", null, false,
        null, null, false, null, false, "petri-workspaces", "registry.test/ws:1", "5Gi", null, null,
        null, null, null, 1000L, 1000L, 10), "admin");
    projectId = UUID.randomUUID();
    settings.saveProject(projectId, new KubernetesSettingsService.Save(true, false, null, null,
        false, null, null, false, null, false, null, null, null, null, null, null, null, null, null,
        null, null), "owner");
  }

  /** This test commits its data, so it removes it for the tests that assume an empty instance. */
  @org.junit.jupiter.api.AfterEach
  void cleanUp() {
    states.deleteAll();
    configs.deleteAll();
  }

  /** An orchestrator that finds a toolchain bundle in {@code bundles} (nothing when it is empty). */
  private WorkspacePodOrchestrator withBundles(Path bundles) {
    return new WorkspacePodOrchestrator(settings, states, cluster, cipher, audit,
        new AgentBinaries(agentDir.toString()), new ToolchainBundles(java.util.List.of(bundles)),
        new TransactionTemplate(transactionManager), clock, Duration.ofSeconds(2), 10);
  }

  private static void bundle(Path directory, String version, int size) throws Exception {
    Files.createDirectories(directory.resolve("amd64"));
    Files.write(directory.resolve("amd64").resolve(ToolchainBundles.FILE), new byte[size]);
    Files.writeString(directory.resolve("amd64").resolve(ToolchainBundles.VERSION_FILE), version + "\n");
  }

  @Test
  void theToolchainBundleIsCopiedOncePerVolumeAndVersion(@TempDir Path bundles) throws Exception {
    bundle(bundles, "bundle-1", 7);
    var withBundle = withBundles(bundles);

    PodHandle handle = withBundle.ensureRunning(projectId);
    assertThat(handle.toolchain()).isEqualTo(ToolchainMode.FALLBACK);
    assertThat(cluster.toolchainUploads).containsExactly(7);

    withBundle.stop(projectId, "owner");
    withBundle.ensureRunning(projectId);
    assertThat(cluster.toolchainUploads).as("the volume already holds this version").hasSize(1);

    bundle(bundles, "bundle-2", 9);
    cluster.versionAfterUpload = "bundle-2";
    withBundle.stop(projectId, "owner");
    withBundle.ensureRunning(projectId);
    assertThat(cluster.toolchainUploads).as("a newer Petri brings a newer bundle").containsExactly(7, 9);
  }

  @Test
  void anImageWithoutGitWorksThroughTheBundleAndFailsClearlyWithout(@TempDir Path bundles) throws Exception {
    cluster.gitAvailable = false;
    bundle(bundles, "bundle-1", 3);
    assertThat(withBundles(bundles).ensureRunning(projectId).toolchain().enabled()).isTrue();

    orchestrator.stop(projectId, "owner");
    cluster.toolchainVersion = null;
    settings.saveProject(projectId, new KubernetesSettingsService.Save(true, false, null, null, false,
        null, null, false, null, false, null, null, null, null, null, null, null, null, null, null,
        null, "OFF"), "owner");
    assertThatThrownBy(() -> withBundles(bundles).ensureRunning(projectId))
        .isInstanceOf(WorkspaceHostUnavailableException.class)
        .hasMessageContaining("no git").hasMessageContaining("bundled tools");
  }

  @Test
  void theModeOfTheProjectOverridesTheInstanceAndOffCopiesNothing(@TempDir Path bundles) throws Exception {
    bundle(bundles, "bundle-1", 3);
    settings.saveProject(projectId, new KubernetesSettingsService.Save(true, false, null, null, false,
        null, null, false, null, false, null, null, null, null, null, null, null, null, null, null,
        null, "prefer"), "owner");
    assertThat(withBundles(bundles).ensureRunning(projectId).toolchain()).isEqualTo(ToolchainMode.PREFER);

    withBundles(bundles).stop(projectId, "owner");
    cluster.toolchainUploads.clear();
    settings.saveProject(projectId, new KubernetesSettingsService.Save(true, false, null, null, false,
        null, null, false, null, false, null, null, null, null, null, null, null, null, null, null,
        null, "OFF"), "owner");
    assertThat(withBundles(bundles).ensureRunning(projectId).toolchain()).isEqualTo(ToolchainMode.OFF);
    assertThat(cluster.toolchainUploads).isEmpty();
  }

  @Test
  void aBundleThatCannotBeCopiedDoesNotStopAPodWhoseImageHasTheTools(@TempDir Path bundles) throws Exception {
    bundle(bundles, "bundle-1", 3);
    cluster.failToolchainUpload = true;
    assertThat(withBundles(bundles).ensureRunning(projectId).projectId()).isEqualTo(projectId);
  }

  @Test
  void whenTheImageHasNoGitAndTheBundleCannotBeCopiedTheErrorSaysWhy(@TempDir Path bundles) throws Exception {
    cluster.gitAvailable = false;
    cluster.failToolchainUpload = true;
    bundle(bundles, "bundle-1", 3);
    assertThatThrownBy(() -> withBundles(bundles).ensureRunning(projectId))
        .isInstanceOf(WorkspaceHostUnavailableException.class)
        .hasMessageContaining("no git").hasMessageContaining("could not be installed")
        .hasMessageContaining("bundle refused");
  }

  @Test
  void withoutABundleInThisInstanceThePodUsesItsImage(@TempDir Path emptyDirectory) {
    assertThat(withBundles(emptyDirectory).ensureRunning(projectId).projectId()).isEqualTo(projectId);
    assertThat(cluster.toolchainUploads).isEmpty();
  }

  @Test
  void startingCreatesTheObjectsCopiesTheAgentAndRecordsTheState() {
    PodHandle handle = orchestrator.ensureRunning(projectId);

    assertThat(handle.namespace()).isEqualTo("petri-workspaces");
    assertThat(cluster.pods).containsKey("petri-workspaces/petri-ws-" + projectId);
    assertThat(cluster.claims).hasSize(1);
    assertThat(cluster.secrets).hasSize(1);
    assertThat(cluster.installedSizes).containsExactly(4);
    WorkspacePodState state = states.findById(projectId).orElseThrow();
    assertThat(state.getPhase()).isEqualTo(WorkspacePodPhase.RUNNING);
    assertThat(state.getAgentArch()).isEqualTo("amd64");
    assertThat(state.getImageDigest()).isEqualTo("sha256:digest");
    assertThat(state.getAgentTokenCiphertext()).startsWith("v1:").doesNotContain(handle.agentToken());
    assertThat(handle.agentToken()).hasSizeGreaterThan(30);
  }

  @Test
  void aRunningPodIsReusedWithoutTouchingTheCluster() {
    PodHandle first = orchestrator.ensureRunning(projectId);
    int calls = cluster.calls.size();
    PodHandle second = orchestrator.ensureRunning(projectId);
    assertThat(second.agentToken()).isEqualTo(first.agentToken());
    assertThat(cluster.calls).hasSize(calls);
  }

  @Test
  void aPodThatSurvivedARestartIsAdoptedWithItsToken() {
    PodHandle first = orchestrator.ensureRunning(projectId);
    var restarted = new WorkspacePodOrchestrator(settings, states, cluster, cipher, audit,
        new AgentBinaries(agentDir.toString()), new TransactionTemplate(transactionManager), clock,
        Duration.ofSeconds(2), 10);
    PodHandle second = restarted.ensureRunning(projectId);
    assertThat(second.agentToken()).isEqualTo(first.agentToken());
    assertThat(cluster.installedSizes).hasSize(1);
    assertThat(cluster.calls.stream().filter("pod"::equals)).hasSize(1);
  }

  @Test
  void stoppingKeepsTheVolumeAndTheNextUseStartsANewPod() {
    orchestrator.ensureRunning(projectId);
    orchestrator.stop(projectId, "owner");
    assertThat(cluster.pods).isEmpty();
    assertThat(cluster.claims).hasSize(1);
    assertThat(states.findById(projectId).orElseThrow().getPhase()).isEqualTo(WorkspacePodPhase.STOPPED);

    orchestrator.ensureRunning(projectId);
    assertThat(cluster.pods).hasSize(1);
    assertThat(cluster.installedSizes).hasSize(2);
  }

  @Test
  void deletingTheDataRemovesPodVolumeSecretAndState() {
    orchestrator.ensureRunning(projectId);
    orchestrator.deleteData(projectId, "owner");
    assertThat(cluster.pods).isEmpty();
    assertThat(cluster.claims).isEmpty();
    assertThat(cluster.secrets).isEmpty();
    assertThat(states.findById(projectId)).isEmpty();
  }

  @Test
  void anIdlePodIsStoppedButOneInUseIsNot() {
    try (var session = orchestrator.use(projectId)) {
      clock.now = clock.now.plus(Duration.ofMinutes(30));
      orchestrator.reapIdle();
      assertThat(cluster.pods).hasSize(1);
    }
    orchestrator.reapIdle();
    assertThat(cluster.pods).hasSize(1);
    clock.now = clock.now.plus(Duration.ofMinutes(11));
    orchestrator.reapIdle();
    assertThat(cluster.pods).isEmpty();
    assertThat(states.findById(projectId).orElseThrow().getPhase()).isEqualTo(WorkspacePodPhase.STOPPED);
  }

  @Test
  void reconcileForgetsPodsThatVanishedWhilePetriWasDown() {
    orchestrator.ensureRunning(projectId);
    cluster.pods.clear();
    orchestrator.reconcile();
    assertThat(states.findById(projectId).orElseThrow().getPhase()).isEqualTo(WorkspacePodPhase.STOPPED);
  }

  @Test
  void anImageWithoutGitFailsWithAClearMessageAndLeavesNoPod() {
    cluster.gitAvailable = false;
    assertThatThrownBy(() -> orchestrator.ensureRunning(projectId))
        .isInstanceOf(WorkspaceHostUnavailableException.class).hasMessageContaining("no git");
    assertThat(cluster.pods).isEmpty();
    WorkspacePodState state = states.findById(projectId).orElseThrow();
    assertThat(state.getPhase()).isEqualTo(WorkspacePodPhase.FAILED);
    assertThat(state.getLastError()).contains("no git");
  }

  @Test
  void anUnwritableVolumeSuggestsTheSecurityContext() {
    cluster.mkdirStatus = 400;
    assertThatThrownBy(() -> orchestrator.ensureRunning(projectId))
        .hasMessageContaining("not writable").hasMessageContaining("file system group");
  }

  @Test
  void aPodThatNeverStartsReportsWhyAfterTheTimeout() {
    cluster.podRuns = false;
    cluster.waitingReason = "ImagePullBackOff";
    assertThatThrownBy(() -> orchestrator.ensureRunning(projectId))
        .isInstanceOf(WorkspaceHostUnavailableException.class)
        .hasMessageContaining("ImagePullBackOff");
    assertThat(cluster.pods).isEmpty();
  }

  @Test
  void anUnsupportedArchitectureIsExplained() {
    cluster.machine = "riscv64";
    assertThatThrownBy(() -> orchestrator.ensureRunning(projectId))
        .hasMessageContaining("unsupported CPU architecture");
  }

  @Test
  void theSpecsCarryLabelsSecurityContextAndResources() {
    orchestrator.ensureRunning(projectId);
    var pod = cluster.pods.get("petri-workspaces/petri-ws-" + projectId);
    assertThat(pod.getMetadata().getLabels())
        .containsEntry("app.kubernetes.io/managed-by", "petri")
        .containsEntry("petri.wenisch.tech/project", projectId.toString());
    assertThat(pod.getSpec().getSecurityContext().getFsGroup()).isEqualTo(1000L);
    assertThat(pod.getSpec().getAutomountServiceAccountToken()).isFalse();
    var container = pod.getSpec().getContainers().get(0);
    assertThat(container.getImage()).isEqualTo("registry.test/ws:1");
    assertThat(container.getCommand()).containsExactly("sh", "-c", WorkspacePodSpecs.BOOT_COMMAND);
    assertThat(container.getEnv().get(0).getValueFrom().getSecretKeyRef().getKey()).isEqualTo("token");
    assertThat(cluster.claims.values().iterator().next().getSpec().getResources().getRequests()
        .get("storage").toString()).isEqualTo("5Gi");
  }

  @Test
  void theInstallScriptReadsExactlyTheBinary() {
    assertThat(WorkspacePodSpecs.installScript(1234)).contains("head -c 1234 >")
        .contains("/petri/bin/petri-workspace-agent").contains("askpass");
  }
}
