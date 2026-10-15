package tech.wenisch.petri.agent.skill;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.config.PetriProperties;

@Service
public class SkillService {
  private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
  private static final int MAX_DESCRIPTION_LENGTH = 500;

  private final Path configuredRoot;
  private final Path installedRoot;
  private final int maxSkillBytes;

  /**
   * @param installedRoot where skills an administrator installed live; may be empty. Bundled skills
   *     win on a name clash, so an installed skill can never replace a built-in one.
   */
  @Autowired
  public SkillService(
      PetriProperties properties,
      @org.springframework.beans.factory.annotation.Value("${petri.skills.installed-root:}")
          String installedRoot) {
    this(
        Path.of(properties.agent().skillsRoot()).toAbsolutePath().normalize(),
        installedRoot == null || installedRoot.isBlank() ? null
            : Path.of(installedRoot.strip()).toAbsolutePath().normalize(),
        properties.agent().maxSkillBytes());
  }

  public SkillService(PetriProperties properties) {
    this(properties, "");
  }

  SkillService(Path configuredRoot, int maxSkillBytes) {
    this(configuredRoot, null, maxSkillBytes);
  }

  SkillService(Path configuredRoot, Path installedRoot, int maxSkillBytes) {
    this.configuredRoot = configuredRoot.toAbsolutePath().normalize();
    this.installedRoot = installedRoot == null ? null : installedRoot.toAbsolutePath().normalize();
    this.maxSkillBytes = maxSkillBytes;
  }

  public List<SkillSummary> list() {
    java.util.Map<String, SkillSummary> merged = new java.util.LinkedHashMap<>();
    for (Path root : roots()) {
      for (SkillSummary skill : listIn(root)) merged.putIfAbsent(skill.name(), skill);
    }
    return merged.values().stream().sorted(Comparator.comparing(SkillSummary::name)).toList();
  }

  /** The bundled root first, then the installed one: the order decides a name clash. */
  private List<Path> roots() {
    List<Path> roots = new ArrayList<>();
    Path bundled = root(false, configuredRoot);
    if (bundled != null) roots.add(bundled);
    if (installedRoot != null) {
      Path installed = root(false, installedRoot);
      if (installed != null) roots.add(installed);
    }
    return roots;
  }

  private List<SkillSummary> listIn(Path root) {
    List<SkillSummary> result = new ArrayList<>();
    try (var entries = Files.newDirectoryStream(root)) {
      for (Path entry : entries) {
        if (Files.isSymbolicLink(entry)
            || !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
            || !NAME.matcher(entry.getFileName().toString()).matches()) continue;
        Path skillFile = entry.resolve("SKILL.md");
        if (Files.isSymbolicLink(skillFile)
            || !Files.isRegularFile(skillFile, LinkOption.NOFOLLOW_LINKS)) continue;
        ParsedSkill parsed = parse(entry.getFileName().toString(), readBounded(skillFile));
        result.add(new SkillSummary(parsed.name(), parsed.description()));
      }
    } catch (IOException exception) {
      throw new IllegalStateException("Could not list skills", exception);
    }
    return result.stream().sorted(Comparator.comparing(SkillSummary::name)).toList();
  }

  public SkillDocument load(String name) {
    String safeName = requiredName(name);
    if (root(false, configuredRoot) == null && installedRoot == null) {
      root(true, configuredRoot); // reports the missing skills root
    }
    for (Path root : roots()) {
      Path directory = root.resolve(safeName).normalize();
      if (!directory.startsWith(root)
          || Files.isSymbolicLink(directory)
          || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
        continue;
      }
      Path skillFile = directory.resolve("SKILL.md");
      if (Files.isSymbolicLink(skillFile)
          || !Files.isRegularFile(skillFile, LinkOption.NOFOLLOW_LINKS)) {
        continue;
      }
      String content = readBounded(skillFile);
      ParsedSkill parsed = parse(safeName, content);
      return new SkillDocument(parsed.name(), parsed.description(), content, parsed.manifest());
    }
    throw new IllegalArgumentException("Unknown skill: " + safeName);
  }

