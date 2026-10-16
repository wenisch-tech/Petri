package tech.wenisch.petri.packages;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the remotes that apply to a run into what each package manager reads: files under the
 * run's home directory and environment variables. Pure rendering; nothing here talks to the
 * network or the database. Credentials end up in files (0600) in the throw-away home, never in
 * command lines.
 */
public final class PackageToolConfig {
  /** Where tool files are looked for by the tools that read them (differs inside a container). */
  static final String FILES_DIRECTORY = ".petri";
  /** Certificates insecure Go proxies present, written before rendering (see PackageEnvironment). */
  static final String PRESENTED_CERTIFICATES = "go-presented-ca.pem";

  private PackageToolConfig() {}

  /** A remote with its password decrypted. */
  public record Remote(
      PackageEcosystem ecosystem, String url, PackageRemoteMode mode, String username,
      String password, String mirrorOf, boolean insecure) {
    boolean hasCredentials() {
      return username != null && !username.isBlank() && password != null;
    }
  }

  /** Trust material shared by all runs; paths are prepared by {@link PackageTrustStore}. */
  public record Trust(Path pemBundle, Path truststore, String truststorePassword,
      boolean ignoreTlsErrors) {
    public static final Trust NONE = new Trust(null, null, null, false);

    /** The same trust as seen from inside a container that mounts the PKI directory. */
    public Trust relocated(String directory) {
      return new Trust(
          pemBundle == null ? null : Path.of(directory, pemBundle.getFileName().toString()),
          truststore == null ? null : Path.of(directory, truststore.getFileName().toString()),
          truststorePassword, ignoreTlsErrors);
    }
  }

  /**
   * @param env variables to add to the command environment
   * @param apkRepositories repository lines for apk, or null to keep the image's own file
   */
  public record Rendered(Map<String, String> env, List<String> apkRepositories) {}

  /** Files are written under {@code write}; tools are told to find them under {@code reference}. */
  private record Out(Path write, String reference) {
    Path file(String name) { return write.resolve(name); }

    String ref(String name) { return reference + "/" + name; }
  }

  public static Rendered render(List<Remote> remotes, Trust trust, Path home) throws IOException {
    return render(remotes, trust, home, home.toString());
  }

  public static Rendered render(List<Remote> remotes, Trust trust, Path write, String reference)
      throws IOException {
    Out out = new Out(write, reference);
    Map<String, String> env = new LinkedHashMap<>();
    trust(env, trust);
    maven(remotes, trust, out, env);
    gradle(remotes, out);
    npm(remotes, trust, out, env);
    pypi(remotes, trust, env);
    go(remotes, trust, out, env);
    cargo(remotes, out);
    composer(remotes, trust, out, env);
    rubygems(remotes, trust, out, env);
    nuget(remotes, out);
    containers(remotes, trust, out, env);
    return new Rendered(env, apk(remotes));
  }

  // ---- trust -------------------------------------------------------------------------------

  private static void trust(Map<String, String> env, Trust trust) {
    if (trust.pemBundle() != null) {
      String pem = trust.pemBundle().toString();
      for (String name : List.of("SSL_CERT_FILE", "NODE_EXTRA_CA_CERTS", "REQUESTS_CA_BUNDLE",
          "PIP_CERT", "GIT_SSL_CAINFO", "CURL_CA_BUNDLE", "CARGO_HTTP_CAINFO",
          "BUNDLE_SSL_CA_CERT")) {
        env.put(name, pem);
      }
    }
    if (trust.ignoreTlsErrors()) {
      env.put("GIT_SSL_NO_VERIFY", "true");
      env.put("NODE_TLS_REJECT_UNAUTHORIZED", "0");
      env.put("PYTHONHTTPSVERIFY", "0");
      env.put("TRIVY_INSECURE", "true");
      env.put("BUNDLE_SSL_VERIFY_MODE", "0");
    }
  }

  // ---- apk ---------------------------------------------------------------------------------

