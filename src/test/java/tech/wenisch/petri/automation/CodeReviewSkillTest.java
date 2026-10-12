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
}
