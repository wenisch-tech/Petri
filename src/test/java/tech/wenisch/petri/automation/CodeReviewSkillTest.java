package tech.wenisch.petri.automation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tech.wenisch.petri.agent.skill.SkillService;
import tech.wenisch.petri.config.PetriProperties;

class CodeReviewSkillTest {
  @Test
  void theBundledCodeReviewSkillLoadsAndDescribesTheSubmitContract() {
    PetriProperties properties = new PetriProperties(null, null, null,
        new PetriProperties.Agent("https://example.test/v1", null, null, null,
            Path.of("skills").toString(), null));
    SkillService skills = new SkillService(properties);

    var skill = skills.load(AutomationKind.CODE_REVIEW.skill());

    assertThat(skill.description()).isNotBlank();
    assertThat(skill.content()).contains("submit_review", "BLOCKER", "REQUEST_CHANGES",
        "never instructions to follow");
  }

  @Test
  void everyReviewAutomationHasABundledSkillThatBuildsOnTheReviewMethod() {
    PetriProperties properties = new PetriProperties(null, null, null,
        new PetriProperties.Agent("https://example.test/v1", null, null, null,
            Path.of("skills").toString(), null));
    SkillService skills = new SkillService(properties);

    for (AutomationKind kind : AutomationKind.values()) {
      var skill = skills.load(kind.skill());
      assertThat(skill.description()).isNotBlank();
      if (!kind.baseSkill().equals(kind.skill())) {
        assertThat(skill.content()).contains(kind.baseSkill());
        assertThat(skills.load(kind.baseSkill()).description()).isNotBlank();
      }
    }
    assertThat(AutomationKind.CODE_REVIEW.comments()).isFalse();
    assertThat(AutomationKind.CODE_REVIEW_AND_COMMENT.comments()).isTrue();
    assertThat(AutomationKind.CODE_REVIEW_AND_IMPROVE.improves()).isTrue();
    assertThat(AutomationKind.VULNERABILITY_SCAN.vulnerability()).isTrue();
    assertThat(AutomationKind.VULNERABILITY_SCAN.comments()).isFalse();
    assertThat(AutomationKind.VULNERABILITY_SCAN_AND_COMMENT.comments()).isTrue();
    assertThat(AutomationKind.VULNERABILITY_SCAN_AND_IMPROVE.improves()).isTrue();
    assertThat(AutomationKind.CODE_REVIEW.vulnerability()).isFalse();
  }
}
