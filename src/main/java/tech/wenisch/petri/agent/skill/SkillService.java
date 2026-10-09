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
  private final int maxSkillBytes;

  @Autowired
  public SkillService(PetriProperties properties) {
    this(
        Path.of(properties.agent().skillsRoot()).toAbsolutePath().normalize(),
        properties.agent().maxSkillBytes());
  }

  SkillService(Path configuredRoot, int maxSkillBytes) {
    this.configuredRoot = configuredRoot.toAbsolutePath().normalize();
    this.maxSkillBytes = maxSkillBytes;
  }

  public List<SkillSummary> list() {
    Path root = root(false);
    if (root == null) return List.of();
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
    Path root = root(true);
    Path directory = root.resolve(safeName).normalize();
    if (!directory.startsWith(root)
        || Files.isSymbolicLink(directory)
        || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("Unknown skill: " + safeName);
    }
    Path skillFile = directory.resolve("SKILL.md");
    if (Files.isSymbolicLink(skillFile)
        || !Files.isRegularFile(skillFile, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("Unknown skill: " + safeName);
    }
    String content = readBounded(skillFile);
    ParsedSkill parsed = parse(safeName, content);
    return new SkillDocument(parsed.name(), parsed.description(), content);
  }

  private Path root(boolean required) {
    if (!Files.exists(configuredRoot, LinkOption.NOFOLLOW_LINKS)) {
      if (required) throw new IllegalStateException("Skills root does not exist");
      return null;
    }
    if (Files.isSymbolicLink(configuredRoot)
        || !Files.isDirectory(configuredRoot, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalStateException("Skills root must be a non-symlink directory");
    }
    try {
      return configuredRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
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
    int closing = -1;
    for (int index = 1; index < lines.length; index++) {
      if ("---".equals(lines[index])) {
        closing = index;
        break;
      }
      int separator = lines[index].indexOf(':');
      if (separator < 1) continue;
      String key = lines[index].substring(0, separator).strip();
      String value = unquote(lines[index].substring(separator + 1).strip());
      if ("name".equals(key)) name = value;
      if ("description".equals(key)) description = value;
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
    return new ParsedSkill(name, description);
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

  private record ParsedSkill(String name, String description) {}
}
