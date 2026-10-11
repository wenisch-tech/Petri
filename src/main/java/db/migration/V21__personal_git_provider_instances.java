package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Makes self-hosted Git provider instances personal while retaining global built-in presets. */
public class V21__personal_git_provider_instances extends BaseJavaMigration {
  @Override
  public void migrate(Context context) throws Exception {
    Connection connection = context.getConnection();
    try (Statement statement = connection.createStatement()) {
      statement.execute("ALTER TABLE git_provider_instance ADD COLUMN owner_id UUID");
      statement.execute("""
          ALTER TABLE git_provider_instance
          ADD CONSTRAINT fk_git_provider_instance_owner
          FOREIGN KEY (owner_id) REFERENCES app_user (id)
          """);
    }

    for (String constraint : webUrlUniqueConstraints(connection)) {
      try (Statement statement = connection.createStatement()) {
        statement.execute("ALTER TABLE git_provider_instance DROP CONSTRAINT "
            + quoted(connection, constraint));
      }
    }

    try (Statement statement = connection.createStatement()) {
      statement.execute("""
          CREATE UNIQUE INDEX uk_git_provider_owner_url
          ON git_provider_instance (owner_id, web_base_url)
          """);
      statement.execute("""
          CREATE INDEX idx_git_provider_owner_enabled
          ON git_provider_instance (owner_id, enabled)
          """);
    }
  }

  private static List<String> webUrlUniqueConstraints(Connection connection) throws SQLException {
    List<String> constraints = new ArrayList<>();
    String query = """
        SELECT tc.constraint_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
          ON tc.constraint_catalog = kcu.constraint_catalog
         AND tc.constraint_schema = kcu.constraint_schema
         AND tc.constraint_name = kcu.constraint_name
        WHERE LOWER(tc.table_name) = 'git_provider_instance'
          AND UPPER(tc.constraint_type) = 'UNIQUE'
        GROUP BY tc.constraint_catalog, tc.constraint_schema, tc.constraint_name
        HAVING COUNT(*) = 1 AND MAX(LOWER(kcu.column_name)) = 'web_base_url'
        """;
    try (Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery(query)) {
      while (result.next()) constraints.add(result.getString(1));
    }
    if (constraints.isEmpty()) {
      throw new SQLException("Could not locate the legacy web_base_url unique constraint");
    }
    return constraints;
  }

  private static String quoted(Connection connection, String identifier) throws SQLException {
    String quote = connection.getMetaData().getIdentifierQuoteString();
    if (quote == null || quote.isBlank()) return identifier;
    String trimmed = quote.trim();
    return trimmed + identifier.replace(trimmed, trimmed + trimmed) + trimmed;
  }
}
