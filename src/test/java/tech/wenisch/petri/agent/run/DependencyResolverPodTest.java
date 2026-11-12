package tech.wenisch.petri.agent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import tech.wenisch.petri.agent.run.TrivyScanService.Resolution;
import tech.wenisch.petri.kubernetes.RealAgent;
import tech.wenisch.petri.packages.PackageEnvironment;
import tech.wenisch.petri.workspace.WorkspaceHost;

/**
 * Dependency resolution for a scan runs in the project's pod and brings the lockfiles back. A stub
 * {@code npm} stands in for the real tool so the test shows where it ran and with which settings.
 */
class DependencyResolverPodTest {
  @TempDir Path workspace;
  @TempDir Path tools;
  @TempDir Path local;
  private RealAgent agent;

  @AfterEach
  void tearDown() {
    if (agent != null) agent.close();
  }

  @SuppressWarnings("unchecked")
  private DependencyResolver resolver(UUID projectId, Map<String, String> packageEnvironment) throws Exception {
    PackageEnvironment packages = mock(PackageEnvironment.class);
    when(packages.remote(any(), any(), any(), any())).thenReturn(packageEnvironment);
    ObjectProvider<PackageEnvironment> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(packages);
    TestSandboxProfileService profiles = mock(TestSandboxProfileService.class);
    when(profiles.environmentFor(projectId)).thenReturn(Map.of("PROJECT_FLAG", "from-the-project"));
    return new DependencyResolver(mock(LocalToolchain.class), mock(Confinement.class),
        new CommandEnvironment(Map.of("PETRI_COMMAND_ENV_INSTANCE_FLAG", "from-the-instance")), profiles,
        provider);
  }

  private static void stub(Path tools, String name, String body) throws Exception {
    Path script = tools.resolve(name);
    Files.writeString(script, "#!/bin/sh\n" + body + "\n");
    assertThat(script.toFile().setExecutable(true)).isTrue();
  }

  @Test
  void theToolRunsInThePodWithItsSettingsAndTheLockfileComesBack() throws Exception {
    stub(tools, "npm", "echo \"$PROJECT_FLAG $INSTANCE_FLAG $npm_config_userconfig $PWD\" > package-lock.json");
    agent = RealAgent.start(workspace, tools);
    UUID projectId = UUID.randomUUID();
    WorkspaceHost host = agent.host(projectId);

    Path worktree = Files.createDirectories(
        agent.root().resolve("runs").resolve(projectId.toString()).resolve(UUID.randomUUID().toString()));
    Files.createDirectories(worktree.resolve("web"));
    Files.writeString(worktree.resolve("web/package.json"), "{}");
    Path copy = Files.createDirectories(local.resolve("copy"));
    assertThat(host.archives().downloadTree(worktree, copy, DependencyResolver.SKIP_DIRECTORIES, 1 << 20, 100)).isTrue();

    List<Resolution> steps = resolver(projectId, Map.of("npm_config_userconfig", "/pod/pkg/.npmrc"))
        .resolveInPod(host, worktree, copy);

    assertThat(steps).extracting(Resolution::ecosystem, Resolution::directory, Resolution::status)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("npm", "web", DependencyResolver.RESOLVED));
    String lock = Files.readString(copy.resolve("web/package-lock.json"));
    assertThat(lock).contains("from-the-project").contains("from-the-instance").contains("/pod/pkg/.npmrc");
    assertThat(lock).as("it ran on a copy in the pod's scratch space, not in the worktree")
        .doesNotContain(worktree.toString()).contains(agent.root().resolve("tmp").toString());
    assertThat(worktree.resolve("web/package-lock.json")).as("the repository is never written to").doesNotExist();
    assertThat(Files.list(agent.root().resolve("tmp")).filter(p -> p.getFileName().toString().startsWith("petri-resolve-")))
        .as("the scratch copy is removed").isEmpty();
  }

  @Test
  void aToolThatIsMissingInThePodIsReportedNotInstalled() throws Exception {
    agent = RealAgent.start(workspace, tools);
    UUID projectId = UUID.randomUUID();
    WorkspaceHost host = agent.host(projectId);
    Path worktree = Files.createDirectories(
        agent.root().resolve("runs").resolve(projectId.toString()).resolve(UUID.randomUUID().toString()));
    Files.writeString(worktree.resolve("Cargo.toml"), "[package]");
    Path copy = Files.createDirectories(local.resolve("copy"));
    host.archives().downloadTree(worktree, copy, DependencyResolver.SKIP_DIRECTORIES, 1 << 20, 100);

    List<Resolution> steps = resolver(projectId, Map.of()).resolveInPod(host, worktree, copy);

    assertThat(steps).extracting(Resolution::ecosystem, Resolution::status)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("cargo", DependencyResolver.TOOL_MISSING));
  }

  @Test
  void aFailingToolIsReportedWithItsOutput() throws Exception {
    stub(tools, "npm", "echo 'ERR! registry unreachable'; exit 3");
    agent = RealAgent.start(workspace, tools);
    UUID projectId = UUID.randomUUID();
    WorkspaceHost host = agent.host(projectId);
    Path worktree = Files.createDirectories(
        agent.root().resolve("runs").resolve(projectId.toString()).resolve(UUID.randomUUID().toString()));
    Files.writeString(worktree.resolve("package.json"), "{}");
    Path copy = Files.createDirectories(local.resolve("copy"));
    host.archives().downloadTree(worktree, copy, DependencyResolver.SKIP_DIRECTORIES, 1 << 20, 100);

    List<Resolution> steps = resolver(projectId, Map.of()).resolveInPod(host, worktree, copy);

    assertThat(steps).hasSize(1);
    assertThat(steps.get(0).status()).isEqualTo(DependencyResolver.FAILED);
    assertThat(steps.get(0).note()).contains("exit 3").contains("registry unreachable");
  }
}
