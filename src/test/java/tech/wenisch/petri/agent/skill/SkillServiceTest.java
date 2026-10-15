package tech.wenisch.petri.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillServiceTest {
  @TempDir Path temporaryDirectory;

  @Test
  void listsMetadataAndLoadsOnlyTheRequestedSkill() throws Exception {
    writeSkill("z-last", "Last workflow", "# Last\n\nDo the last thing.\n");
    writeSkill("a-first", "First workflow", "# First\n\nDo the first thing.\n");
    SkillService skills = new SkillService(temporaryDirectory, 4096);

    assertThat(skills.list()).containsExactly(
        new SkillSummary("a-first", "First workflow"),
        new SkillSummary("z-last", "Last workflow"));
    SkillDocument loaded = skills.load("a-first");
    assertThat(loaded.name()).isEqualTo("a-first");
    assertThat(loaded.content()).contains("Do the first thing.").doesNotContain("last thing");
  }

  @Test
  void rejectsTraversalSymlinksAndOversizedSkillFiles() throws Exception {
    Path root = Files.createDirectory(temporaryDirectory.resolve("root"));
    SkillService skills = new SkillService(root, 1024);
    assertThatIllegalArgumentException().isThrownBy(() -> skills.load("../outside"));

    Path outside = Files.createDirectory(temporaryDirectory.resolve("outside-skill"));
    Files.writeString(outside.resolve("SKILL.md"), skill("linked", "Linked", "# Linked\n"));
    Files.createSymbolicLink(root.resolve("linked"), outside);
    assertThatIllegalArgumentException().isThrownBy(() -> skills.load("linked"));

    Path large = Files.createDirectory(root.resolve("large"));
    Files.writeString(large.resolve("SKILL.md"), skill("large", "Large", "x".repeat(1100)));
    assertThatIllegalStateException().isThrownBy(() -> skills.load("large"))
        .withMessage("Skill file is too large");
  }

  @Test
  void rejectsInvalidOrSpoofedFrontMatter() throws Exception {
    Path skillDirectory = Files.createDirectory(temporaryDirectory.resolve("expected-name"));
    Files.writeString(
        skillDirectory.resolve("SKILL.md"),
        skill("different-name", "Description", "# Workflow\n"));

    SkillService skills = new SkillService(temporaryDirectory, 4096);

    assertThatIllegalStateException().isThrownBy(skills::list)
        .withMessage("Skill name must match its directory");
  }

  private void writeSkill(String name, String description, String body) throws Exception {
    Path directory = Files.createDirectory(temporaryDirectory.resolve(name));
    Files.writeString(directory.resolve("SKILL.md"), skill(name, description, body));
  }

  private static String skill(String name, String description, String body) {
    return "---\nname: " + name + "\ndescription: " + description + "\n---\n\n" + body;
  }

  @Test
  void readsTheManifestFromTheFrontMatter() throws Exception {
    Path directory = Files.createDirectory(temporaryDirectory.resolve("child"));
    Files.writeString(directory.resolve("SKILL.md"), """
        ---
        name: child
        description: Builds on a base
        extends: base
        context: pull_request
        produces: review
        requires: [read_repo, scan]
        version: 1.2.0
        license: MIT
        ---
        # Child
        """);
    Path other = Files.createDirectory(temporaryDirectory.resolve("listy"));
    Files.writeString(other.resolve("SKILL.md"), """
        ---
        name: listy
        description: Block list
        requires:
          - read_repo
          - write_proposal
        ---
        # Listy
        """);
    SkillService skills = new SkillService(temporaryDirectory, 4096);

    SkillManifest manifest = skills.load("child").manifest();

    assertThat(manifest.extendsSkill()).isEqualTo("base");
    assertThat(manifest.context()).isEqualTo("pull_request");
    assertThat(manifest.produces()).isEqualTo("review");
    assertThat(manifest.requires()).containsExactly("read_repo", "scan");
    assertThat(manifest.version()).isEqualTo("1.2.0");
    assertThat(skills.load("listy").manifest().requires()).containsExactly("read_repo", "write_proposal");
    // Skills without a manifest still load.
    writeSkill("plain", "Plain", "# Plain\n");
    assertThat(skills.load("plain").manifest()).isEqualTo(SkillManifest.EMPTY);
  }

  @Test
  void anInstalledSkillIsFoundButNeverReplacesABundledOne() throws Exception {
    Path installed = Files.createDirectory(temporaryDirectory.resolve("installed"));
    Path bundled = Files.createDirectory(temporaryDirectory.resolve("bundled"));
    writeSkillIn(bundled, "shared", "Bundled version", "# Bundled\n");
    writeSkillIn(installed, "shared", "Installed version", "# Installed\n");
    writeSkillIn(installed, "extra", "Only installed", "# Extra\n");
    SkillService skills = new SkillService(bundled, installed, 4096);

    assertThat(skills.list()).extracting(SkillSummary::name).containsExactly("extra", "shared");
    assertThat(skills.load("shared").description()).isEqualTo("Bundled version");
    assertThat(skills.load("extra").description()).isEqualTo("Only installed");
  }

  @Test
  void aSkillCannotExtendItself() throws Exception {
    Path directory = Files.createDirectory(temporaryDirectory.resolve("loop"));
    Files.writeString(directory.resolve("SKILL.md"),
        "---\nname: loop\ndescription: Loops\nextends: loop\n---\n# Loop\n");
    SkillService skills = new SkillService(temporaryDirectory, 4096);

    assertThatIllegalStateException().isThrownBy(() -> skills.load("loop"))
        .withMessageContaining("cannot extend itself");
  }

  @Test
  void composesTheChainBaseFirstWithoutFrontMatterAndStopsOnCycles() throws Exception {
    writeSkill("method", "The method", "# Method\n\nDo it this way.\n");
    Path child = Files.createDirectory(temporaryDirectory.resolve("variant"));
    Files.writeString(child.resolve("SKILL.md"),
        "---\nname: variant\ndescription: A variant\nextends: method\n---\n# Variant\n\nAlso post it.\n");
    Path a = Files.createDirectory(temporaryDirectory.resolve("cycle-a"));
    Path b = Files.createDirectory(temporaryDirectory.resolve("cycle-b"));
    Files.writeString(a.resolve("SKILL.md"), "---\nname: cycle-a\ndescription: A\nextends: cycle-b\n---\n# A\n");
    Files.writeString(b.resolve("SKILL.md"), "---\nname: cycle-b\ndescription: B\nextends: cycle-a\n---\n# B\n");
    SkillService skills = new SkillService(temporaryDirectory, 4096);

    String composed = SkillComposer.compose(skills, "variant", null);

    assertThat(composed).startsWith("# Method").contains("Do it this way.", "Also post it.")
        .doesNotContain("description:");
    assertThat(composed.indexOf("Do it this way.")).isLessThan(composed.indexOf("Also post it."));
    assertThatIllegalStateException().isThrownBy(() -> SkillComposer.compose(skills, "cycle-a", null))
        .withMessageContaining("extends itself or nests too deeply");
    // A skill that predates the manifest composes with the base it was registered with.
    writeSkill("legacy", "Legacy", "# Legacy\n");
    assertThat(SkillComposer.compose(skills, "legacy", "method")).contains("Do it this way.", "# Legacy");
  }

  private void writeSkillIn(Path root, String name, String description, String body) throws Exception {
    Path directory = Files.createDirectory(root.resolve(name));
    Files.writeString(directory.resolve("SKILL.md"), skill(name, description, body));
  }

  @org.junit.jupiter.api.Test
  void anAdministratorCanInstallAndRemoveAPromptOnlySkillButNotShadowABuiltInOne(
      @org.junit.jupiter.api.io.TempDir Path temp) throws Exception {
    Path bundled = temp.resolve("bundled");
    Files.createDirectories(bundled.resolve("base"));
    Files.writeString(bundled.resolve("base/SKILL.md"),
        "---\nname: base\ndescription: Base\n---\nBase text\n");
    SkillService service = new SkillService(bundled, temp.resolve("installed"), 10_000);

    var installed = service.install(
        "---\nname: mine\ndescription: Mine\nextends: base\n---\nMore\n");

    org.assertj.core.api.Assertions.assertThat(installed.name()).isEqualTo("mine");
    org.assertj.core.api.Assertions.assertThat(service.load("mine").manifest().extendsSkill())
        .isEqualTo("base");
    org.assertj.core.api.Assertions.assertThat(service.listInstalled()).hasSize(1);
    org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
        .isThrownBy(() -> service.install("---\nname: base\ndescription: x\n---\nhi\n"))
        .withMessageContaining("built-in");
    org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
        .isThrownBy(() -> service.install("---\nname: orphan\ndescription: x\nextends: nope\n---\nhi\n"))
        .withMessageContaining("does not exist");
    org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
        .isThrownBy(() -> service.remove("base"));

    service.remove("mine");
    org.assertj.core.api.Assertions.assertThat(service.listInstalled()).isEmpty();
  }
}
