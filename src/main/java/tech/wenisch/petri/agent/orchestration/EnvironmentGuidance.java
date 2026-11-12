package tech.wenisch.petri.agent.orchestration;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import java.util.UUID;
import tech.wenisch.petri.agent.run.PackageInstallService;
import tech.wenisch.petri.git.ProjectWorktreeService;
import tech.wenisch.petri.kubernetes.KubernetesSettingsService;

/**
 * Tells every agent what runtime it is working in and how package installs work. Registered as a
 * bean so prompts can append the current text without changing their constructors.
 */
@Component
class EnvironmentGuidance {
  private static final AtomicReference<EnvironmentGuidance> CURRENT = new AtomicReference<>();

  private final PackageInstallService installs;
  private final ProjectWorktreeService worktrees;
  private final KubernetesSettingsService kubernetes;

  EnvironmentGuidance(PackageInstallService installs, ProjectWorktreeService worktrees,
      KubernetesSettingsService kubernetes) {
    this.installs = installs;
    this.worktrees = worktrees;
    this.kubernetes = kubernetes;
    CURRENT.set(this);
  }

  /** Guidance text for prompts; empty when no application context has registered one. */
  static String current() {
    EnvironmentGuidance value = CURRENT.get();
    return value == null ? "" : "\n" + value.text();
  }

  /** As {@link #current()}, describing the pod when the worktree's project runs in Kubernetes. */
  static String current(UUID worktreeId) {
    EnvironmentGuidance value = CURRENT.get();
    return value == null ? "" : "\n" + value.text(worktreeId);
  }

  String text(UUID worktreeId) {
    if (worktreeId != null) {
      try {
        UUID projectId = worktrees.get(worktreeId).projectId();
        if (kubernetes.enabled(projectId)) {
          var pod = kubernetes.effective(projectId).pod();
          return podText(pod.image(), pod.toolchain());
        }
      } catch (RuntimeException ignored) {
        // fall back to the general description
      }
    }
    return text();
  }

  private static String podText(String image, tech.wenisch.petri.kubernetes.ToolchainMode toolchain) {
    String text = """
        Runtime environment: this project's workspace runs in a Kubernetes pod started from the
        image %s. The install_packages tool is disabled: only tools that are already in that image
        are available, so do not request package installs. run_command and run_tests execute with
        sh in the worktree inside the pod; use the project's own build wrapper or the tools the
        image provides. Temporary files: $TMPDIR is a directory private to this worktree that
        survives between commands and is deleted with the worktree. Use it for scratch files,
        downloads and generated scripts, never the repository.
        """.formatted(image);
    if (toolchain != null && toolchain.enabled()) {
      text += """
          In addition, git, curl, python3 with pip, go, node and npm come from a bundle that is on
          the PATH, so they exist even if the image does not have them.
          """;
    }
    return text;
  }

  String text() {
    if (!installs.enabled()) {
      return """
          Runtime environment: approved tests run in an isolated sandbox container. The
          install_packages tool is disabled in this deployment; do not request package installs.
          Temporary files: /tmp inside the sandbox is a directory private to this worktree that
          survives between commands and is deleted with the worktree. Use it (or $TMPDIR) for
          scratch files, downloads and generated scripts, never the repository.
          """;
    }
    Set<String> installed = installs.installedPackages();
    return """
        Runtime environment: Petri runs on Alpine Linux (musl libc, busybox userland, apk package
        manager) with JDK 25, Git and the project's Maven/Gradle wrapper. You are unprivileged:
        there is no root, sudo or apt, and you cannot run package managers yourself. To add tools
        needed to implement or test the task call install_packages with Alpine package names (for
        example jq, python3, nodejs, npm, gcc, make) and a short reason; request every package you
        need in one call and prefer the smallest set. An install is a proposal: it runs only after
        the user approves it (unless the project auto-approves) and then applies to later
        run_tests. Never assume a package is available until a result shows it, and do not repeat
        a pending request. Extra packages already installed: %s.
        The run_command tool (implementer and direct runs) executes a shell command in the worktree
        root as an unprivileged user confined to that worktree; it needs user approval unless the
        project auto-approves, and its result carries the exit code and output. Prefer file tools
        for reading and editing files.
        Installed tools are on the PATH of run_command and run_tests. For a Maven or Gradle project
        without a wrapper, install maven or gradle and run_tests uses mvn/gradle directly; a
        wrapper in the worktree takes precedence. If a tool reports "not found" after an install,
        look in the toolchain's usr/bin rather than reinstalling it.
        Temporary files: the system /tmp is not writable for your commands. Use the directory in
        $TMPDIR (also TMP and TEMP): it is private to this worktree, survives between commands and
        is deleted with the worktree. mktemp, Maven and the JVM already use it. Put scratch files,
        downloads and generated scripts there, never inside the repository.
        """.formatted(installed.isEmpty() ? "none" : String.join(", ", installed));
  }
}
