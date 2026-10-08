package tech.wenisch.petri.secret;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.config.PetriProperties;

/** Encrypts persisted service credentials with an externally supplied 256-bit AES key. */
@Service
public class SecretCipher {
  private static final String VERSION = "v1";
  private static final int KEY_BYTES = 32;
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final PetriProperties properties;
  private final SecureRandom random = new SecureRandom();

  public SecretCipher(PetriProperties properties) {
    this.properties = properties;
  }

  public boolean isConfigured() {
    return properties.secrets().isConfigured();
  }

  public String encrypt(String plaintext) {
    if (plaintext == null || plaintext.isEmpty()) {
      throw new IllegalArgumentException("A secret must not be empty");
    }
    byte[] nonce = new byte[NONCE_BYTES];
    random.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
      byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      return VERSION + ":" + encode(nonce) + ":" + encode(encrypted);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Could not encrypt secret", exception);
    }
  }

  public String decrypt(String storedValue) {
    String[] parts = storedValue == null ? new String[0] : storedValue.split(":", -1);
    if (parts.length != 3 || !VERSION.equals(parts[0])) {
      throw new IllegalArgumentException("Unsupported encrypted secret format");
    }
    try {
      byte[] nonce = decode(parts[1]);
      if (nonce.length != NONCE_BYTES) throw new IllegalArgumentException("Invalid encrypted secret nonce");
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
      return new String(cipher.doFinal(decode(parts[2])), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException exception) {
      throw new IllegalArgumentException("Encrypted secret cannot be decrypted", exception);
    }
  }

  private SecretKeySpec key() {
    if (!isConfigured()) {
      throw new IllegalStateException("PETRI_SECRETS_MASTER_KEY must be configured before storing secrets");
    }
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(properties.secrets().masterKey().trim());
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("PETRI_SECRETS_MASTER_KEY must be Base64-encoded", exception);
    }
    if (bytes.length != KEY_BYTES) {
      throw new IllegalStateException("PETRI_SECRETS_MASTER_KEY must contain exactly 32 bytes");
    }
    return new SecretKeySpec(bytes, "AES");
  }

  private static String encode(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private static byte[] decode(String value) {
    return Base64.getUrlDecoder().decode(value);
  }
}
