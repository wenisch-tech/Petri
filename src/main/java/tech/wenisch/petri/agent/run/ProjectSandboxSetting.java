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
  UUID projectId() { return projectId; }
  UUID profileId() { return profileId; }
  Boolean networkEnabled() { return networkEnabled; }
}
