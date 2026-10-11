package tech.wenisch.petri.agent.run;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestSandboxProfileRepository extends JpaRepository<TestSandboxProfile, UUID> {
  List<TestSandboxProfile> findAllByOrderByNameAsc();
  Optional<TestSandboxProfile> findFirstByDefaultProfileTrueAndEnabledTrue();
}
