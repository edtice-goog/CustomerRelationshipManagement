package com.edtice.crm.store.jdbc;

import com.edtice.crm.domain.Observation;
import com.edtice.crm.domain.ObservationStatus;
import com.edtice.crm.store.Database;
import com.edtice.crm.store.ObservationStore;
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
public class JdbcObservationStore implements ObservationStore {

    private static final String COLS =
            "id, entity_id, attribute, value, confidence, evidence, source_doc_id, status, observed_at";

    private final Database db;

    JdbcObservationStore(Database db) {
        this.db = db;
    }

    @Override
    public Observation insert(long entityId, String attribute, String value, double confidence,
                              String evidence, Long sourceDocId, ObservationStatus status) {
        Instant now = Instant.now();
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO observations (entity_id, attribute, value, confidence, evidence, source_doc_id, status, observed_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, entityId);
                ps.setString(2, attribute);
                ps.setString(3, value);
                ps.setDouble(4, confidence);
                ps.setString(5, evidence);
                if (sourceDocId == null) {
                    ps.setNull(6, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(6, sourceDocId);
                }
                ps.setString(7, status.db());
                ps.setString(8, now.toString());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return new Observation(keys.getLong(1), entityId, attribute, value, confidence,
                            evidence, sourceDocId, status, now);
                }
            }
        });
    }

    @Override
    public Optional<Observation> byId(long id) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + COLS + " FROM observations WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<Observation> forEntity(long entityId) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + COLS + " FROM observations WHERE entity_id = ? ORDER BY observed_at DESC, id DESC")) {
                ps.setLong(1, entityId);
                try (ResultSet rs = ps.executeQuery()) {
                    return mapAll(rs);
                }
            }
        });
    }

    @Override
    public List<Observation> pendingReview() {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + COLS + " FROM observations WHERE status = ? ORDER BY observed_at DESC, id DESC")) {
                ps.setString(1, ObservationStatus.PENDING_REVIEW.db());
                try (ResultSet rs = ps.executeQuery()) {
                    return mapAll(rs);
                }
            }
        });
    }

    @Override
    public Optional<Long> entityIdByAttributeValue(String attribute, String value) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT entity_id FROM observations WHERE attribute = ? AND LOWER(value) = LOWER(?) "
                            + "AND status IN (?, ?) ORDER BY id LIMIT 1")) {
                ps.setString(1, attribute);
                ps.setString(2, value);
                ps.setString(3, ObservationStatus.ACTIVE.db());
                ps.setString(4, ObservationStatus.PENDING_REVIEW.db());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public boolean duplicateExists(long entityId, String attribute, String value) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM observations WHERE entity_id = ? AND attribute = ? AND LOWER(value) = LOWER(?) "
                            + "AND status IN (?, ?) LIMIT 1")) {
                ps.setLong(1, entityId);
                ps.setString(2, attribute);
                ps.setString(3, value);
                ps.setString(4, ObservationStatus.ACTIVE.db());
                ps.setString(5, ObservationStatus.PENDING_REVIEW.db());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    @Override
    public void setStatus(long id, ObservationStatus status) {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE observations SET status = ? WHERE id = ?")) {
                ps.setString(1, status.db());
                ps.setLong(2, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Optional<Instant> latestObservedAt(long entityId) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT MAX(observed_at) FROM observations WHERE entity_id = ?")) {
                ps.setLong(1, entityId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String max = rs.getString(1);
                        if (max != null) {
                            return Optional.of(Instant.parse(max));
                        }
                    }
                    return Optional.empty();
                }
            }
        });
    }

    private static List<Observation> mapAll(ResultSet rs) throws SQLException {
        List<Observation> out = new ArrayList<>();
        while (rs.next()) {
            out.add(map(rs));
        }
        return out;
    }

    private static Observation map(ResultSet rs) throws SQLException {
        long sourceDoc = rs.getLong("source_doc_id");
        Long sourceDocId = rs.wasNull() ? null : sourceDoc;
        return new Observation(
                rs.getLong("id"),
                rs.getLong("entity_id"),
                rs.getString("attribute"),
                rs.getString("value"),
                rs.getDouble("confidence"),
                rs.getString("evidence"),
                sourceDocId,
                ObservationStatus.fromDb(rs.getString("status")),
                Instant.parse(rs.getString("observed_at")));
    }
}
