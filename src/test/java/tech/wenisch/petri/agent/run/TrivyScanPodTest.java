package tech.wenisch.petri.agent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import tech.wenisch.petri.config.PetriProperties;
import tech.wenisch.petri.kubernetes.RealAgent;
import tech.wenisch.petri.packages.PackageEnvironment;
import tech.wenisch.petri.vulnerability.TrivySettingsService;
import tech.wenisch.petri.workspace.WorkspacePaths;
import tools.jackson.databind.json.JsonMapper;

/**
 * A scan of a project that lives in a pod: the package manager runs in the pod, its lockfile reaches
 * the scanner in Petri, and nothing is left behind in the pod or written to the repository. A stub
 * scanner reports a finding when it sees the lockfile, which only the pod's tool could have made.
 */
class TrivyScanPodTest {
  @TempDir Path workspace;
  @TempDir Path tools;
  @TempDir Path petri;
  private RealAgent agent;

  @AfterEach
  void tearDown() {
    if (agent != null) agent.close();
  }

  private static void script(Path file, String body) throws Exception {
    Files.writeString(file, "#!/bin/sh\n" + body + "\n");
    assertThat(file.toFile().setExecutable(true)).isTrue();
  }

  @SuppressWarnings("unchecked")
  private TrivyScanService service(UUID projectId, Path trivy) throws Exception {
    PackageEnvironment packages = mock(PackageEnvironment.class);
    when(packages.remote(any(), any(), any(), any())).thenReturn(Map.of("npm_config_registry", "https://nexus.test/npm"));
    ObjectProvider<PackageEnvironment> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(packages);
    TestSandboxProfileService profiles = mock(TestSandboxProfileService.class);
    when(profiles.environmentFor(projectId)).thenReturn(Map.of());
    LocalToolchain toolchain = new LocalToolchain(petri.resolve("toolchain").toString(), "apk");
    Confinement confinement = mock(Confinement.class);
    DependencyResolver resolver = new DependencyResolver(
        toolchain, confinement, CommandEnvironment.none(), profiles, provider);
    TrivySettingsService settings = mock(TrivySettingsService.class);
    when(settings.current()).thenReturn(new TrivySettingsService.Config(
        TrivySettingsService.Mode.ONLINE, null, null, null, null, null, false, petri.resolve("none").toString()));
    var paths = new WorkspacePaths(new PetriProperties(null, null, new PetriProperties.Workspace(
        petri.resolve("workspace").toString(), Duration.ofSeconds(5), 4096, 1024, 4096, 2, null, null), null));
    return new TrivyScanService(paths, settings, resolver, toolchain, confinement,
        JsonMapper.builder().build(), trivy.toString());
  }

  @Test
  void theLockfileMadeInThePodReachesTheScannerAndNothingIsLeftBehind() throws Exception {
    script(tools.resolve("npm"), "echo '{\"lock\":true}' > package-lock.json");
    agent = RealAgent.start(workspace, tools);
    UUID projectId = UUID.randomUUID();
    var host = agent.host(projectId);
    Path worktree = Files.createDirectories(
        agent.root().resolve("runs").resolve(projectId.toString()).resolve(UUID.randomUUID().toString()));
    Files.createDirectories(worktree.resolve("web"));
    Files.writeString(worktree.resolve("web/package.json"), "{}");
    Path trivy = tools.resolve("trivy-stub");
    script(trivy, """
        out=""; prev=""
        for a in "$@"; do [ "$prev" = "--output" ] && out="$a"; prev="$a"; last="$a"; done
        if [ -f "$last/web/package-lock.json" ]; then
          echo '{"Trivy":{"Version":"stub"},"Results":[{"Target":"web/package-lock.json","Vulnerabilities":[{"VulnerabilityID":"CVE-1","Severity":"HIGH","PkgName":"left-pad","InstalledVersion":"1.0.0","FixedVersion":"1.1.0"}]}]}' > "$out"
        else
          echo '{"Trivy":{"Version":"stub"},"Results":[]}' > "$out"
        fi
        """);

    var result = service(projectId, trivy).scan(host, worktree, null, List.of("vuln"), List.of("HIGH"), false);

    assertThat(result.total()).as("the scanner saw the lockfile the pod's npm wrote").isEqualTo(1);
    assertThat(result.findings().get(0).id()).isEqualTo("CVE-1");
    assertThat(result.dependencyResolution()).extracting(
        TrivyScanService.Resolution::ecosystem, TrivyScanService.Resolution::status)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("npm", DependencyResolver.RESOLVED));
    assertThat(result.complete()).isTrue();
    assertThat(worktree.resolve("web/package-lock.json")).as("the repository is never written to").doesNotExist();
    try (var leftovers = Files.list(agent.root().resolve("tmp"))) {
      assertThat(leftovers.filter(p -> p.getFileName().toString().startsWith("petri-"))).isEmpty();
    }
  }

  @Test
  void aProjectWithoutResolutionIsStillScannedFromACopy() throws Exception {
    agent = RealAgent.start(workspace, tools);
    UUID projectId = UUID.randomUUID();
    var host = agent.host(projectId);
    Path worktree = Files.createDirectories(
        agent.root().resolve("runs").resolve(projectId.toString()).resolve(UUID.randomUUID().toString()));
    Files.writeString(worktree.resolve("package-lock.json"), "{}");
    Path trivy = tools.resolve("trivy-stub");
    script(trivy, "for a in \"$@\"; do [ \"$p\" = \"--output\" ] && out=\"$a\"; p=\"$a\"; done\n"
        + "echo '{\"Results\":[]}' > \"$out\"");

    var result = service(projectId, trivy).scan(host, worktree, null, List.of("vuln"), List.of("HIGH"), false, false);

    assertThat(result.total()).isZero();
    assertThat(result.dependencyResolution()).isEmpty();
  }
}
