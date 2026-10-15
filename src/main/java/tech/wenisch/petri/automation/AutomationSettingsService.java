package tech.wenisch.petri.automation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.agent.model.ModelCredentialService;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.git.provider.ProjectProviderSourceService;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectAccessService;

@Service
public class AutomationSettingsService {
  private final ProjectAutomationRepository automations;
  private final ProjectAccessService access;
  private final ProjectProviderSourceService sources;
  private final ModelCredentialService models;
  private final AuditLogService audit;

  public AutomationSettingsService(
      ProjectAutomationRepository automations, ProjectAccessService access,
      ProjectProviderSourceService sources, ModelCredentialService models, AuditLogService audit) {
    this.automations = automations;
    this.access = access;
    this.sources = sources;
    this.models = models;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public List<ProjectAutomation> list(UUID projectId, String actorEmail) {
    access.requireProject(projectId, actorEmail);
    return automations.findAllByProjectIdOrderByCreatedAtAsc(projectId);
  }

  @Transactional(readOnly = true)
  public ProjectAutomation require(UUID projectId, UUID automationId, String actorEmail) {
    access.requireProject(projectId, actorEmail);
    return automations.findById(automationId)
        .filter(value -> value.getProjectId().equals(projectId))
        .orElseThrow(() -> new IllegalArgumentException("Automation not found"));
  }

  /** Why automations cannot be enabled for the project, or null. */
  @Transactional(readOnly = true)
  public String unavailableReason(UUID projectId) {
    return sources.find(projectId) == null
        ? "Import this project from a connected Git account to use automations. "
            + "Projects with plain repository credentials cannot be polled for pull requests."
        : null;
  }

  /**
   * Creates an automation, or updates the one with {@code automationId}. The skill ({@code kind})
   * of an existing automation does not change.
   */
  @Transactional
  public ProjectAutomation save(
      UUID projectId, UUID automationId, AutomationKind kind, boolean enabled,
      AutomationTrigger trigger, String cronExpression, boolean completeWithoutApproval,
      UUID providerInstanceId, String model, int maxModelTurns, String actorEmail) {
    return save(projectId, automationId, kind, enabled, trigger, cronExpression,
        completeWithoutApproval, providerInstanceId, model, maxModelTurns, null, actorEmail);
  }

  /** @param branch the branch a branch-based automation works on; ignored by the others */
  @Transactional
  public ProjectAutomation save(
      UUID projectId, UUID automationId, AutomationKind kind, boolean enabled,
      AutomationTrigger trigger, String cronExpression, boolean completeWithoutApproval,
      UUID providerInstanceId, String model, int maxModelTurns, String branch, String actorEmail) {
    Project project = access.requireProject(projectId, actorEmail);
    ProjectAutomation existing = automationId == null ? null : require(projectId, automationId, actorEmail);
    AutomationKind effectiveKind = existing != null ? existing.getKind() : kind;
    if (effectiveKind == null) throw new IllegalArgumentException("Select a skill");
    if (trigger == null || !effectiveKind.triggers().contains(trigger)) {
      throw new IllegalArgumentException("This skill cannot be started that way");
    }
    if (maxModelTurns < 1 || maxModelTurns > 50) {
      throw new IllegalArgumentException("Maximum model turns must be between 1 and 50");
    }
    String safeBranch = null;
    if (effectiveKind.branchBased()) {
      safeBranch = branch == null || branch.isBlank() ? project.getDefaultBranch() : branch.strip();
      if (safeBranch == null || safeBranch.length() > 255
          || !safeBranch.matches("[A-Za-z0-9._/\\-]+") || safeBranch.contains("..")) {
        throw new IllegalArgumentException("Choose the branch the fixes are opened against");
      }
    }
    String cron = trigger == AutomationTrigger.CRON ? normalizeCron(cronExpression) : null;
    String safeModel = model == null || model.isBlank() ? null : model.strip();
    if (safeModel != null && safeModel.length() > 200) {
      throw new IllegalArgumentException("Model name is too long");
    }
    if (enabled && sources.find(projectId) == null) {
      throw new IllegalArgumentException(unavailableReason(projectId));
    }
    if (providerInstanceId != null
        && models.usable(project.getCreatedBy()).stream()
            .noneMatch(value -> value.id().equals(providerInstanceId))) {
      throw new IllegalArgumentException("The selected model provider is not usable");
    }
    final String selectedBranch = safeBranch;
    automations.findByProjectIdAndKindAndTrigger(projectId, effectiveKind, trigger)
        .filter(other -> existing == null || !other.getId().equals(existing.getId()))
        .ifPresent(other -> {
          throw new IllegalArgumentException(
              "This project already has a " + effectiveKind.title().toLowerCase()
                  + " automation with that trigger; edit it instead");
        });
    Instant now = Instant.now();
    ProjectAutomation automation = existing != null ? existing
        : new ProjectAutomation(UUID.randomUUID(), projectId, effectiveKind, now);
    AutomationPostMode postMode = completeWithoutApproval
        ? AutomationPostMode.AUTO : AutomationPostMode.DRAFT;
    automation.update(enabled, trigger, cron, postMode, providerInstanceId, safeModel,
        maxModelTurns, now);
    automation.branch(selectedBranch);
    automations.save(automation);
    audit.record(actorEmail, "PROJECT_AUTOMATION_SAVED", "PROJECT", projectId,
        effectiveKind.title() + (enabled ? " enabled (" + trigger + ", "
            + (completeWithoutApproval ? "no approval" : "approval required") + ")" : " disabled")
            + " for project " + project.getName());
    return automation;
  }

  @Transactional
  public void delete(UUID projectId, UUID automationId, String actorEmail) {
    ProjectAutomation automation = require(projectId, automationId, actorEmail);
    automations.delete(automation);
    audit.record(actorEmail, "PROJECT_AUTOMATION_DELETED", "PROJECT", projectId,
        automation.getKind().title() + " automation (" + automation.getTrigger() + ") removed");
  }

  /** Accepts the usual five fields and Spring's six; returns the six-field form. */
  public static String normalizeCron(String expression) {
    if (expression == null || expression.isBlank()) {
      throw new IllegalArgumentException("A cron expression is required for scheduled automations");
    }
    String value = expression.strip().replaceAll("\\s+", " ");
    if (value.split(" ").length == 5) value = "0 " + value;
    try {
      CronExpression.parse(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Invalid cron expression: " + exception.getMessage());
    }
    return value;
  }

  public static Instant nextFire(String cronExpression, Instant after) {
    if (cronExpression == null) return null;
    try {
      var next = CronExpression.parse(cronExpression)
          .next(after.atZone(java.time.ZoneOffset.UTC));
      return next == null ? null : next.toInstant();
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  @Transactional
  public void markPolled(UUID automationId, Instant now) {
    automations.findById(automationId).ifPresent(value -> value.polled(now));
  }

  @Transactional
  public void markCronFired(UUID automationId, Instant now) {
    automations.findById(automationId).ifPresent(value -> value.cronFired(now));
  }
}