  /** True when administrators can install skills (an installed root is configured). */
  public boolean installable() {
    return installedRoot != null;
  }

  /** The skills in the installed root, which can be removed again. */
  public List<SkillSummary> listInstalled() {
    if (installedRoot == null) return List.of();
    Path root = root(false, installedRoot);
    return root == null ? List.of() : listIn(root);
  }

  /**
   * Installs a prompt-only skill from its SKILL.md text. The name comes from the front matter; a
   * bundled skill of the same name wins, so it cannot be replaced; one that extends another must
   * extend a skill that exists.
   */
  public SkillSummary install(String content) {
    if (installedRoot == null) {
      throw new IllegalStateException(
          "Installing skills is not enabled: set petri.skills.installed-root");
    }
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("The skill file is empty");
    }
    if (content.getBytes(StandardCharsets.UTF_8).length > maxSkillBytes) {
      throw new IllegalArgumentException("The skill file is too large");
    }
    String name = declaredName(content);
    requiredName(name);
    ParsedSkill parsed;
    try {
      parsed = parse(name, content);
    } catch (IllegalStateException exception) {
      throw new IllegalArgumentException(exception.getMessage());
    }
    Path bundled = root(false, configuredRoot);
    if (bundled != null && Files.isRegularFile(bundled.resolve(name).resolve("SKILL.md"))) {
      throw new IllegalArgumentException("A built-in skill named '" + name + "' exists");
    }
    String parent = parsed.manifest().extendsSkill();
    if (parent != null) {
      try {
        load(parent);
      } catch (RuntimeException exception) {
        throw new IllegalArgumentException("The skill extends '" + parent + "', which does not exist");
      }
    }
    try {
      Files.createDirectories(installedRoot);
      Path directory = installedRoot.resolve(name).normalize();
      if (!directory.startsWith(installedRoot) || Files.isSymbolicLink(directory)) {
        throw new IllegalArgumentException("Skill name is invalid");
      }
      Files.createDirectories(directory);
      Path file = directory.resolve("SKILL.md");
      if (Files.isSymbolicLink(file)) throw new IllegalArgumentException("Skill name is invalid");
      Files.writeString(file, content, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("Could not store the skill", exception);
    }
    return new SkillSummary(parsed.name(), parsed.description());
  }

  public void remove(String name) {
    String safeName = requiredName(name);
    if (installedRoot == null || listInstalled().stream().noneMatch(skill -> skill.name().equals(safeName))) {
      throw new IllegalArgumentException("Only installed skills can be removed");
    }
    Path directory = installedRoot.resolve(safeName).normalize();
    try {
      Files.deleteIfExists(directory.resolve("SKILL.md"));
      Files.deleteIfExists(directory);
    } catch (IOException exception) {
      throw new IllegalStateException("Could not remove the skill", exception);
    }
  }

  private static String declaredName(String content) {
    for (String line : content.replace("\r\n", "\n").split("\n")) {
      if (line.startsWith("name:")) return unquote(line.substring(5).strip());
    }
    throw new IllegalArgumentException("The skill needs a name in its front matter");
  }

