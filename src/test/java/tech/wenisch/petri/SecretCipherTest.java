package tech.wenisch.petri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;
import tech.wenisch.petri.config.PetriProperties;
import tech.wenisch.petri.secret.SecretCipher;

class SecretCipherTest {
  private static final String MASTER_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

  @Test
  void encryptsSecretsWithRandomAuthenticatedCiphertext() {
    SecretCipher cipher = cipher(MASTER_KEY);

    String first = cipher.encrypt("git-access-token");
    String second = cipher.encrypt("git-access-token");

    assertThat(first).startsWith("v1:").doesNotContain("git-access-token");
    assertThat(second).isNotEqualTo(first);
    assertThat(cipher.decrypt(first)).isEqualTo("git-access-token");
  }

  @Test
  void refusesMissingOrInvalidMasterKeysAndTamperedCiphertext() {
    assertThatIllegalStateException().isThrownBy(() -> cipher(null).encrypt("value"));
    assertThatIllegalStateException().isThrownBy(() -> cipher("not-base64").encrypt("value"));
    assertThatIllegalStateException().isThrownBy(() -> cipher("AQID").encrypt("value"));
    assertThatIllegalArgumentException().isThrownBy(() -> cipher(MASTER_KEY).decrypt("v1:bad:value"));
  }

  private static SecretCipher cipher(String masterKey) {
    return new SecretCipher(new PetriProperties(new PetriProperties.Security(null, null),
        new PetriProperties.Secrets(masterKey)));
  }
}
