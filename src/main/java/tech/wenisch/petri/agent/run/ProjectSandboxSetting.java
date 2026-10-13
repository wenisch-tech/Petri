package tech.wenisch.petri.agent.run;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "project_sandbox_setting")
class ProjectSandboxSetting {
  @Id @Column(name = "project_id") private UUID projectId;
  @Column(name = "profile_id", nullable = false) private UUID profileId;
  @Column(name = "network_enabled") private Boolean networkEnabled;
  @Column(name = "auto_approve_package_installs", nullable = false) private boolean autoApprovePackageInstalls;
  @Column(name = "auto_approve_terminal_commands", nullable = false) private boolean autoApproveTerminalCommands;
  @Column(name = "environment_ciphertext", columnDefinition = "TEXT") private String environmentCiphertext;
  protected ProjectSandboxSetting() {}
  ProjectSandboxSetting(UUID projectId, UUID profileId, Boolean networkEnabled) {
    this.projectId = projectId;
    this.profileId = profileId;
    this.networkEnabled = networkEnabled;
  }
  void update(UUID profileId, Boolean networkEnabled) {
    this.profileId = profileId;
    this.networkEnabled = networkEnabled;
  }
  void autoApprovePackageInstalls(boolean value) { this.autoApprovePackageInstalls = value; }
  boolean autoApprovePackageInstalls() { return autoApprovePackageInstalls; }
  void autoApproveTerminalCommands(boolean value) { this.autoApproveTerminalCommands = value; }
  boolean autoApproveTerminalCommands() { return autoApproveTerminalCommands; }
  String environmentCiphertext() { return environmentCiphertext; }
  void environmentCiphertext(String value) { this.environmentCiphertext = value; }
  UUID projectId() { return projectId; }
  UUID profileId() { return profileId; }
  Boolean networkEnabled() { return networkEnabled; }
}
