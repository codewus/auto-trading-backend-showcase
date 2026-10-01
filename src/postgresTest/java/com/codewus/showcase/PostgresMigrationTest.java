package com.codewus.showcase;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PostgresMigrationTest {
    private final String url = "jdbc:postgresql://localhost:" + System.getenv().getOrDefault("SHOWCASE_DB_PORT", "15432") + "/showcase";

    @Test void freshMigrationCreatesAllThreeVersionsAndUniqueConstraints() throws Exception {
        String schema = "fresh_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url, "showcase", "")) {
            var statement = connection.createStatement();
            statement.execute("CREATE SCHEMA " + schema);
            try {
                var flyway = flyway(schema, "3");
                assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3);
                assertThat(flyway.info().pending()).isEmpty();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='" + schema + "'");
                rows.next(); assertThat(rows.getInt(1)).isEqualTo(8); // Seven demo tables + Flyway history.
                assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
            } finally { statement.execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }

    @Test void migrateExistingLegacyRowsWithoutBackfillOrRemoval() throws Exception {
        String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url, "showcase", "")) {
            var statement = connection.createStatement();
            statement.execute("CREATE SCHEMA " + schema);
            try {
                assertThat(flyway(schema, "1").migrate().migrationsExecuted).isEqualTo(1);
                statement.execute("INSERT INTO " + schema + ".legacy_trade(actor_id,symbol,exchange_order_id,side,quantity,occurred_at) "
                        + "VALUES ('demo-legacy','DEMOUSDT','fx-past','BUY',1,'2026-01-01T00:00:00Z')");
                assertThat(flyway(schema, "3").migrate().migrationsExecuted).isEqualTo(2);
                var old = statement.executeQuery("SELECT COUNT(*) FROM " + schema + ".legacy_trade");
                old.next(); assertThat(old.getInt(1)).isEqualTo(1);
                var fills = statement.executeQuery("SELECT COUNT(*) FROM " + schema + ".fill");
                fills.next(); assertThat(fills.getInt(1)).isZero();
            } finally { statement.execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }

    private Flyway flyway(String schema, String target) {
        return Flyway.configure().dataSource(url, "showcase", "").schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target(target).load();
    }
}
