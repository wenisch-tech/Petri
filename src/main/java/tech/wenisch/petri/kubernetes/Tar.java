package tech.wenisch.petri.kubernetes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;

/**
 * A minimal tar implementation for moving source trees: directories and regular files only, with
 * long names (PAX and GNU) understood. Entries that could leave the destination are refused.
 */
final class Tar {
  private static final int BLOCK = 512;

  private Tar() {}

  /** Unpacks a tar stream below {@code destination}; returns false when a cap was exceeded. */
  static boolean extract(InputStream in, Path destination, long maxBytes, int maxFiles) throws IOException {
    long total = 0;
    int files = 0;
    String pendingName = null;
    byte[] header = new byte[BLOCK];
    while (true) {
      if (!readBlock(in, header)) return true;
      if (isZero(header)) return true;
      String name = text(header, 0, 100);
      String prefix = text(header, 345, 155);
      if (!prefix.isEmpty()) name = prefix + "/" + name;
      long size = octal(header, 124, 12);
      char type = (char) (header[156] == 0 ? '0' : header[156]);
      if (type == 'x' || type == 'g') {
        String path = paxPath(in.readNBytes((int) size));
        skip(in, padding(size));
        if (type == 'x' && path != null) pendingName = path;
        continue;
      }
      if (type == 'L') {
        pendingName = text(in.readNBytes((int) size), 0, (int) size);
        skip(in, padding(size));
        continue;
      }
      if (pendingName != null) {
        name = pendingName;
        pendingName = null;
      }
      Path target = safe(destination, name);
      if (type == '5') {
        Files.createDirectories(target);
      } else if (type == '0' || type == '\0') {
        total += size;
        files++;
        if (total > maxBytes || files > maxFiles) return false;
        Files.createDirectories(target.getParent());
        try (OutputStream out = Files.newOutputStream(target)) {
          copy(in, out, size);
        }
        skip(in, padding(size));
        if ((octal(header, 100, 8) & 0111) != 0) target.toFile().setExecutable(true, false);
      } else {
        throw new IOException("Archive entry type '" + type + "' is not supported");
      }
    }
  }

  /** Packs a local directory; names are relative to it. */
  static void write(Path source, OutputStream out) throws IOException {
    try (Stream<Path> walk = Files.walk(source)) {
      for (Path path : (Iterable<Path>) walk::iterator) {
        if (path.equals(source)) continue;
        String name = source.relativize(path).toString().replace('\\', '/');
        if (Files.isSymbolicLink(path)) continue;
        if (Files.isDirectory(path)) {
          entry(out, name + "/", '5', 0, 0755);
        } else if (Files.isRegularFile(path)) {
          long size = Files.size(path);
          entry(out, name, '0', size, modeOf(path));
          try (InputStream in = Files.newInputStream(path)) {
            copy(in, out, size);
          }
          out.write(new byte[padding(size)]);
        }
      }
    }
    out.write(new byte[BLOCK * 2]);
  }

  /** The permission bits of a file, so that a private file (0600, credentials) stays private. */
  private static int modeOf(Path path) {
    try {
      int mode = 0;
      for (var permission : Files.getPosixFilePermissions(path)) {
        mode |= switch (permission) {
          case OWNER_READ -> 0400;
          case OWNER_WRITE -> 0200;
          case OWNER_EXECUTE -> 0100;
          case GROUP_READ -> 0040;
          case GROUP_WRITE -> 0020;
          case GROUP_EXECUTE -> 0010;
          case OTHERS_READ -> 0004;
          case OTHERS_WRITE -> 0002;
          case OTHERS_EXECUTE -> 0001;
        };
      }
      return mode;
    } catch (IOException | UnsupportedOperationException exception) {
      return Files.isExecutable(path) ? 0755 : 0644;
    }
  }

