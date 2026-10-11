package tech.wenisch.petri.agent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PackageNamePolicyTest {
  @Test
  void acceptsPlainAlpineNamesAndDeduplicates() {
    assertThat(PackageNamePolicy.validate(List.of("jq", "python3", "py3-pip", "g++", "jq")))
        .containsExactly("jq", "python3", "py3-pip", "g++");
  }

  @Test
  void rejectsFlagsUrlsPathsVersionsAndUppercase() {
    for (String bad : List.of("--allow-untrusted", "-X", "http://evil/x", "../x", "a b", "Jq",
        "jq=1.7", "", " ", "a;b", "a$(b)")) {
      assertThatThrownBy(() -> PackageNamePolicy.validate(List.of(bad)))
          .as(bad).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void rejectsEmptyAndTooManyRequests() {
    assertThatThrownBy(() -> PackageNamePolicy.validate(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    List<String> many = IntStream.range(0, 21).mapToObj(index -> "pkg" + index).toList();
    assertThatThrownBy(() -> PackageNamePolicy.validate(many))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
