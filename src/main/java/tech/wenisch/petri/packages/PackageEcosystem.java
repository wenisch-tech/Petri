package tech.wenisch.petri.packages;

/** The package managers and container registries a remote can be configured for. */
public enum PackageEcosystem {
  APK("Alpine packages (apk)", "https://dl-cdn.alpinelinux.org/alpine/v3.24/main", false),
  MAVEN("Maven", "https://repo.maven.apache.org/maven2", false),
  GRADLE("Gradle (plugins and dependencies)", "https://plugins.gradle.org/m2", false),
  NPM("npm / yarn / pnpm", "https://registry.npmjs.org", false),
  PYPI("Python (pip, uv)", "https://pypi.org/simple", false),
  GO("Go modules", "https://proxy.golang.org", false),
  CARGO("Rust (cargo)", "sparse+https://index.crates.io/", false),
  COMPOSER("PHP (Composer)", "https://repo.packagist.org", false),
  RUBYGEMS("Ruby (gem, bundler)", "https://rubygems.org", false),
  NUGET("NuGet (.NET)", "https://api.nuget.org/v3/index.json", false),
  DOCKER_HUB("Container images from docker.io", "https://registry-1.docker.io", true),
  GHCR("Container images from ghcr.io", "https://ghcr.io", true);

  private final String label;
  private final String example;
  private final boolean registry;

  PackageEcosystem(String label, String example, boolean registry) {
    this.label = label;
    this.example = example;
    this.registry = registry;
  }

  public String getLabel() { return label; }

  /** The public default, shown as a hint. */
  public String getExample() { return example; }

  /** A container registry (used by Podman and Trivy), not a package manager of a build tool. */
  public boolean isRegistry() { return registry; }
}