  /** Repository lines for apk ({@code @default} stands for the image's own), or null. */
  public static List<String> apk(List<Remote> remotes) {
    List<Remote> mine = of(remotes, PackageEcosystem.APK);
    if (mine.isEmpty()) return null;
    List<String> lines = new ArrayList<>();
    boolean replace = mine.stream().anyMatch(value -> value.mode() == PackageRemoteMode.REPLACE);
    if (!replace) lines.add("@default");
    for (Remote remote : mine) lines.add(withUserInfo(trimSlash(remote.url()), remote));
    return lines;
  }

  // ---- Maven -------------------------------------------------------------------------------

  private static void maven(List<Remote> remotes, Trust trust, Out out, Map<String, String> env)
      throws IOException {
    List<Remote> mine = of(remotes, PackageEcosystem.MAVEN);
    List<String> arguments = new ArrayList<>();
    if (trust.truststore() != null) {
      arguments.add("-Djavax.net.ssl.trustStore=" + trust.truststore());
      arguments.add("-Djavax.net.ssl.trustStorePassword=" + trust.truststorePassword());
      arguments.add("-Djavax.net.ssl.trustStoreType=PKCS12");
    }
    boolean insecure = trust.ignoreTlsErrors() || mine.stream().anyMatch(Remote::insecure);
    if (insecure) {
      arguments.add("-Dmaven.wagon.http.ssl.insecure=true");
      arguments.add("-Dmaven.wagon.http.ssl.allowall=true");
      arguments.add("-Dmaven.wagon.http.ssl.ignore.validity.dates=true");
    }
    if (!mine.isEmpty()) {
      StringBuilder xml = new StringBuilder("<settings>\n");
      StringBuilder servers = new StringBuilder();
      StringBuilder mirrors = new StringBuilder();
      StringBuilder repositories = new StringBuilder();
      int index = 0;
      for (Remote remote : mine) {
        String id = "petri-" + index++;
        if (remote.hasCredentials()) {
          servers.append("    <server><id>").append(id).append("</id><username>")
              .append(escape(remote.username())).append("</username><password>")
              .append(escape(remote.password())).append("</password></server>\n");
        }
        if (remote.mode() == PackageRemoteMode.REPLACE) {
          String mirrorOf = remote.mirrorOf() == null || remote.mirrorOf().isBlank()
              ? "*" : remote.mirrorOf();
          mirrors.append("    <mirror><id>").append(id).append("</id><mirrorOf>")
              .append(escape(mirrorOf)).append("</mirrorOf><url>").append(escape(remote.url()))
              .append("</url></mirror>\n");
        } else {
          repositories.append("        <repository><id>").append(id).append("</id><url>")
              .append(escape(remote.url())).append("</url></repository>\n");
        }
      }
      if (!servers.isEmpty()) xml.append("  <servers>\n").append(servers).append("  </servers>\n");
      if (!mirrors.isEmpty()) xml.append("  <mirrors>\n").append(mirrors).append("  </mirrors>\n");
      if (!repositories.isEmpty()) {
        xml.append("  <profiles>\n    <profile>\n      <id>petri-remotes</id>\n"
            + "      <activation><activeByDefault>true</activeByDefault></activation>\n"
            + "      <repositories>\n").append(repositories)
            .append("      </repositories>\n    </profile>\n  </profiles>\n");
      }
      xml.append("</settings>\n");
      write(out.file(".m2/settings.xml"), xml.toString());
      arguments.add("-s");
      arguments.add(out.ref(".m2/settings.xml"));
    }
    if (!arguments.isEmpty()) env.put("MAVEN_ARGS", String.join(" ", arguments));
  }

  // ---- Gradle ------------------------------------------------------------------------------

