package tech.wenisch.petri.agent.orchestration;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.agent.run.PackageInstallService;

/**
 * Tells every agent what runtime it is working in and how package installs work. Registered as a
 * bean so prompts can append the current text without changing their constructors.
 */
@Component
class EnvironmentGuidance {
  private static final AtomicReference<EnvironmentGuidance> CURRENT = new AtomicReference<>();

  private final PackageInstallService installs;

  EnvironmentGuidance(PackageInstallService installs) {
    this.installs = installs;
    CURRENT.set(this);
  }

  /** Guidance text for prompts; empty when no application context has registered one. */
  static String current() {
    EnvironmentGuidance value = CURRENT.get();
    return value == null ? "" : "\n" + value.text();
  }

  String text() {
    if (!installs.enabled()) {
      return """
          Runtime environment: approved tests run in an isolated sandbox container. The
          install_packages tool is disabled in this deployment; do not request package installs.
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
        """.formatted(installed.isEmpty() ? "none" : String.join(", ", installed));
  }
}
