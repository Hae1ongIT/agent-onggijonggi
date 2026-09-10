package com.onggijonggi.api.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;

/**
 * Class Name : FlywayMigrationVerifier.java
 * Description : 빈 PostgreSQL에 전체 Flyway migration을 적용하고 history 성공 상태를 검증하는 CI 전용 실행기.
 */
public final class FlywayMigrationVerifier {

	private static final String JDBC_URL = "FLYWAY_VERIFY_JDBC_URL";
	private static final String USERNAME = "FLYWAY_VERIFY_USERNAME";
	private static final String PASSWORD = "FLYWAY_VERIFY_PASSWORD";

	private FlywayMigrationVerifier() {
	}

	public static void main(String[] args) throws SQLException {
		var jdbcUrl = requiredEnvironment(JDBC_URL);
		var username = requiredEnvironment(USERNAME);
		var password = requiredEnvironment(PASSWORD);
		var flyway = Flyway.configure()
			.dataSource(jdbcUrl, username, password)
			.locations("classpath:db/migration")
			.load();

		flyway.migrate();
		flyway.validate();

		var applied = Arrays.stream(flyway.info().applied())
			.filter(migration -> migration.getVersion() != null)
			.toList();
		ensureEveryVersionedMigrationSucceeded(applied);
		ensureHistoryHasNoFailures(jdbcUrl, username, password);

		System.out.printf("Applied %d versioned Flyway migrations:%n", applied.size());
		for (var migration : applied) {
			System.out.printf("- %s %s%n", migration.getVersion(), migration.getDescription());
		}
	}

	private static String requiredEnvironment(String key) {
		var value = System.getenv(key);
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(key + " must be set");
		}
		return value;
	}

	private static void ensureEveryVersionedMigrationSucceeded(List<MigrationInfo> applied) {
		var unsuccessful = applied.stream()
			.filter(migration -> migration.getState() != MigrationState.SUCCESS)
			.toList();
		if (!unsuccessful.isEmpty()) {
			throw new IllegalStateException("Versioned migrations did not succeed: " + unsuccessful);
		}
	}

	private static void ensureHistoryHasNoFailures(String jdbcUrl, String username, String password) throws SQLException {
		try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
			PreparedStatement statement = connection.prepareStatement(
				"select installed_rank, version, description from flyway_schema_history where success = false");
			ResultSet rows = statement.executeQuery()) {
			if (rows.next()) {
				throw new IllegalStateException("Failed Flyway history row: rank=%d, version=%s, description=%s"
					.formatted(rows.getInt("installed_rank"), rows.getString("version"), rows.getString("description")));
			}
		}
	}

}
