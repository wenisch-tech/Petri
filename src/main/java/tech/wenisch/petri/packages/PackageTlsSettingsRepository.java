package tech.wenisch.petri.packages;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PackageTlsSettingsRepository extends JpaRepository<PackageTlsSettings, UUID> {}