  private static void entry(OutputStream out, String name, char type, long size, int mode) throws IOException {
    byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > 100) {
      byte[] record = pax("path", name);
      out.write(header("PaxHeader", 'x', record.length, 0644));
      out.write(record);
      out.write(new byte[padding(record.length)]);
    }
    out.write(header(name, type, size, mode));
  }

  private static byte[] pax(String key, String value) {
    String body = " " + key + "=" + value + "\n";
    int length = body.getBytes(StandardCharsets.UTF_8).length;
    int total = length + Integer.toString(length).length();
    if (Integer.toString(total).length() != Integer.toString(length).length()) total++;
    return (total + body).getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] header(String name, char type, long size, int mode) {
    byte[] block = new byte[BLOCK];
    put(block, 0, name.getBytes(StandardCharsets.UTF_8), 100);
    put(block, 100, String.format("%07o", mode).getBytes(StandardCharsets.US_ASCII), 8);
    put(block, 108, "0000000".getBytes(StandardCharsets.US_ASCII), 8);
    put(block, 116, "0000000".getBytes(StandardCharsets.US_ASCII), 8);
    put(block, 124, String.format("%011o", size).getBytes(StandardCharsets.US_ASCII), 12);
    put(block, 136, String.format("%011o", System.currentTimeMillis() / 1000).getBytes(StandardCharsets.US_ASCII), 12);
    Arrays.fill(block, 148, 156, (byte) ' ');
    block[156] = (byte) type;
    put(block, 257, "ustar".getBytes(StandardCharsets.US_ASCII), 6);
    block[263] = '0';
    block[264] = '0';
    long sum = 0;
    for (byte value : block) sum += value & 0xff;
    put(block, 148, String.format("%06o", sum).getBytes(StandardCharsets.US_ASCII), 7);
    block[154] = 0;
    block[155] = ' ';
    return block;
  }

  private static void put(byte[] block, int offset, byte[] value, int max) {
    System.arraycopy(value, 0, block, offset, Math.min(value.length, max));
  }

  private static Path safe(Path destination, String name) throws IOException {
    if (name.isEmpty() || name.indexOf('\0') >= 0 || name.startsWith("/")) {
      throw new IOException("Archive entry name is invalid");
    }
    Path base = destination.normalize();
    Path target = base.resolve(name).normalize();
    if (!target.startsWith(base)) throw new IOException("Archive entry escapes its destination");
    return target;
  }

  private static boolean readBlock(InputStream in, byte[] block) throws IOException {
    int read = in.readNBytes(block, 0, BLOCK);
    if (read == 0) return false;
    if (read < BLOCK) throw new IOException("Archive ends in the middle of a block");
    return true;
  }

  private static boolean isZero(byte[] block) {
    for (byte value : block) if (value != 0) return false;
    return true;
  }

  private static String text(byte[] block, int offset, int length) {
    int end = offset;
    while (end < offset + length && end < block.length && block[end] != 0) end++;
    return new String(block, offset, end - offset, StandardCharsets.UTF_8);
  }

  private static long octal(byte[] block, int offset, int length) {
    String value = text(block, offset, length).strip();
    return value.isEmpty() ? 0 : Long.parseLong(value, 8);
  }

  private static String paxPath(byte[] data) {
    String found = null;
    int position = 0;
    while (position < data.length) {
      int space = position;
      while (space < data.length && data[space] != ' ') space++;
      int length = Integer.parseInt(new String(data, position, space - position, StandardCharsets.US_ASCII));
      String record = new String(data, space + 1, position + length - space - 2, StandardCharsets.UTF_8);
      if (record.startsWith("path=")) found = record.substring(5);
      position += length;
    }
    return found;
  }

  private static int padding(long size) {
    return (int) ((BLOCK - size % BLOCK) % BLOCK);
  }

  private static void skip(InputStream in, long count) throws IOException {
    if (count > 0 && in.readNBytes((int) count).length < count) throw new IOException("Archive is truncated");
  }

  private static void copy(InputStream in, OutputStream out, long size) throws IOException {
    byte[] buffer = new byte[32 * 1024];
    long left = size;
    while (left > 0) {
      int read = in.read(buffer, 0, (int) Math.min(buffer.length, left));
      if (read < 0) throw new IOException("Archive is truncated");
      out.write(buffer, 0, read);
      left -= read;
    }
  }

  static byte[] pack(Path source) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    write(source, out);
    return out.toByteArray();
  }
}
