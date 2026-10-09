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
}
