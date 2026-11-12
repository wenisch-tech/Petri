package tech.wenisch.petri.agent.run;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.workspace.WorkspaceHost;
import tech.wenisch.petri.workspace.WorkspaceLayout;

/**
 * Runs approved tests inside the project's Kubernetes pod. The pod is the sandbox: the profile
 * contributes its timeout, output limit, build systems and environment, while image, CPU and memory
 * are the pod's own settings.
 */
@Component
class RemoteWorkspaceTestExecutor {
  private final CommandEnvironment commandEnvironment;
  private final tech.wenisch.petri.packages.PackageEnvironment packages;

  RemoteWorkspaceTestExecutor(
      CommandEnvironment commandEnvironment,
      org.springframework.beans.factory.ObjectProvider<tech.wenisch.petri.packages.PackageEnvironment>
          packages) {
    this.commandEnvironment = commandEnvironment;
    this.packages = packages.getIfAvailable();
  }

  TestCommandResult run(
      WorkspaceHost host, UUID projectId, Path worktree, TestCommand command,
      TestSandboxProfileView profile, Consumer<String> progress) throws IOException {
    if (!profile.buildSystems().contains(command.buildSystem())) {
      throw new IllegalArgumentException("Build system is not allowed by the sandbox profile");
    }
    WorkspaceLayout layout = host.layout();
    Path home = layout.createTemporaryDirectory("petri-test-");
    try {
      Path cache = layout.testCaches(projectId);
      Path scratch = layout.worktreeTemporaryDirectory(worktree);
      Map<String, String> env = new LinkedHashMap<>();
      env.put("HOME", home.toString());
      env.put("CI", "true");
      env.put("LC_ALL", "C.UTF-8");
      env.putAll(commandEnvironment.extra(profile.environment()));
      if (packages != null) env.putAll(packages.remote(projectId, host, home, cache));
      if (command.buildSystem() == TestBuildSystem.MAVEN) {
        env.put("MAVEN_OPTS", "-Dmaven.repo.local=" + cache.resolve("maven"));
      } else {
        env.put("GRADLE_USER_HOME", cache.resolve("gradle").toString());
      }
      env.putAll(LocalToolchain.temporaryDirectoryEnvironment(scratch));
      return host.processes().exec(worktree, command.arguments(), env, profile.timeout(),
          profile.outputLimitBytes(), "kubernetes-pod", progress);
    } finally {
      layout.deleteTemporary(home);
    }
  }
}
