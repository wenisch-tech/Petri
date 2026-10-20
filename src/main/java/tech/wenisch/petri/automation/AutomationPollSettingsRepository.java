package tech.wenisch.petri.automation;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AutomationPollSettingsRepository extends JpaRepository<AutomationPollSettings, UUID> {}
