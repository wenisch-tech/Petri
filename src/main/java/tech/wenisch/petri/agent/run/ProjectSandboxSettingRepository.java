package tech.wenisch.petri.agent.run;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProjectSandboxSettingRepository extends JpaRepository<ProjectSandboxSetting, UUID> {}
