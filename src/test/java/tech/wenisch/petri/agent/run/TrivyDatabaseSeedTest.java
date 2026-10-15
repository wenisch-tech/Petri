package tech.wenisch.petri.agent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrivyDatabaseSeedTest {
  private static Path supplied(Path root, String content, String meta) throws Exception {
    Path db = Files.createDirectories(root.resolve("image/db"));
    Files.writeString(db.resolve("trivy.db"), content);
    Files.writeString(db.resolve("metadata.json"), meta);
    return root.resolve("image");
  }

  @Test
  void theDatabaseOfTheImageIsInstalledIntoAnEmptyCacheAndKeptCurrent(@TempDir Path temp) throws Exception {
    Path image = supplied(temp, "database-v1", "{\"UpdatedAt\":\"2026-10-15T07:00:00Z\"}");
    Path cache = Files.createDirectories(temp.resolve("cache"));

    TrivyScanService.seedFromImage(image.toString(), cache);

    assertThat(Files.readString(cache.resolve("db/trivy.db"))).isEqualTo("database-v1");
    assertThat(Files.readString(cache.resolve("db/metadata.json"))).contains("2026-10-15");

    // Same database: nothing is copied again. A newer one in the image replaces the old copy.
    Files.writeString(cache.resolve("db/marker"), "kept");
    TrivyScanService.seedFromImage(image.toString(), cache);
    assertThat(cache.resolve("db/marker")).exists();
    supplied(temp, "database-v2-longer", "{\"UpdatedAt\":\"2026-10-16T07:00:00Z\"}");
    TrivyScanService.seedFromImage(image.toString(), cache);
    assertThat(Files.readString(cache.resolve("db/trivy.db"))).isEqualTo("database-v2-longer");
  }

  @Test
  void aMissingSuppliedDatabaseIsReportedWithWhereItWasLookedFor(@TempDir Path temp) throws Exception {
    Path cache = Files.createDirectories(temp.resolve("cache"));

    assertThatThrownBy(() -> TrivyScanService.seedFromImage(temp.resolve("nothing").toString(), cache))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no database at").hasMessageContaining("BAKE_TRIVY_DB");
  }
}