  /**
   * An init script that is placed in {@code GRADLE_USER_HOME/init.d} by {@code PackageEnvironment}
   * (Gradle reads no environment variable for repositories).
   */
  private static void gradle(List<Remote> remotes, Out out) throws IOException {
    List<Remote> mine = of(remotes, PackageEcosystem.GRADLE);
    if (mine.isEmpty()) return;
    boolean replace = mine.stream().anyMatch(value -> value.mode() == PackageRemoteMode.REPLACE);
    StringBuilder add = new StringBuilder();
    StringBuilder keep = new StringBuilder("'petri-none'");
    int index = 0;
    for (Remote remote : mine) {
      String name = "petri-" + index++;
      keep.append(", '").append(name).append('\'');
      add.append("  handler.maven {\n    name = '").append(name).append("'\n    url = uri('")
          .append(groovy(remote.url())).append("')\n");
      if (remote.url().startsWith("http://")) add.append("    allowInsecureProtocol = true\n");
      if (remote.hasCredentials()) {
        add.append("    credentials {\n      username = '").append(groovy(remote.username()))
            .append("'\n      password = '").append(groovy(remote.password())).append("'\n    }\n");
      }
      add.append("  }\n");
    }
    StringBuilder script = new StringBuilder("// Generated by Petri. Do not edit.\n");
    script.append("def petriNames = [").append(keep).append("]\n");
    script.append("def petriAdd = { handler ->\n");
    if (replace) {
      script.append("  handler.all { repo ->\n    if (!(repo.name in petriNames)) handler.remove(repo)\n  }\n");
    }
    script.append(add).append("}\n");
    script.append("allprojects {\n  petriAdd(repositories)\n  buildscript { petriAdd(repositories) }\n}\n");
    script.append("beforeSettings { settings ->\n  petriAdd(settings.pluginManagement.repositories)\n"
        + "  try { petriAdd(settings.dependencyResolutionManagement.repositories) } catch (Throwable ignored) {}\n}\n");
    write(out.file(FILES_DIRECTORY + "/gradle-remotes.gradle"), script.toString());
  }

  // ---- npm ---------------------------------------------------------------------------------

  private static void npm(List<Remote> remotes, Trust trust, Out out, Map<String, String> env)
      throws IOException {
    List<Remote> mine = of(remotes, PackageEcosystem.NPM);
    boolean insecure = trust.ignoreTlsErrors() || mine.stream().anyMatch(Remote::insecure);
    if (mine.isEmpty() && !insecure) return;
    StringBuilder rc = new StringBuilder();
    Remote first = mine.isEmpty() ? null : mine.get(0);
    if (first != null) rc.append("registry=").append(trimSlash(first.url())).append("/\n");
    for (Remote remote : mine) {
      if (!remote.hasCredentials()) continue;
      String key = "//" + URI.create(remote.url()).getAuthority() + pathOf(remote.url()) + "/";
      rc.append(key).append(":username=").append(remote.username()).append('\n');
      rc.append(key).append(":_password=").append(Base64.getEncoder().encodeToString(
          remote.password().getBytes(StandardCharsets.UTF_8))).append('\n');
    }
    if (trust.pemBundle() != null) rc.append("cafile=").append(trust.pemBundle()).append('\n');
    if (insecure) rc.append("strict-ssl=false\n");
    write(out.file(".npmrc"), rc.toString());
    env.put("npm_config_userconfig", out.ref(".npmrc"));
  }

  // ---- Python ------------------------------------------------------------------------------

  private static void pypi(List<Remote> remotes, Trust trust, Map<String, String> env) {
    List<Remote> mine = of(remotes, PackageEcosystem.PYPI);
    if (mine.isEmpty()) return;
    List<String> replace = new ArrayList<>();
    List<String> add = new ArrayList<>();
    Set<String> trusted = new LinkedHashSet<>();
    for (Remote remote : mine) {
      String url = withUserInfo(remote.url(), remote);
      (remote.mode() == PackageRemoteMode.REPLACE ? replace : add).add(url);
      if (remote.insecure() || trust.ignoreTlsErrors()) {
        trusted.add(URI.create(remote.url()).getHost());
      }
    }
    if (!replace.isEmpty()) {
      env.put("PIP_INDEX_URL", replace.get(0));
      env.put("UV_INDEX_URL", replace.get(0));
      replace.subList(1, replace.size()).forEach(add::add);
    }
    if (!add.isEmpty()) {
      env.put("PIP_EXTRA_INDEX_URL", String.join(" ", add));
      env.put("UV_EXTRA_INDEX_URL", String.join(" ", add));
    }
    if (!trusted.isEmpty()) env.put("PIP_TRUSTED_HOST", String.join(" ", trusted));
  }

