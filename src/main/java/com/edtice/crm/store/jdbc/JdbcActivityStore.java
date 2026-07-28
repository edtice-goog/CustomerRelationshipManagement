package com.edtice.crm.store.jdbc;

import com.edtice.crm.domain.Activity;
import com.edtice.crm.domain.ActivityAssessment;
import com.edtice.crm.domain.ActivityState;
import com.edtice.crm.domain.DocStatus;
import com.edtice.crm.domain.SourceDocument;
import com.edtice.crm.store.ActivityStore;
import com.edtice.crm.store.Database;
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
public class JdbcActivityStore implements ActivityStore {

    private static final String COLS =
            "id, kind, state, label, token, reference, primary_entity_id, opportunity_id, created_at, closed_at";
    private static final String ASSESS_COLS = "id, activity_id, triggered_by_doc, health, "
            + "customer_disposition, disposition_notes, technical_progress, technical_notes, "
            + "root_cause_progress, root_cause_notes, summary, created_at";
    private static final String DOC_COLS =
            "sd.id, sd.source_type, sd.external_id, sd.raw_content, sd.metadata, sd.received_at, sd.status, sd.error";

    private final Database db;

    JdbcActivityStore(Database db) {
        this.db = db;
    }

    @Override
    public Activity create(String kind, String label, String token, String reference, Long primaryEntityId) {
        Instant now = Instant.now();
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO activities (kind, state, label, token, reference, primary_entity_id, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, kind);
                ps.setString(2, ActivityState.OPEN.db());
                ps.setString(3, label);
                ps.setString(4, token);
                ps.setString(5, reference);
                if (primaryEntityId == null) {
                    ps.setNull(6, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(6, primaryEntityId);
                }
                ps.setString(7, now.toString());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return new Activity(keys.getLong(1), kind, ActivityState.OPEN, label, token,
                            reference, primaryEntityId, null, now, null);
                }
            }
        });
    }

    @Override
    public Optional<Activity> byId(long id) {
        return one("SELECT " + COLS + " FROM activities WHERE id = ?", ps -> ps.setLong(1, id));
    }

    @Override
    public Optional<Activity> byToken(String token) {
        return one("SELECT " + COLS + " FROM activities WHERE token = ?", ps -> ps.setString(1, token));
    }

    @Override
    public List<Activity> list(String kind, ActivityState state) {
        StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM activities WHERE 1=1");
        List<String> params = new ArrayList<>();
        if (kind != null && !kind.isBlank()) {
            sql.append(" AND kind = ?");
            params.add(kind);
        }
        if (state != null) {
            sql.append(" AND state = ?");
            params.add(state.db());
        }
        sql.append(" ORDER BY created_at DESC, id DESC");
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setString(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    List<Activity> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                    return out;
                }
            }
        });
    }

    @Override
    public Optional<Activity> openByKindAndEntity(String kind, long primaryEntityId) {
        return one("SELECT " + COLS + " FROM activities WHERE kind = ? AND state = ? AND primary_entity_id = ? "
                        + "ORDER BY created_at DESC LIMIT 1",
                ps -> {
                    ps.setString(1, kind);
                    ps.setString(2, ActivityState.OPEN.db());
                    ps.setLong(3, primaryEntityId);
                });
    }

    @Override
    public void setState(long id, ActivityState state) {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE activities SET state = ?, closed_at = ? WHERE id = ?")) {
                ps.setString(1, state.db());
                ps.setString(2, state == ActivityState.CLOSED ? Instant.now().toString() : null);
                ps.setLong(3, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void setOpportunity(long id, Long opportunityId) {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE activities SET opportunity_id = ? WHERE id = ?")) {
                if (opportunityId == null) {
                    ps.setNull(1, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(1, opportunityId);
                }
                ps.setLong(2, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void linkDocument(long activityId, long docId) {
        db.with(c -> {
            try (PreparedStatement check = c.prepareStatement(
                    "SELECT 1 FROM activity_documents WHERE activity_id = ? AND source_doc_id = ?")) {
                check.setLong(1, activityId);
                check.setLong(2, docId);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        return null;
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO activity_documents (activity_id, source_doc_id) VALUES (?, ?)")) {
                ps.setLong(1, activityId);
                ps.setLong(2, docId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<Activity> activitiesForDocument(long docId) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT a.id, a.kind, a.state, a.label, a.token, a.reference, a.primary_entity_id, "
                            + "a.opportunity_id, a.created_at, a.closed_at "
                            + "FROM activities a JOIN activity_documents ad ON ad.activity_id = a.id "
                            + "WHERE ad.source_doc_id = ? ORDER BY a.id")) {
                ps.setLong(1, docId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Activity> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                    return out;
                }
            }
        });
    }

    @Override
    public List<SourceDocument> documents(long activityId) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + DOC_COLS + " FROM source_documents sd "
                            + "JOIN activity_documents ad ON ad.source_doc_id = sd.id "
                            + "WHERE ad.activity_id = ? ORDER BY sd.received_at, sd.id")) {
                ps.setLong(1, activityId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<SourceDocument> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(mapDoc(rs));
                    }
                    return out;
                }
            }
        });
    }

    @Override
    public int documentCount(long activityId) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM activity_documents WHERE activity_id = ?")) {
                ps.setLong(1, activityId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }

    @Override
    public ActivityAssessment insertAssessment(long activityId, Long triggeredByDoc, String health,
                                               String customerDisposition, String customerDispositionNotes,
                                               String technicalProgress, String technicalProgressNotes,
                                               String rootCauseProgress, String rootCauseNotes,
                                               String summary) {
        Instant now = Instant.now();
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO activity_assessments (activity_id, triggered_by_doc, health, customer_disposition, "
                            + "disposition_notes, technical_progress, technical_notes, root_cause_progress, "
                            + "root_cause_notes, summary, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, activityId);
                if (triggeredByDoc == null) {
                    ps.setNull(2, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(2, triggeredByDoc);
                }
                ps.setString(3, health);
                ps.setString(4, customerDisposition);
                ps.setString(5, customerDispositionNotes);
                ps.setString(6, technicalProgress);
                ps.setString(7, technicalProgressNotes);
                ps.setString(8, rootCauseProgress);
                ps.setString(9, rootCauseNotes);
                ps.setString(10, summary);
                ps.setString(11, now.toString());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return new ActivityAssessment(keys.getLong(1), activityId, triggeredByDoc, health,
                            customerDisposition, customerDispositionNotes, technicalProgress,
                            technicalProgressNotes, rootCauseProgress, rootCauseNotes, summary, now);
                }
            }
        });
    }

    @Override
    public List<ActivityAssessment> assessments(long activityId) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + ASSESS_COLS + " FROM activity_assessments WHERE activity_id = ? "
                            + "ORDER BY created_at DESC, id DESC")) {
                ps.setLong(1, activityId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<ActivityAssessment> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(mapAssessment(rs));
                    }
                    return out;
                }
            }
        });
    }

    @Override
    public Optional<ActivityAssessment> latestAssessment(long activityId) {
        List<ActivityAssessment> all = assessments(activityId);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    // --- helpers ---

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private Optional<Activity> one(String sql, Binder binder) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                binder.bind(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        });
    }

    private static Activity map(ResultSet rs) throws SQLException {
        long primary = rs.getLong("primary_entity_id");
        Long primaryEntityId = rs.wasNull() ? null : primary;
        long opp = rs.getLong("opportunity_id");
        Long opportunityId = rs.wasNull() ? null : opp;
        String closedAt = rs.getString("closed_at");
        return new Activity(
                rs.getLong("id"),
                rs.getString("kind"),
                ActivityState.fromDb(rs.getString("state")),
                rs.getString("label"),
                rs.getString("token"),
                rs.getString("reference"),
                primaryEntityId,
                opportunityId,
                Instant.parse(rs.getString("created_at")),
                closedAt == null ? null : Instant.parse(closedAt));
    }

    private static SourceDocument mapDoc(ResultSet rs) throws SQLException {
        return new SourceDocument(
                rs.getLong(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                Instant.parse(rs.getString(6)),
                DocStatus.fromDb(rs.getString(7)),
                rs.getString(8));
    }

    private static ActivityAssessment mapAssessment(ResultSet rs) throws SQLException {
        long doc = rs.getLong("triggered_by_doc");
        Long triggeredBy = rs.wasNull() ? null : doc;
        return new ActivityAssessment(
                rs.getLong("id"),
                rs.getLong("activity_id"),
                triggeredBy,
                rs.getString("health"),
                rs.getString("customer_disposition"),
                rs.getString("disposition_notes"),
                rs.getString("technical_progress"),
                rs.getString("technical_notes"),
                rs.getString("root_cause_progress"),
                rs.getString("root_cause_notes"),
                rs.getString("summary"),
                Instant.parse(rs.getString("created_at")));
    }
}
