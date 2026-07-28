package com.edtice.crm.store;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Connection + schema management. The rest of the app only sees {@link #with},
 * so moving from local SQLite to RDS (Postgres) is a change of crm.db.url plus
 * a DDL variant here — no store code changes.
 */
@Singleton
public class Database {

    @ConfigProperty(name = "crm.db.url")
    String url;

    void init(@Observes StartupEvent ev) {
        if (isSqlite()) {
            String file = url.substring("jdbc:sqlite:".length());
            Path parent = Path.of(file).toAbsolutePath().getParent();
            if (parent != null) {
                try {
                    Files.createDirectories(parent);
                } catch (Exception e) {
                    throw new IllegalStateException("Cannot create database directory " + parent, e);
                }
            }
        }
        with(c -> {
            try (Statement st = c.createStatement()) {
                for (String ddl : ddl()) {
                    st.executeUpdate(ddl);
                }
            }
            migrate(c);
            return null;
        });
    }

    /** Additive migrations for databases created before a column or table existed. */
    private void migrate(Connection c) throws SQLException {
        if (!isSqlite()) {
            return;
        }
        addColumnIfMissing(c, "entities", "merged_into", "INTEGER REFERENCES entities(id)");
        addColumnIfMissing(c, "observations", "activity_id", "INTEGER REFERENCES activities(id)");
        migrateCasesToActivities(c);
    }

    private void addColumnIfMissing(Connection c, String table, String column, String type) throws SQLException {
        boolean present = false;
        try (Statement st = c.createStatement();
             var rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    present = true;
                }
            }
        }
        if (!present) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            }
        }
    }

    /** One-time: fold the original support-case tables into the generic activities model. */
    private void migrateCasesToActivities(Connection c) throws SQLException {
        boolean hasCases;
        try (Statement st = c.createStatement();
             var rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'cases'")) {
            hasCases = rs.next();
        }
        if (!hasCases) {
            return;
        }
        try (Statement st = c.createStatement()) {
            st.executeUpdate("""
                    INSERT INTO activities (id, kind, state, label, token, reference, created_at)
                    SELECT id, 'support', 'open', subject, case_token, case_number, created_at FROM cases""");
            st.executeUpdate("""
                    INSERT INTO activity_documents (activity_id, source_doc_id)
                    SELECT case_id, source_doc_id FROM case_documents""");
            st.executeUpdate("""
                    INSERT INTO activity_assessments (id, activity_id, triggered_by_doc, health,
                        customer_disposition, disposition_notes, technical_progress, technical_notes,
                        root_cause_progress, root_cause_notes, summary, created_at)
                    SELECT id, case_id, triggered_by_doc, health, customer_disposition, disposition_notes,
                        technical_progress, technical_notes, root_cause_progress, root_cause_notes,
                        summary, created_at FROM case_assessments""");
            st.executeUpdate("DROP TABLE case_assessments");
            st.executeUpdate("DROP TABLE case_documents");
            st.executeUpdate("DROP TABLE cases");
        }
    }

    private boolean isSqlite() {
        return url.startsWith("jdbc:sqlite:");
    }

    public Connection open() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        if (isSqlite()) {
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
                st.execute("PRAGMA busy_timeout = 5000");
            }
        }
        return c;
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection c) throws SQLException;
    }

    public <T> T with(SqlWork<T> work) {
        try (Connection c = open()) {
            return work.run(c);
        } catch (SQLException e) {
            throw new IllegalStateException("Database error: " + e.getMessage(), e);
        }
    }

    private List<String> ddl() {
        if (!isSqlite()) {
            throw new IllegalStateException(
                    "No schema bootstrap for " + url + " yet — add a DDL variant for this dialect");
        }
        // SQLite dialect. The SQL used by the stores is ANSI; only this DDL and the
        // key-generation syntax differ per engine.
        return List.of(
                """
                CREATE TABLE IF NOT EXISTS entities (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    kind TEXT NOT NULL,
                    display_name TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    merged_into INTEGER REFERENCES entities(id)
                )""",
                """
                CREATE TABLE IF NOT EXISTS source_documents (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    source_type TEXT NOT NULL,
                    external_id TEXT UNIQUE,
                    raw_content TEXT NOT NULL,
                    metadata TEXT,
                    received_at TEXT NOT NULL,
                    status TEXT NOT NULL,
                    error TEXT
                )""",
                """
                CREATE TABLE IF NOT EXISTS observations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    entity_id INTEGER NOT NULL REFERENCES entities(id),
                    attribute TEXT NOT NULL,
                    value TEXT NOT NULL,
                    confidence REAL NOT NULL,
                    evidence TEXT,
                    source_doc_id INTEGER REFERENCES source_documents(id),
                    status TEXT NOT NULL,
                    observed_at TEXT NOT NULL,
                    superseded_by INTEGER REFERENCES observations(id)
                )""",
                """
                CREATE TABLE IF NOT EXISTS relationships (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    from_entity INTEGER NOT NULL REFERENCES entities(id),
                    to_entity INTEGER NOT NULL REFERENCES entities(id),
                    kind TEXT NOT NULL,
                    source_doc_id INTEGER REFERENCES source_documents(id)
                )""",
                """
                CREATE TABLE IF NOT EXISTS opportunities (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    guid TEXT NOT NULL UNIQUE,
                    name TEXT,
                    external_system TEXT,
                    external_ref TEXT,
                    created_at TEXT NOT NULL
                )""",
                """
                CREATE TABLE IF NOT EXISTS opportunity_attributes (
                    opportunity_id INTEGER NOT NULL REFERENCES opportunities(id),
                    key TEXT NOT NULL,
                    value TEXT,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (opportunity_id, key)
                )""",
                """
                CREATE TABLE IF NOT EXISTS activities (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    kind TEXT NOT NULL,
                    state TEXT NOT NULL,
                    label TEXT,
                    token TEXT UNIQUE,
                    reference TEXT,
                    primary_entity_id INTEGER REFERENCES entities(id),
                    opportunity_id INTEGER REFERENCES opportunities(id),
                    created_at TEXT NOT NULL,
                    closed_at TEXT
                )""",
                """
                CREATE TABLE IF NOT EXISTS activity_documents (
                    activity_id INTEGER NOT NULL REFERENCES activities(id),
                    source_doc_id INTEGER NOT NULL REFERENCES source_documents(id),
                    PRIMARY KEY (activity_id, source_doc_id)
                )""",
                """
                CREATE TABLE IF NOT EXISTS activity_assessments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    activity_id INTEGER NOT NULL REFERENCES activities(id),
                    triggered_by_doc INTEGER REFERENCES source_documents(id),
                    health TEXT NOT NULL,
                    customer_disposition TEXT,
                    disposition_notes TEXT,
                    technical_progress TEXT,
                    technical_notes TEXT,
                    root_cause_progress TEXT,
                    root_cause_notes TEXT,
                    summary TEXT,
                    created_at TEXT NOT NULL
                )""",
                """
                CREATE TABLE IF NOT EXISTS housekeeping_records (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    kind TEXT NOT NULL,
                    status TEXT NOT NULL,
                    evidence TEXT,
                    reasoning TEXT,
                    decided_by TEXT,
                    confidence REAL NOT NULL,
                    prior_record_id INTEGER REFERENCES housekeeping_records(id),
                    created_at TEXT NOT NULL,
                    decided_at TEXT
                )""",
                """
                CREATE TABLE IF NOT EXISTS housekeeping_record_entities (
                    record_id INTEGER NOT NULL REFERENCES housekeeping_records(id),
                    entity_id INTEGER NOT NULL REFERENCES entities(id),
                    PRIMARY KEY (record_id, entity_id)
                )""",
                "CREATE INDEX IF NOT EXISTS idx_hk_entities ON housekeeping_record_entities(entity_id)",
                "CREATE INDEX IF NOT EXISTS idx_obs_entity ON observations(entity_id, attribute, status)",
                "CREATE INDEX IF NOT EXISTS idx_obs_status ON observations(status)",
                "CREATE INDEX IF NOT EXISTS idx_docs_status ON source_documents(status)",
                "CREATE INDEX IF NOT EXISTS idx_activity_docs_doc ON activity_documents(source_doc_id)",
                "CREATE INDEX IF NOT EXISTS idx_activity_assess ON activity_assessments(activity_id)",
                "CREATE INDEX IF NOT EXISTS idx_activities_kind ON activities(kind, state)");
    }
}
