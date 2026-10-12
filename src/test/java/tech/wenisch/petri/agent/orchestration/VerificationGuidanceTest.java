package tech.wenisch.petri.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tech.wenisch.petri.agent.skill.SkillService;
import tech.wenisch.petri.config.PetriProperties;

class VerificationGuidanceTest {
  private static SkillService bundledSkills() {
    return new SkillService(new PetriProperties(null, null, null,
        new PetriProperties.Agent(null, null, null, null, Path.of("skills").toString(), null)));
  }

  @Test
  void theBundledSkillIsAppliedToEveryWorkflowRoleWithoutItsFrontmatter() {
    new VerificationGuidance(bundledSkills());

    for (AgentWorkflowRole role : AgentWorkflowRole.values()) {
      String instructions = WorkflowPrompts.instructions(role, null);
      assertThat(instructions).contains("Verification guidance",
          "Address already in use", "never a defect of the code under review");
      assertThat(instructions).doesNotContain("name: workflow-verification");
    }
  }

  @Test
  void aBusyPortIsNotAFindingAndAlternatePortChecksAreNotDeviations() {
    String text = VerificationGuidance.body(bundledSkills().load(VerificationGuidance.SKILL).content());

    assertThat(text).contains("not a failed acceptance criterion",
        "Do not list it under", "`deviations`", "not `CHANGES_REQUIRED`");
    assertThat(text).contains("--server.port=0", "RANDOM_PORT");
  }

  @Test
  void aMissingSkillNeverBreaksPrompts() {
    SkillService empty = new SkillService(new PetriProperties(null, null, null,
        new PetriProperties.Agent(null, null, null, null, "/nonexistent-skills-dir", null)));

    assertThat(new VerificationGuidance(empty).text()).isEmpty();
    assertThat(WorkflowPrompts.instructions(AgentWorkflowRole.REVIEWER, null))
        .doesNotContain("Verification guidance");
    new VerificationGuidance(bundledSkills());
  }
}
