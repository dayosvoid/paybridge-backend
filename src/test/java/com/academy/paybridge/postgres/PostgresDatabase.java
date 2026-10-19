package com.academy.paybridge.postgres;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;

/**
 * One real PostgreSQL in Docker, shared by every "postgres" test in this run. Started on first use and
 * removed automatically when the test JVM exits.
 */
final class PostgresDatabase {

    static final String USER = "paybridge";
    static final String PASSWORD = "paybridge";
    static final String MAIN_DB = "paybridge";

    private static GenericContainer<?> container;

    private PostgresDatabase() {}

    static synchronized void ensureStarted() {
        if (container != null) {
            return;
        }
        GenericContainer<?> candidate = new GenericContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withEnv("POSTGRES_USER", USER)
                .withEnv("POSTGRES_PASSWORD", PASSWORD)
                .withEnv("POSTGRES_DB", MAIN_DB)
                .withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2)
                        .withStartupTimeout(Duration.ofSeconds(180)));
        try {
            candidate.start();
        } catch (RuntimeException e) {
            throw new IllegalStateException("Could not start the PostgreSQL test container. "
                    + "Is Docker running? (These tests only run with: mvnw test -Ppostgres)", e);
        }
        container = candidate;
        waitUntilAcceptingConnections();
    }

    static String url(String database) {
        ensureStarted();
        return "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/" + database;
    }

    /** A brand-new empty database on the shared server. Returns its name. */
    static String createDatabase(String prefix) {
        String name = prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(url(MAIN_DB), USER, PASSWORD);
             Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create test database " + name, e);
        }
        return name;
    }

    private static void waitUntilAcceptingConnections() {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        SQLException last = null;
        while (System.nanoTime() < deadline) {
            try (Connection ignored = DriverManager.getConnection(url(MAIN_DB), USER, PASSWORD)) {
                return;
            } catch (SQLException e) {
                last = e;
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new IllegalStateException("PostgreSQL started but does not accept connections", last);
    }
}