  // ---- Go ----------------------------------------------------------------------------------

  private static void go(List<Remote> remotes, Trust trust, Out out, Map<String, String> env)
      throws IOException {
    List<Remote> mine = of(remotes, PackageEcosystem.GO);
    if (mine.isEmpty()) return;
    List<String> proxies = new ArrayList<>();
    StringBuilder netrc = new StringBuilder();
    boolean insecure = false;
    boolean keepDirect = true;
    for (Remote remote : mine) {
      proxies.add(trimSlash(remote.url()));
      if (remote.mode() == PackageRemoteMode.REPLACE) keepDirect = false;
      URI uri = URI.create(remote.url());
      if (remote.hasCredentials()) {
        netrc.append("machine ").append(uri.getHost()).append(" login ").append(remote.username())
            .append(" password ").append(remote.password()).append('\n');
      }
      if (remote.insecure() || trust.ignoreTlsErrors()) insecure = true;
    }
    if (keepDirect) proxies.add("direct");
    env.put("GOPROXY", String.join(",", proxies));
    if (!netrc.isEmpty()) {
      write(out.file(".netrc"), netrc.toString());
      env.put("NETRC", out.ref(".netrc"));
    }
    // Go cannot skip certificate verification for a proxy (GOINSECURE matches module paths, not
    // proxy hosts), so the certificates the proxy presents are trusted explicitly. They were
    // fetched by PackageEnvironment; Go still reads the system roots next to SSL_CERT_FILE.
    Path presented = out.file(PRESENTED_CERTIFICATES);
    if (insecure && Files.isRegularFile(presented)) {
      StringBuilder bundle = new StringBuilder();
      if (trust.pemBundle() != null && Files.isRegularFile(trust.pemBundle())) {
        bundle.append(Files.readString(trust.pemBundle())).append('\n');
      }
      bundle.append(Files.readString(presented));
      write(out.file("go-ca.pem"), bundle.toString());
      env.put("SSL_CERT_FILE", out.ref("go-ca.pem"));
    }
  }

  // ---- Cargo -------------------------------------------------------------------------------

  /** Placed in {@code CARGO_HOME/config.toml} by {@code PackageEnvironment}. */
  private static void cargo(List<Remote> remotes, Out out) throws IOException {
    List<Remote> mine = of(remotes, PackageEcosystem.CARGO);
    if (mine.isEmpty()) return;
    Remote remote = mine.get(0);
    String url = remote.url().startsWith("sparse+") || remote.url().startsWith("registry+")
        ? remote.url() : "sparse+" + trimSlash(remote.url()) + "/";
    StringBuilder toml = new StringBuilder("# Generated by Petri. Do not edit.\n");
    toml.append("[source.crates-io]\nreplace-with = \"petri\"\n\n");
    toml.append("[source.petri]\nregistry = \"").append(toml(url)).append("\"\n");
    if (remote.hasCredentials()) {
      toml.append("\n[registries.petri]\nindex = \"").append(toml(url)).append("\"\ntoken = \"Basic ")
          .append(Base64.getEncoder().encodeToString((remote.username() + ":" + remote.password())
              .getBytes(StandardCharsets.UTF_8))).append("\"\n");
    }
    write(out.file(FILES_DIRECTORY + "/cargo-config.toml"), toml.toString());
  }

  // ---- Composer ----------------------------------------------------------------------------