  private Path root(boolean required, Path configured) {
    if (!Files.exists(configured, LinkOption.NOFOLLOW_LINKS)) {
      if (required) throw new IllegalStateException("Skills root does not exist");
      return null;
    }
    if (Files.isSymbolicLink(configured)
        || !Files.isDirectory(configured, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalStateException("Skills root must be a non-symlink directory");
    }
    try {
      return configured.toRealPath(LinkOption.NOFOLLOW_LINKS);
    } catch (IOException exception) {
      throw new IllegalStateException("Could not resolve skills root", exception);
    }
  }

  private String readBounded(Path file) {
    try {
      if (Files.size(file) > maxSkillBytes) throw new IllegalStateException("Skill file is too large");
      byte[] bytes;
      try (InputStream input = Files.newInputStream(file)) {
        bytes = input.readNBytes(maxSkillBytes + 1);
      }
      if (bytes.length > maxSkillBytes) throw new IllegalStateException("Skill file is too large");
      return decodeUtf8(bytes);
    } catch (IOException exception) {
      throw new IllegalStateException("Could not read skill file", exception);
    }
  }

  private static String decodeUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException exception) {
      throw new IllegalStateException("Skill file is not valid UTF-8", exception);
    }
  }

  private static ParsedSkill parse(String directoryName, String content) {
    String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
    String[] lines = normalized.split("\n", -1);
    if (lines.length < 4 || !"---".equals(lines[0])) {
      throw new IllegalStateException("Skill file must start with YAML front matter");
    }
    String name = null;
    String description = null;
    String extendsSkill = null;
    String context = null;
    String produces = null;
    String version = null;
    String license = null;
    List<String> requires = new ArrayList<>();
    String openList = null;
    int closing = -1;
    for (int index = 1; index < lines.length; index++) {
      if ("---".equals(lines[index])) {
        closing = index;
        break;
      }
      String line = lines[index];
      // A block list: "requires:" followed by "  - item" lines.
      if (openList != null && line.stripLeading().startsWith("- ")) {
        if ("requires".equals(openList)) requires.add(unquote(line.strip().substring(2).strip()));
        continue;
      }
      openList = null;
      int separator = line.indexOf(':');
      if (separator < 1) continue;
      String key = line.substring(0, separator).strip();
      String raw = line.substring(separator + 1).strip();
      String value = unquote(raw);
      switch (key) {
        case "name" -> name = value;
        case "description" -> description = value;
        case "extends" -> extendsSkill = value.isEmpty() ? null : value;
        case "context" -> context = value.isEmpty() ? null : value;
        case "produces" -> produces = value.isEmpty() ? null : value;
        case "version" -> version = value.isEmpty() ? null : value;
        case "license" -> license = value.isEmpty() ? null : value;
        case "requires" -> {
          if (raw.isEmpty()) openList = "requires";
          else requires.addAll(inlineList(raw));
        }
        default -> { }
      }
    }
    if (closing < 0) throw new IllegalStateException("Skill front matter is not closed");
    if (!directoryName.equals(name)) {
      throw new IllegalStateException("Skill name must match its directory");
    }
    if (description == null || description.isBlank()) {
      throw new IllegalStateException("Skill description is required");
    }
    if (description.length() > MAX_DESCRIPTION_LENGTH) {
      throw new IllegalStateException("Skill description is too long");
    }
    if (extendsSkill != null && !NAME.matcher(extendsSkill).matches()) {
      throw new IllegalStateException("Skill 'extends' is not a skill name");
    }
    if (extendsSkill != null && extendsSkill.equals(name)) {
      throw new IllegalStateException("A skill cannot extend itself");
    }
    return new ParsedSkill(name, description,
        new SkillManifest(extendsSkill, context, requires, produces, version, license));
  }

  /** {@code [a, b]} or {@code a, b}. */
  private static List<String> inlineList(String raw) {
    String inner = raw.strip();
    if (inner.startsWith("[") && inner.endsWith("]")) inner = inner.substring(1, inner.length() - 1);
    List<String> values = new ArrayList<>();
    for (String part : inner.split(",")) {
      String value = unquote(part.strip());
      if (!value.isEmpty()) values.add(value);
    }
    return values;
  }

  private static String unquote(String value) {
    if (value.length() >= 2
        && ((value.startsWith("\"") && value.endsWith("\""))
            || (value.startsWith("'") && value.endsWith("'")))) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }

  private static String requiredName(String value) {
    if (value == null || !NAME.matcher(value).matches()) {
      throw new IllegalArgumentException("Skill name is invalid");
    }
    return value;
  }

  private record ParsedSkill(String name, String description, SkillManifest manifest) {}
}
