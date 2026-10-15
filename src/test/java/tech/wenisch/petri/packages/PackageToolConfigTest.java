package tech.wenisch.petri.packages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.wenisch.petri.packages.PackageToolConfig.Remote;
import tech.wenisch.petri.packages.PackageToolConfig.Trust;

class PackageToolConfigTest {
  @TempDir Path home;

  private static Remote remote(PackageEcosystem ecosystem, String url, PackageRemoteMode mode,
      String user, String password, boolean insecure) {
    return new Remote(ecosystem, url, mode, user, password, null, insecure);
  }

  @Test
  void mavenGetsAMirrorWithCredentialsInASettingsFile() throws Exception {
    var rendered = PackageToolConfig.render(List.of(remote(PackageEcosystem.MAVEN,
        "https://nexus.test/repository/maven-public/", PackageRemoteMode.REPLACE, "bot", "p&ss", false)),
        Trust.NONE, home);

    String settings = Files.readString(home.resolve(".m2/settings.xml"));
    assertThat(settings).contains("<mirrorOf>*</mirrorOf>", "<username>bot</username>",
        "<password>p&amp;ss</password>");
    assertThat(rendered.env().get("MAVEN_ARGS")).contains("-s " + home.resolve(".m2/settings.xml"));
    assertThat(rendered.env().values()).noneMatch(value -> value.contains("p&ss"));
  }

  @Test
  void npmPypiAndGoUseTheirOwnSettings() throws Exception {
    var rendered = PackageToolConfig.render(List.of(
        remote(PackageEcosystem.NPM, "https://npm.test/repo/", PackageRemoteMode.REPLACE, "u", "pw", false),
        remote(PackageEcosystem.PYPI, "https://pypi.test/simple", PackageRemoteMode.REPLACE, "u", "p w", true),
        remote(PackageEcosystem.GO, "https://goproxy.test", PackageRemoteMode.ADD, null, null, false)),
        Trust.NONE, home);

    assertThat(Files.readString(home.resolve(".npmrc")))
        .contains("registry=https://npm.test/repo/", "//npm.test/repo/:username=u", "_password=");
    assertThat(rendered.env()).containsEntry("npm_config_userconfig", home.resolve(".npmrc").toString());
    assertThat(rendered.env().get("PIP_INDEX_URL")).isEqualTo("https://u:p+w@pypi.test/simple");
    assertThat(rendered.env()).containsEntry("PIP_TRUSTED_HOST", "pypi.test");
    assertThat(rendered.env()).containsEntry("GOPROXY", "https://goproxy.test,direct");
  }

  @Test
  void aReplacingApkRemoteHidesTheDefaultAndAnAddingOneKeepsIt() throws Exception {
    var replaced = PackageToolConfig.render(List.of(remote(PackageEcosystem.APK,
        "https://apk.test/alpine/v3.24/main", PackageRemoteMode.REPLACE, "u", "p", false)),
        Trust.NONE, home);
    var added = PackageToolConfig.render(List.of(remote(PackageEcosystem.APK,
        "https://apk.test/extra", PackageRemoteMode.ADD, null, null, false)), Trust.NONE, home);
    var none = PackageToolConfig.render(List.of(), Trust.NONE, home);

    assertThat(replaced.apkRepositories()).containsExactly("https://u:p@apk.test/alpine/v3.24/main");
    assertThat(added.apkRepositories()).containsExactly("@default", "https://apk.test/extra");
    assertThat(none.apkRepositories()).isNull();
    assertThat(none.env()).isEmpty();
  }

  @Test
  void aCaBundleReachesEveryToolAndIgnoringErrorsSetsTheirFlags() throws Exception {
    Trust trust = new Trust(home.resolve("ca.pem"), home.resolve("ca.p12"), "changeit", true);

    var env = PackageToolConfig.render(List.of(), trust, home).env();

    assertThat(env).containsEntry("SSL_CERT_FILE", home.resolve("ca.pem").toString())
        .containsEntry("NODE_EXTRA_CA_CERTS", home.resolve("ca.pem").toString())
        .containsEntry("GIT_SSL_NO_VERIFY", "true");
    assertThat(env.get("MAVEN_ARGS")).contains("trustStore=" + home.resolve("ca.p12"),
        "maven.wagon.http.ssl.insecure=true");
  }

  @Test
  void aBundleWithoutCertificatesIsRefused() {
    assertThatIllegalArgumentException().isThrownBy(() -> PackageTrustStore.validate("not a cert"));
  }

  @Test
  void gradleCargoComposerRubyAndNugetGetTheirOwnFiles() throws Exception {
    var rendered = PackageToolConfig.render(List.of(
        remote(PackageEcosystem.GRADLE, "https://nexus.test/gradle", PackageRemoteMode.REPLACE, "u", "p'w", false),
        remote(PackageEcosystem.CARGO, "https://crates.test/index/", PackageRemoteMode.REPLACE, null, null, false),
        remote(PackageEcosystem.COMPOSER, "https://composer.test", PackageRemoteMode.REPLACE, "u", "pw", false),
        remote(PackageEcosystem.RUBYGEMS, "https://gems.test", PackageRemoteMode.REPLACE, null, null, true),
        remote(PackageEcosystem.NUGET, "https://nuget.test/v3/index.json", PackageRemoteMode.REPLACE, "u", "pw", false)),
        Trust.NONE, home);

    assertThat(Files.readString(home.resolve(".petri/gradle-remotes.gradle")))
        .contains("url = uri('https://nexus.test/gradle')", "password = 'p\\'w'", "handler.remove(repo)");
    assertThat(Files.readString(home.resolve(".petri/cargo-config.toml")))
        .contains("replace-with = \"petri\"", "sparse+https://crates.test/index/");
    assertThat(Files.readString(home.resolve(".petri/composer-config.json")))
        .contains("\"packagist.org\": false", "https://composer.test");
    assertThat(rendered.env().get("COMPOSER_AUTH")).contains("composer.test", "\"password\": \"pw\"");
    assertThat(Files.readString(home.resolve(".bundle/config"))).contains("https://gems.test", "SSL_VERIFY_MODE");
    assertThat(rendered.env()).containsKey("GEMRC").containsKey("BUNDLE_APP_CONFIG");
    assertThat(Files.readString(home.resolve(".nuget/NuGet/NuGet.Config")))
        .contains("<clear />", "https://nuget.test/v3/index.json", "ClearTextPassword");
  }

  @Test
  void dockerHubAndGhcrAreRewrittenOrMirroredForPodman() throws Exception {
    var rendered = PackageToolConfig.render(List.of(
        remote(PackageEcosystem.DOCKER_HUB, "https://registry.corp/dockerhub", PackageRemoteMode.REPLACE, "u", "pw", false),
        remote(PackageEcosystem.GHCR, "https://ghcr-mirror.corp", PackageRemoteMode.ADD, null, null, true)),
        Trust.NONE, home, "/petri-pkg");

    String conf = Files.readString(home.resolve("registries.conf"));
    assertThat(conf).contains("prefix = \"docker.io\"", "location = \"registry.corp/dockerhub\"",
        "prefix = \"ghcr.io\"", "[[registry.mirror]]", "location = \"ghcr-mirror.corp\"", "insecure = true");
    assertThat(Files.readString(home.resolve("auth.json"))).contains("registry.corp");
    assertThat(rendered.env()).containsEntry("CONTAINERS_REGISTRIES_CONF", "/petri-pkg/registries.conf")
        .containsEntry("REGISTRY_AUTH_FILE", "/petri-pkg/auth.json");
  }
}
