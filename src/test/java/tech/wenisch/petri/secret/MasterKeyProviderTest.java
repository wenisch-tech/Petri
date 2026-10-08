package tech.wenisch.petri.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.wenisch.petri.config.PetriProperties;

class MasterKeyProviderTest {
  private static final String CONFIGURED_KEY =
      "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

  @TempDir Path temporaryDirectory;

  @Test
  void createsOwnerOnlyKeyAndReusesItAcrossProviderInstances() throws Exception {
    Path keyFile = temporaryDirectory.resolve("secrets/petri-master.key");
    SecretCipher first = cipher(null, keyFile);

    String encrypted = first.encrypt("persistent-secret");

    assertThat(keyFile).isRegularFile();
    assertThat(Base64.getDecoder().decode(Files.readString(keyFile).trim())).hasSize(32);
    if (Files.getFileStore(keyFile).supportsFileAttributeView("posix")) {
      assertThat(Files.getPosixFilePermissions(keyFile)).isEqualTo(EnumSet.of(
          PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }
    SecretCipher restarted = cipher(null, keyFile);
    assertThat(restarted.decrypt(encrypted)).isEqualTo("persistent-secret");
  }

  @Test
  void configuredKeyTakesPrecedenceWithoutCreatingAFile() {
    Path keyFile = temporaryDirectory.resolve("must-not-exist.key");

    SecretCipher cipher = cipher(CONFIGURED_KEY, keyFile);

    assertThat(cipher.decrypt(cipher.encrypt("value"))).isEqualTo("value");
    assertThat(keyFile).doesNotExist();
  }

  @Test
  void refusesInvalidExistingFileInsteadOfReplacingIt() throws Exception {
    Path keyFile = temporaryDirectory.resolve("invalid.key");
    Files.writeString(keyFile, "not-a-valid-key\n");

    assertThatIllegalStateException().isThrownBy(() -> cipher(null, keyFile));
    assertThat(keyFile).hasContent("not-a-valid-key\n");
  }

  @Test
  void refusesSymbolicLinkAsKeyFile() throws Exception {
    Path target = temporaryDirectory.resolve("target.key");
    Files.writeString(target, CONFIGURED_KEY);
    Path keyFile = temporaryDirectory.resolve("linked.key");
    Files.createSymbolicLink(keyFile, target);

    assertThatIllegalStateException().isThrownBy(() -> cipher(null, keyFile));
  }

  private static SecretCipher cipher(String configuredKey, Path keyFile) {
    PetriProperties properties = new PetriProperties(
        null,
        new PetriProperties.Secrets(configuredKey, keyFile.toString()),
        null,
        null);
    return new SecretCipher(new MasterKeyProvider(properties));
  }
}
