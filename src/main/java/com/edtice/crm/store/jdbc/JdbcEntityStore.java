package com.edtice.crm.store.jdbc;

import com.edtice.crm.domain.Entity;
import com.edtice.crm.store.Database;
import com.edtice.crm.store.EntityStore;
import jakarta.inject.Singleton;

import java.sql.Connection;
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

    private static final String COLS = "id, kind, display_name, created_at, merged_into";

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
                    return new Entity(keys.getLong(1), kind, displayName, now, null);
                }
            }
        });
    }

    @Override
    public Optional<Entity> byId(long id) {
        return db.with(c -> byId(c, id));
    }

    private static Optional<Entity> byId(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + COLS + " FROM entities WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    @Override
    public Optional<Entity> findByName(String kind, String displayName) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + COLS + " FROM entities WHERE kind = ? AND LOWER(display_name) = LOWER(?)")) {
                ps.setString(1, kind);
                ps.setString(2, displayName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    Entity e = map(rs);
                    // Follow merge pointers so aliased names resolve to the survivor.
                    int hops = 0;
                    while (e.mergedInto() != null && hops++ < 10) {
                        Optional<Entity> next = byId(c, e.mergedInto());
                        if (next.isEmpty()) {
                            break;
                        }
                        e = next.get();
                    }
                    return Optional.of(e);
                }
            }
        });
    }

    @Override
    public List<Entity> listByKind(String kind) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + COLS + " FROM entities WHERE kind = ? AND merged_into IS NULL ORDER BY display_name")) {
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

    @Override
    public Entity merge(long loserId, long winnerId) {
        if (loserId == winnerId) {
            throw new IllegalArgumentException("Cannot merge an entity into itself");
        }
        return db.with(c -> {
            Entity winner = byId(c, winnerId)
                    .orElseThrow(() -> new IllegalStateException("No such entity: " + winnerId));
            if (winner.mergedInto() != null) {
                throw new IllegalStateException("Merge target " + winnerId + " is itself merged");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE observations SET entity_id = ? WHERE entity_id = ?")) {
                ps.setLong(1, winnerId);
                ps.setLong(2, loserId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE relationships SET from_entity = ? WHERE from_entity = ?")) {
                ps.setLong(1, winnerId);
                ps.setLong(2, loserId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE relationships SET to_entity = ? WHERE to_entity = ?")) {
                ps.setLong(1, winnerId);
                ps.setLong(2, loserId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM relationships WHERE from_entity = to_entity")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE entities SET merged_into = ? WHERE id = ?")) {
                ps.setLong(1, winnerId);
                ps.setLong(2, loserId);
                ps.executeUpdate();
            }
            return winner;
        });
    }

    private static Entity map(ResultSet rs) throws SQLException {
        long mergedInto = rs.getLong("merged_into");
        Long mergedIntoId = rs.wasNull() ? null : mergedInto;
        return new Entity(
                rs.getLong("id"),
                rs.getString("kind"),
                rs.getString("display_name"),
                Instant.parse(rs.getString("created_at")),
                mergedIntoId);
    }
}
