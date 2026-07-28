package com.edtice.crm.store.jdbc;

import com.edtice.crm.domain.Entity;
import com.edtice.crm.store.Database;
import com.edtice.crm.store.EntityStore;
import jakarta.inject.Singleton;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Singleton
public class JdbcEntityStore implements EntityStore {

    private final Database db;

    JdbcEntityStore(Database db) {
        this.db = db;
    }

    @Override
    public Entity create(String kind, String displayName) {
        Instant now = Instant.now();
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO entities (kind, display_name, created_at) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, kind);
                ps.setString(2, displayName);
                ps.setString(3, now.toString());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return new Entity(keys.getLong(1), kind, displayName, now);
                }
            }
        });
    }

    @Override
    public Optional<Entity> byId(long id) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, kind, display_name, created_at FROM entities WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public Optional<Entity> findByName(String kind, String displayName) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, kind, display_name, created_at FROM entities WHERE kind = ? AND LOWER(display_name) = LOWER(?)")) {
                ps.setString(1, kind);
                ps.setString(2, displayName);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<Entity> listByKind(String kind) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, kind, display_name, created_at FROM entities WHERE kind = ? ORDER BY display_name")) {
                ps.setString(1, kind);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Entity> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                    return out;
                }
            }
        });
    }

    private static Entity map(ResultSet rs) throws SQLException {
        return new Entity(
                rs.getLong("id"),
                rs.getString("kind"),
                rs.getString("display_name"),
                Instant.parse(rs.getString("created_at")));
    }
}