  private static void composer(List<Remote> remotes, Trust trust, Out out, Map<String, String> env)
      throws IOException {
    List<Remote> mine = of(remotes, PackageEcosystem.COMPOSER);
    if (mine.isEmpty()) return;
    StringBuilder repositories = new StringBuilder();
    StringBuilder auth = new StringBuilder();
    boolean replace = false;
    boolean insecure = trust.ignoreTlsErrors();
    for (Remote remote : mine) {
      if (repositories.length() > 0) repositories.append(", ");
      repositories.append("{\"type\": \"composer\", \"url\": \"").append(json(remote.url())).append("\"}");
      if (remote.mode() == PackageRemoteMode.REPLACE) replace = true;
      if (remote.insecure()) insecure = true;
      if (remote.hasCredentials()) {
        if (auth.length() > 0) auth.append(", ");
        auth.append('"').append(json(URI.create(remote.url()).getHost())).append("\": {\"username\": \"")
            .append(json(remote.username())).append("\", \"password\": \"")
            .append(json(remote.password())).append("\"}");
      }
    }
    if (replace) repositories.append(", {\"packagist.org\": false}");
    StringBuilder config = new StringBuilder("{\n  \"repositories\": [").append(repositories).append("]");
    config.append(",\n  \"config\": {");
    List<String> settings = new ArrayList<>();
    if (trust.pemBundle() != null) settings.add("\"cafile\": \"" + json(trust.pemBundle().toString()) + "\"");
    if (insecure) settings.add("\"disable-tls\": true");
    if (mine.stream().anyMatch(value -> value.url().startsWith("http://"))) {
      settings.add("\"secure-http\": false");
    }
    config.append(String.join(", ", settings)).append("}\n}\n");
    write(out.file(FILES_DIRECTORY + "/composer-config.json"), config.toString());
    if (auth.length() > 0) env.put("COMPOSER_AUTH", "{\"http-basic\": {" + auth + "}}");
  }

  // ---- Ruby --------------------------------------------------------------------------------

  private static void rubygems(List<Remote> remotes, Trust trust, Out out, Map<String, String> env)
      throws IOException {
    List<Remote> mine = of(remotes, PackageEcosystem.RUBYGEMS);
    if (mine.isEmpty()) return;
    Remote first = mine.get(0);
    String url = withUserInfo(first.url(), first);
    boolean insecure = trust.ignoreTlsErrors() || mine.stream().anyMatch(Remote::insecure);
    StringBuilder bundle = new StringBuilder("---\n");
    bundle.append("BUNDLE_MIRROR__HTTPS://RUBYGEMS.ORG/: \"").append(url).append("\"\n");
    if (insecure) bundle.append("BUNDLE_SSL_VERIFY_MODE: \"0\"\n");
    write(out.file(".bundle/config"), bundle.toString());
    env.put("BUNDLE_APP_CONFIG", out.ref(".bundle"));
    StringBuilder gemrc = new StringBuilder("---\n:sources:\n");
    for (Remote remote : mine) gemrc.append("  - ").append(withUserInfo(remote.url(), remote)).append('\n');
    if (insecure) gemrc.append(":ssl_verify_mode: 0\n");
    if (trust.pemBundle() != null) gemrc.append(":ssl_ca_cert: ").append(trust.pemBundle()).append('\n');
    write(out.file(".gemrc"), gemrc.toString());
    env.put("GEMRC", out.ref(".gemrc"));
  }

  // ---- NuGet -------------------------------------------------------------------------------

  private static void nuget(List<Remote> remotes, Out out) throws IOException {
    List<Remote> mine = of(remotes, PackageEcosystem.NUGET);
    if (mine.isEmpty()) return;
    boolean replace = mine.stream().anyMatch(value -> value.mode() == PackageRemoteMode.REPLACE);
    StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<configuration>\n");
    xml.append("  <packageSources>\n");
    if (replace) xml.append("    <clear />\n");
    int index = 0;
    for (Remote remote : mine) {
      xml.append("    <add key=\"petri-").append(index++).append("\" value=\"")
          .append(escape(remote.url())).append('"');
      if (remote.url().startsWith("http://")) xml.append(" allowInsecureConnections=\"true\"");
      xml.append(" />\n");
    }
    xml.append("  </packageSources>\n");
    index = 0;
    StringBuilder credentials = new StringBuilder();
    for (Remote remote : mine) {
      String key = "petri-" + index++;
      if (!remote.hasCredentials()) continue;
      credentials.append("    <").append(key).append(">\n      <add key=\"Username\" value=\"")
          .append(escape(remote.username())).append("\" />\n      <add key=\"ClearTextPassword\" value=\"")
          .append(escape(remote.password())).append("\" />\n    </").append(key).append(">\n");
    }
    if (!credentials.isEmpty()) {
      xml.append("  <packageSourceCredentials>\n").append(credentials)
          .append("  </packageSourceCredentials>\n");
    }
    xml.append("</configuration>\n");
    write(out.file(".nuget/NuGet/NuGet.Config"), xml.toString());
  }

