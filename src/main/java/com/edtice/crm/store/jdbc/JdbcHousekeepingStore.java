package com.edtice.crm.store.jdbc;

import com.edtice.crm.domain.HousekeepingRecord;
import com.edtice.crm.domain.HousekeepingStatus;
import com.edtice.crm.store.Database;
import com.edtice.crm.store.HousekeepingStore;
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
public class JdbcHousekeepingStore implements HousekeepingStore {

    private static final String COLS = "id, kind, status, evidence, reasoning, decided_by, "
            + "confidence, prior_record_id, created_at, decided_at";

    private final Database db;

    JdbcHousekeepingStore(Database db) {
        this.db = db;
    }

    @Override
    public HousekeepingRecord create(String kind, List<Long> entityIds, String evidence, String reasoning,
                                     String decidedBy, double confidence, HousekeepingStatus status,
                                     Long priorRecordId) {
        Instant now = Instant.now();
        Instant decidedAt = status == HousekeepingStatus.OPEN ? null : now;
        return db.with(c -> {
            long id;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO housekeeping_records (kind, status, evidence, reasoning, decided_by, "
                            + "confidence, prior_record_id, created_at, decided_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, kind);
                ps.setString(2, status.db());
                ps.setString(3, evidence);
                ps.setString(4, reasoning);
                ps.setString(5, decidedBy);
                ps.setDouble(6, confidence);
                if (priorRecordId == null) {
                    ps.setNull(7, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(7, priorRecordId);
                }
                ps.setString(8, now.toString());
                ps.setString(9, decidedAt == null ? null : decidedAt.toString());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getLong(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO housekeeping_record_entities (record_id, entity_id) VALUES (?, ?)")) {
                for (Long entityId : entityIds) {
                    ps.setLong(1, id);
                    ps.setLong(2, entityId);
                    ps.executeUpdate();
                }
            }
            return new HousekeepingRecord(id, kind, status, evidence, reasoning, decidedBy,
                    confidence, priorRecordId, now, decidedAt, List.copyOf(entityIds));
        });
    }

    @Override
    public Optional<HousekeepingRecord> byId(long id) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + COLS + " FROM housekeeping_records WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(c, rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<HousekeepingRecord> listAll() {
        return query("SELECT " + COLS + " FROM housekeeping_records ORDER BY created_at DESC, id DESC", ps -> {
        });
    }

    @Override
    public List<HousekeepingRecord> byStatus(HousekeepingStatus status) {
        return query("SELECT " + COLS + " FROM housekeeping_records WHERE status = ? ORDER BY created_at DESC, id DESC",
                ps -> ps.setString(1, status.db()));
    }

    @Override
    public List<HousekeepingRecord> forEntity(long entityId) {
        return query("SELECT " + qualified() + " FROM housekeeping_records r "
                        + "JOIN housekeeping_record_entities re ON re.record_id = r.id "
                        + "WHERE re.entity_id = ? ORDER BY r.created_at DESC, r.id DESC",
                ps -> ps.setLong(1, entityId));
    }

    @Override
    public List<HousekeepingRecord> forPair(long entityA, long entityB) {
        return query("SELECT " + qualified() + " FROM housekeeping_records r "
                        + "JOIN housekeeping_record_entities ra ON ra.record_id = r.id AND ra.entity_id = ? "
                        + "JOIN housekeeping_record_entities rb ON rb.record_id = r.id AND rb.entity_id = ? "
                        + "ORDER BY r.created_at DESC, r.id DESC",
                ps -> {
                    ps.setLong(1, entityA);
                    ps.setLong(2, entityB);
                });
    }

    @Override
    public void decide(long id, HousekeepingStatus status, String decidedBy, String reasoning, double confidence) {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE housekeeping_records SET status = ?, decided_by = ?, reasoning = ?, "
                            + "confidence = ?, decided_at = ? WHERE id = ?")) {
                ps.setString(1, status.db());
                ps.setString(2, decidedBy);
                ps.setString(3, reasoning);
                ps.setDouble(4, confidence);
                ps.setString(5, Instant.now().toString());
                ps.setLong(6, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // --- helpers ---

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private List<HousekeepingRecord> query(String sql, Binder binder) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                binder.bind(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    List<HousekeepingRecord> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(map(c, rs));
                    }
                    return out;
                }
            }
        });
    }

    private static String qualified() {
        return "r.id, r.kind, r.status, r.evidence, r.reasoning, r.decided_by, "
                + "r.confidence, r.prior_record_id, r.created_at, r.decided_at";
    }

    private static HousekeepingRecord map(Connection c, ResultSet rs) throws SQLException {
        long priorRaw = rs.getLong("prior_record_id");
        Long priorId = rs.wasNull() ? null : priorRaw;
        String decidedAtRaw = rs.getString("decided_at");
        long id = rs.getLong("id");
        List<Long> entityIds = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT entity_id FROM housekeeping_record_entities WHERE record_id = ? ORDER BY entity_id")) {
            ps.setLong(1, id);
            try (ResultSet ers = ps.executeQuery()) {
                while (ers.next()) {
                    entityIds.add(ers.getLong(1));
                }
            }
        }
        return new HousekeepingRecord(
                id,
                rs.getString("kind"),
                HousekeepingStatus.fromDb(rs.getString("status")),
                rs.getString("evidence"),
                rs.getString("reasoning"),
                rs.getString("decided_by"),
                rs.getDouble("confidence"),
                priorId,
                Instant.parse(rs.getString("created_at")),
                decidedAtRaw == null ? null : Instant.parse(decidedAtRaw),
                entityIds);
    }
}
