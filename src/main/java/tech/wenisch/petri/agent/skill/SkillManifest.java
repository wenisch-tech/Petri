package tech.wenisch.petri.agent.skill;

import java.util.List;

/**
 * What a skill declares about itself in its front matter, beyond name and description. All fields
 * are optional, so skills written before the manifest keep working.
 *
 * @param extendsSkill name of the skill whose method this one builds on; the composed text is that
 *     skill first, then this one
 * @param context what the skill works on: {@code pull_request}, {@code branch} or {@code any}
 * @param requires tool capabilities the skill needs, for example {@code scan} or {@code read_repo}
 * @param produces the result the skill ends with: {@code review}, {@code vulnerability_report} or
 *     {@code none}
 */
public record SkillManifest(
    String extendsSkill, String context, List<String> requires, String produces, String version,
    String license) {
  public static final SkillManifest EMPTY = new SkillManifest(null, null, List.of(), null, null, null);

  public SkillManifest {
    requires = requires == null ? List.of() : List.copyOf(requires);
  }
}