  // ---- Container registries ----------------------------------------------------------------

  /**
   * registries.conf and auth.json for Podman. A replacing remote rewrites the registry's location
   * (every pull of docker.io/x goes to the mirror); an adding one is a mirror tried first.
   */
  private static void containers(List<Remote> remotes, Trust trust, Out out, Map<String, String> env)
      throws IOException {
    StringBuilder conf = new StringBuilder("# Generated by Petri. Do not edit.\n"
        + "unqualified-search-registries = [\"docker.io\"]\n");
    Map<String, String> auths = new LinkedHashMap<>();
    boolean any = false;
    for (PackageEcosystem registry : List.of(PackageEcosystem.DOCKER_HUB, PackageEcosystem.GHCR)) {
      String prefix = registry == PackageEcosystem.GHCR ? "ghcr.io" : "docker.io";
      for (Remote remote : of(remotes, registry)) {
        any = true;
        String location = remote.url().replaceFirst("^https?://", "");
        location = location.endsWith("/") ? location.substring(0, location.length() - 1) : location;
        boolean insecure = remote.insecure() || trust.ignoreTlsErrors()
            || remote.url().startsWith("http://");
        conf.append("\n[[registry]]\nprefix = \"").append(prefix).append("\"\n");
        if (remote.mode() == PackageRemoteMode.REPLACE) {
          conf.append("location = \"").append(toml(location)).append("\"\n");
          if (insecure) conf.append("insecure = true\n");
        } else {
          conf.append("location = \"").append(prefix).append("\"\n\n[[registry.mirror]]\nlocation = \"")
              .append(toml(location)).append("\"\n");
          if (insecure) conf.append("insecure = true\n");
        }
        if (remote.hasCredentials()) {
          auths.put(location.split("/")[0], Base64.getEncoder().encodeToString(
              (remote.username() + ":" + remote.password()).getBytes(StandardCharsets.UTF_8)));
        }
      }
    }
    if (!any) return;
    write(out.file("registries.conf"), conf.toString());
    env.put("CONTAINERS_REGISTRIES_CONF", out.ref("registries.conf"));
    if (!auths.isEmpty()) {
      StringBuilder json = new StringBuilder("{\"auths\": {");
      boolean first = true;
      for (var entry : auths.entrySet()) {
        if (!first) json.append(", ");
        first = false;
        json.append('"').append(json(entry.getKey())).append("\": {\"auth\": \"")
            .append(entry.getValue()).append("\"}");
      }
      json.append("}}\n");
      write(out.file("auth.json"), json.toString());
      env.put("REGISTRY_AUTH_FILE", out.ref("auth.json"));
    }
  }

  // ---- helpers -----------------------------------------------------------------------------

  private static List<Remote> of(List<Remote> remotes, PackageEcosystem ecosystem) {
    return remotes.stream().filter(value -> value.ecosystem() == ecosystem).toList();
  }

  private static String trimSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  private static String pathOf(String url) {
    String path = URI.create(url).getPath();
    return path == null ? "" : trimSlash(path);
  }

  private static String withUserInfo(String url, Remote remote) {
    if (!remote.hasCredentials()) return url;
    String info = URLEncoder.encode(remote.username(), StandardCharsets.UTF_8) + ":"
        + URLEncoder.encode(remote.password(), StandardCharsets.UTF_8);
    return url.substring(0, url.indexOf("://") + 3) + info + "@" + url.substring(url.indexOf("://") + 3);
  }

  private static String escape(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  private static String groovy(String value) {
    return value.replace("\\", "\\\\").replace("'", "\\'").replace("$", "\\$");
  }

  private static String toml(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static String json(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static void write(Path file, String content) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
    try {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    } catch (UnsupportedOperationException ignored) {
      // not a POSIX file system
    }
  }
}
