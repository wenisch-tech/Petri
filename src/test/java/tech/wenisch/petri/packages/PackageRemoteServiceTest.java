package tech.wenisch.petri.packages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties =
    "petri.secrets.master-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")
@Transactional
class PackageRemoteServiceTest {
  @Autowired PackageRemoteService service;

  private static PackageRemoteService.Save save(PackageEcosystem ecosystem, String url, String password) {
    return new PackageRemoteService.Save(ecosystem, "Mirror", url, PackageRemoteMode.REPLACE, "bot",
        password, false, null, false, true, 0);
  }

  @Test
  void aProjectsRemotesOfAnEcosystemReplaceTheInstancesAndThePasswordIsEncrypted() {
    UUID project = UUID.randomUUID();
    service.save(null, null, save(PackageEcosystem.NPM, "https://npm.corp/", "secret"), "admin");
    service.save(null, null, save(PackageEcosystem.MAVEN, "https://mvn.corp/", "secret"), "admin");
    service.save(project, null, save(PackageEcosystem.NPM, "https://npm.project/", "other"), "admin");

    var instance = service.effective(null);
    var forProject = service.effective(project);

    assertThat(instance.remotes()).extracting(PackageToolConfig.Remote::url)
        .containsExactlyInAnyOrder("https://npm.corp/", "https://mvn.corp/");
    assertThat(forProject.remotes()).extracting(PackageToolConfig.Remote::url)
        .containsExactlyInAnyOrder("https://mvn.corp/", "https://npm.project/");
    assertThat(forProject.remotes()).anyMatch(value -> "other".equals(value.password()));
    assertThat(service.instanceRemotes()).allMatch(PackageRemote::isPasswordSet)
        .noneMatch(value -> value.passwordCiphertext().contains("secret"));
  }

  @Test
  void urlsWithCredentialsOrOtherSchemesAreRefused() {
    assertThatIllegalArgumentException().isThrownBy(() -> service.save(null, null,
        save(PackageEcosystem.GO, "https://u:p@proxy.corp", null), "admin"));
    assertThatIllegalArgumentException().isThrownBy(() -> service.save(null, null,
        save(PackageEcosystem.GO, "file:///tmp/x", null), "admin"));
  }

  @Test
  void aCaBundleMustBeCertificates() {
    assertThatIllegalArgumentException().isThrownBy(() -> service.saveTls("nope", false, "admin"));
  }
}
