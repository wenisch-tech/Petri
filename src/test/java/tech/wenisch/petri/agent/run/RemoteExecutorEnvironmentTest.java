package tech.wenisch.petri.agent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import tech.wenisch.petri.kubernetes.RealAgent;
import tech.wenisch.petri.packages.PackageEnvironment;
import tech.wenisch.petri.workspace.WorkspaceHost;

/**
 * Terminal commands and approved tests in a pod see the same variables as locally: the instance's,
 * the sandbox profile's and the project's, and the configured package sources.
 */
class RemoteExecutorEnvironmentTest {
  @TempDir Path workspace;
  private RealAgent agent;
  private WorkspaceHost host;
  private final UUID projectId = UUID.randomUUID();
  private Path worktree;
  private CommandEnvironment commandEnvironment;
  private ObjectProvider<PackageEnvironment> packages;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() throws Exception {
    agent = RealAgent.start(workspace);
    host = agent.host(projectId);
    worktree = Files.createDirectories(
        agent.root().resolve("runs").resolve(projectId.toString()).resolve(UUID.randomUUID().toString()));
    commandEnvironment = new CommandEnvironment(Map.of(
        "HTTPS_PROXY", "http://proxy.test:3128", "PETRI_COMMAND_ENV_INSTANCE_FLAG", "instance"));
    PackageEnvironment environment = mock(PackageEnvironment.class);
    when(environment.remote(any(), any(), any(), any())).thenReturn(Map.of(
        "npm_config_userconfig", "/pod/pkg/.npmrc", "MAVEN_ARGS", "-s /pod/pkg/settings.xml"));
    packages = mock(ObjectProvider.class);
    when(packages.getIfAvailable()).thenReturn(environment);
  }

  @AfterEach
  void tearDown() {
    if (agent != null) agent.close();
  }

  @Test
  void aTerminalCommandInThePodSeesInstanceProfileAndProjectVariablesAndPackageSources() throws Exception {
    TestSandboxProfileService profiles = mock(TestSandboxProfileService.class);
    // environmentFor is the profile's variables with the project's on top.
    when(profiles.environmentFor(projectId)).thenReturn(Map.of("PROJECT_FLAG", "project", "PROFILE_FLAG", "profile"));
    TerminalRunner runner = new TerminalRunner(null, mock(LocalToolchain.class), mock(Confinement.class),
        profiles, commandEnvironment, "local", "podman", Duration.ofSeconds(30), 100_000, packages);

    TestCommandResult result = runner.run(host, projectId, worktree, "env | sort", null);

    assertThat(result.exitCode()).isZero();
    assertThat(result.output())
        .contains("PROJECT_FLAG=project", "PROFILE_FLAG=profile", "INSTANCE_FLAG=instance",
            "HTTPS_PROXY=http://proxy.test:3128", "https_proxy=http://proxy.test:3128",
            "npm_config_userconfig=/pod/pkg/.npmrc", "MAVEN_ARGS=-s /pod/pkg/settings.xml", "CI=true")
        .contains("HOME=" + agent.root().resolve("tmp").resolve("petri-term-"))
        .contains("MAVEN_OPTS=-Dmaven.repo.local=" + agent.root().resolve("test-caches").resolve(projectId.toString()).resolve("maven"))
        .contains("TMPDIR=" + agent.root().resolve("worktree-tmp"));
    assertThat(Files.list(agent.root().resolve("tmp")).filter(p -> p.getFileName().toString().startsWith("petri-term-")))
        .as("the command's home directory is removed afterwards").isEmpty();
  }

  @Test
  void anApprovedTestInThePodGetsTheProfileVariablesAndPackageSourcesToo() throws Exception {
    var profile = new TestSandboxProfileView(UUID.randomUUID(), "java", "image", Set.of(TestBuildSystem.GRADLE),
        "2", "2g", 256, Duration.ofSeconds(30), 100_000, true, true, true,
        Map.of("PROFILE_FLAG", "profile", "PROJECT_FLAG", "project"));
    var executor = new RemoteWorkspaceTestExecutor(commandEnvironment, packages);

    TestCommandResult result = executor.run(host, projectId, worktree,
        new TestCommand(TestBuildSystem.GRADLE, java.util.List.of("sh", "-c", "env | sort")), profile, null);

    assertThat(result.exitCode()).isZero();
    assertThat(result.output())
        .contains("PROFILE_FLAG=profile", "PROJECT_FLAG=project", "INSTANCE_FLAG=instance",
            "npm_config_userconfig=/pod/pkg/.npmrc", "CI=true")
        .contains("GRADLE_USER_HOME=" + agent.root().resolve("test-caches").resolve(projectId.toString()).resolve("gradle"))
        .doesNotContain("MAVEN_OPTS=");
  }
}
