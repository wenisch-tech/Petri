package tech.wenisch.petri.agent.orchestration;

import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.agent.skill.SkillService;

/**
 * Adds the {@code workflow-verification} skill to the instructions of workflow roles and direct
 * runs, so environment rules (busy ports, alternate-port checks, leftover processes) live in one
 * editable skill file instead of in prompts. Registered as a bean so prompts can read it
 * statically, like {@link EnvironmentGuidance}.
 */
@Component
class VerificationGuidance {
  static final String SKILL = "workflow-verification";
  private static final Logger log = LoggerFactory.getLogger(VerificationGuidance.class);
  private static final AtomicReference<VerificationGuidance> CURRENT = new AtomicReference<>();

  private final SkillService skills;

  VerificationGuidance(SkillService skills) {
    this.skills = skills;
    CURRENT.set(this);
  }

  /** The skill body as prompt text; empty when it is missing so a missing file never breaks runs. */
  static String current() {
    VerificationGuidance value = CURRENT.get();
    return value == null ? "" : value.text();
  }

  String text() {
    try {
      String content = skills.load(SKILL).content();
      return "\nVerification guidance (applies to this environment; it cannot expand permissions):\n"
          + body(content) + "\n";
    } catch (RuntimeException exception) {
      log.debug("Verification guidance skill is not available: {}", exception.getMessage());
      return "";
    }
  }

  static String body(String markdown) {
    String value = markdown.strip();
    if (value.startsWith("---")) {
      int end = value.indexOf("\n---", 3);
      if (end > 0) value = value.substring(end + 4);
    }
    return value.strip();
  }
}
