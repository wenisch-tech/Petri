package tech.wenisch.petri.agent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.wenisch.petri.agent.run.TrivyScanService.Resolution;

class DependencyResolverTest {
  private final DependencyResolver resolver =
      new DependencyResolver(mock(LocalToolchain.class), mock(Confinement.class));

  @Test
  void theCopyLeavesOutVersionControlAndBuildOutputButKeepsTheSources(@TempDir Path temp) throws Exception {
    Path source = Files.createDirectories(temp.resolve("source"));
    Files.writeString(source.resolve("pom.xml"), "<project/>");
    Files.createDirectories(source.resolve("src/main"));
    Files.writeString(source.resolve("src/main/A.java"), "class A {}");
    Files.createDirectories(source.resolve(".git"));
    Files.writeString(source.resolve(".git/HEAD"), "ref");
    Files.createDirectories(source.resolve("node_modules/x"));
    Files.writeString(source.resolve("node_modules/x/i.js"), "x");
    Files.createDirectories(source.resolve("target"));
    Files.writeString(source.resolve("target/a.class"), "x");
    Path copy = Files.createDirectories(temp.resolve("copy"));

    assertThat(resolver.copy(source, copy)).isTrue();

    assertThat(copy.resolve("pom.xml")).exists();
    assertThat(copy.resolve("src/main/A.java")).exists();
    assertThat(copy.resolve(".git")).doesNotExist();
    assertThat(copy.resolve("node_modules")).doesNotExist();
    assertThat(copy.resolve("target")).doesNotExist();
  }

  @Test
  void projectsThatAlreadyCarryTheirDependencySetNeedNoResolution(@TempDir Path temp) throws Exception {
    Files.writeString(temp.resolve("package.json"), "{}");
    Files.writeString(temp.resolve("package-lock.json"), "{}");
    Path go = Files.createDirectories(temp.resolve("service"));
    Files.writeString(go.resolve("go.mod"), "module x");
    Path python = Files.createDirectories(temp.resolve("tools"));
    Files.writeString(python.resolve("requirements.txt"), "flask");

    List<Resolution> steps = resolver.resolve(temp, temp.resolve("cache"), temp.resolve("home"));

    assertThat(steps).extracting(Resolution::ecosystem, Resolution::status).contains(
        org.assertj.core.groups.Tuple.tuple("npm", DependencyResolver.NOT_NEEDED),
        org.assertj.core.groups.Tuple.tuple("go", DependencyResolver.NOT_NEEDED),
        org.assertj.core.groups.Tuple.tuple("python", DependencyResolver.SKIPPED));
  }

  @Test
  void aMissingPackageManagerIsReportedInsteadOfBeingGuessed(@TempDir Path temp) throws Exception {
    Files.writeString(temp.resolve("Cargo.toml"), "[package]");
    DependencyResolver noTools = new DependencyResolver(mock(LocalToolchain.class), mock(Confinement.class));

    List<Resolution> steps = noTools.resolve(temp, temp.resolve("cache"), temp.resolve("home"));

    // Either cargo is absent (reported) or present and ran; it is never silently ignored.
    assertThat(steps).anySatisfy(step -> {
      assertThat(step.ecosystem()).isEqualTo("cargo");
      assertThat(step.status()).isIn(DependencyResolver.TOOL_MISSING, DependencyResolver.RESOLVED,
          DependencyResolver.FAILED);
    });
  }
}
