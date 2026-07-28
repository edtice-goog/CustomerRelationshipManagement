package com.edtice.crm.store.jdbc;

import com.edtice.crm.domain.Opportunity;
import com.edtice.crm.store.Database;
import com.edtice.crm.store.OpportunityStore;
import jakarta.inject.Singleton;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

@Singleton
public class JdbcOpportunityStore implements OpportunityStore {

    private static final String COLS = "id, guid, name, external_system, external_ref, created_at";

    private final Database db;

    JdbcOpportunityStore(Database db) {
        this.db = db;
    }

    @Override
    public Opportunity create(String name, String externalSystem, String externalRef) {
        Instant now = Instant.now();
        String guid = UUID.randomUUID().toString();
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO opportunities (guid, name, external_system, external_ref, created_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, guid);
                ps.setString(2, name);
                ps.setString(3, externalSystem);
                ps.setString(4, externalRef);
                ps.setString(5, now.toString());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return new Opportunity(keys.getLong(1), guid, name, externalSystem, externalRef, now);
                }
            }
        });
    }

    @Override
    public Optional<Opportunity> byId(long id) {
        return one("SELECT " + COLS + " FROM opportunities WHERE id = ?", ps -> ps.setLong(1, id));
    }

    @Override
    public Optional<Opportunity> byGuid(String guid) {
        return one("SELECT " + COLS + " FROM opportunities WHERE guid = ?", ps -> ps.setString(1, guid));
    }

    @Override
    public List<Opportunity> listAll() {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + COLS + " FROM opportunities ORDER BY created_at DESC, id DESC")) {
                try (ResultSet rs = ps.executeQuery()) {
                    List<Opportunity> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                    return out;
                }
            }
        });
    }

    @Override
    public void setAttribute(long opportunityId, String key, String value) {
        db.with(c -> {
            try (PreparedStatement del = c.prepareStatement(
                    "DELETE FROM opportunity_attributes WHERE opportunity_id = ? AND key = ?")) {
                del.setLong(1, opportunityId);
                del.setString(2, key);
                del.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO opportunity_attributes (opportunity_id, key, value, updated_at) VALUES (?, ?, ?, ?)")) {
                ps.setLong(1, opportunityId);
                ps.setString(2, key);
                ps.setString(3, value);
                ps.setString(4, Instant.now().toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public java.util.Map<String, String> attributes(long opportunityId) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT key, value FROM opportunity_attributes WHERE opportunity_id = ?")) {
                ps.setLong(1, opportunityId);
                try (ResultSet rs = ps.executeQuery()) {
                    TreeMap<String, String> out = new TreeMap<>();
                    while (rs.next()) {
                        out.put(rs.getString(1), rs.getString(2));
                    }
                    return out;
                }
            }
        });
    }

    // --- helpers ---

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private Optional<Opportunity> one(String sql, Binder binder) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                binder.bind(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        });
    }

    private static Opportunity map(ResultSet rs) throws SQLException {
        return new Opportunity(
                rs.getLong("id"),
                rs.getString("guid"),
                rs.getString("name"),
                rs.getString("external_system"),
                rs.getString("external_ref"),
                Instant.parse(rs.getString("created_at")));
    }
}
