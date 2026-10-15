package tech.wenisch.petri.automation;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.agent.skill.SkillService;
import tech.wenisch.petri.audit.AuditLogService;

/**
 * Keeps the actions an automation can run in the database. The built-in ones are written at
 * startup so a release can change them; custom ones are rows added by users and are never
 * overwritten. The in-memory catalog that pipelines and pages read is refreshed from the table.
 */
@Service
public class AutomationActionService {
  private static final Logger log = LoggerFactory.getLogger(AutomationActionService.class);
  private static final Pattern ID = Pattern.compile("[A-Z][A-Z0-9_]{2,62}");
  private final AutomationActionRepository actions;
  private final ProjectAutomationRepository automations;
  private final SkillService skills;
  private final AuditLogService audit;

  AutomationActionService(
      AutomationActionRepository actions, ProjectAutomationRepository automations,
      SkillService skills, AuditLogService audit) {
    this.actions = actions;
    this.automations = automations;
    this.skills = skills;
    this.audit = audit;
  }

  /** What a person chooses when defining an action; the engine flags are derived from it. */
  public record CustomAction(
      String title, String description, String skill, boolean branchBased, boolean vulnerability,
      boolean comments, boolean improves, boolean merges) {}

  @Transactional(readOnly = true)
  public List<AutomationAction> list() {
    return actions.findAll().stream()
        .sorted(java.util.Comparator.comparing(AutomationAction::isBuiltin).reversed()
            .thenComparing(AutomationAction::getTitle))
        .toList();
  }

  /** Creates a custom action, or changes one (the id is derived from the title on create). */
  @Transactional
  public AutomationAction saveCustom(String id, CustomAction input, String actorEmail) {
    String title = input.title() == null ? "" : input.title().strip();
    if (title.isEmpty() || title.length() > 120) {
      throw new IllegalArgumentException("Give the action a title of up to 120 characters");
    }
    String description = input.description() == null ? "" : input.description().strip();
    if (description.length() > 1000) {
      throw new IllegalArgumentException("The description is limited to 1000 characters");
    }
    String skill = input.skill() == null ? "" : input.skill().strip();
    try {
      skills.load(skill);
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("The skill '" + skill + "' does not exist");
    }
    boolean branch = input.branchBased();
    boolean vulnerability = branch || input.vulnerability();
    boolean improves = !branch && input.improves();
    boolean merges = branch && input.merges();
    boolean comments = !branch && (input.comments() || improves);
    String base = branch ? AutomationKind.FIX_METHOD_SKILL
        : vulnerability ? AutomationKind.VULNERABILITY_METHOD_SKILL
        : AutomationKind.REVIEW_METHOD_SKILL;
    Set<AutomationTrigger> triggers = branch
        ? EnumSet.of(AutomationTrigger.CRON)
        : EnumSet.of(AutomationTrigger.PULL_REQUEST, AutomationTrigger.CRON);
    Instant now = Instant.now();
    String key = id;
    if (key == null || key.isBlank()) {
      key = title.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
      if (!ID.matcher(key).matches()) {
        throw new IllegalArgumentException("The title needs at least three letters or digits");
      }
      if (actions.existsById(key)) {
        throw new IllegalArgumentException("An action with this name already exists");
      }
    }
    AutomationAction existing = actions.findById(key).orElse(null);
    if (existing != null && existing.isBuiltin()) {
      throw new IllegalArgumentException("Built-in actions cannot be changed");
    }
    AutomationKind kind = new AutomationKind(key, title, description, skill, base, comments,
        improves, branch, merges, vulnerability, triggers, false);
    AutomationAction row = existing == null ? new AutomationAction(kind, now) : existing;
    if (existing != null) row.update(kind, now);
    actions.save(row);
    refresh();
    audit.record(actorEmail, existing == null ? "AUTOMATION_ACTION_CREATED" : "AUTOMATION_ACTION_UPDATED",
        "AUTOMATION_ACTION", targetId(key), key);
    return row;
  }

  /** The audit log addresses targets by UUID; an action's id is a name, so derive a stable one. */
  private static java.util.UUID targetId(String id) {
    return java.util.UUID.nameUUIDFromBytes(("automation-action:" + id).getBytes(
        java.nio.charset.StandardCharsets.UTF_8));
  }

  @Transactional
  public void deleteCustom(String id, String actorEmail) {
    AutomationAction row = actions.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("The action does not exist"));
    if (row.isBuiltin()) throw new IllegalArgumentException("Built-in actions cannot be removed");
    if (automations.findAll().stream().anyMatch(value -> value.getKind().id().equals(id))) {
      throw new IllegalArgumentException(
          "The action is used by an automation. Remove those automations first");
    }
    actions.delete(row);
    refresh();
    audit.record(actorEmail, "AUTOMATION_ACTION_DELETED", "AUTOMATION_ACTION", targetId(id), id);
  }

  @EventListener(ApplicationReadyEvent.class)
  @Transactional
  public void seed() {
    Instant now = Instant.now();
    for (AutomationKind builtin : AutomationKind.builtins()) {
      AutomationAction row = actions.findById(builtin.id()).orElse(null);
      if (row == null) actions.save(new AutomationAction(builtin, now));
      else if (row.isBuiltin()) row.update(builtin, now);
    }
    refresh();
  }

  @Transactional(readOnly = true)
  public void refresh() {
    List<AutomationKind> loaded = actions.findAll().stream().map(AutomationAction::toKind).toList();
    AutomationKind.install(loaded);
    log.debug("Loaded {} automation actions", loaded.size());
  }
}
