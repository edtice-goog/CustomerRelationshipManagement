package com.edtice.crm.store.jdbc;

import com.edtice.crm.domain.CaseAssessment;
import com.edtice.crm.domain.DocStatus;
import com.edtice.crm.domain.SourceDocument;
import com.edtice.crm.domain.SupportCase;
import com.edtice.crm.store.CaseStore;
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
public class JdbcCaseStore implements CaseStore {

    private static final String CASE_COLS = "id, case_token, case_number, subject, created_at";
    private static final String ASSESS_COLS = "id, case_id, triggered_by_doc, health, "
            + "customer_disposition, disposition_notes, technical_progress, technical_notes, "
            + "root_cause_progress, root_cause_notes, summary, created_at";
    private static final String DOC_COLS =
            "sd.id, sd.source_type, sd.external_id, sd.raw_content, sd.metadata, sd.received_at, sd.status, sd.error";

    private final Database db;

    JdbcCaseStore(Database db) {
        this.db = db;
    }

    @Override
    public SupportCase create(String caseToken, String caseNumber, String subject) {
        Instant now = Instant.now();
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO cases (case_token, case_number, subject, created_at) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, caseToken);
                ps.setString(2, caseNumber);
                ps.setString(3, subject);
                ps.setString(4, now.toString());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return new SupportCase(keys.getLong(1), caseToken, caseNumber, subject, now);
                }
            }
        });
    }

    @Override
    public Optional<SupportCase> byToken(String caseToken) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + CASE_COLS + " FROM cases WHERE case_token = ?")) {
                ps.setString(1, caseToken);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapCase(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public Optional<SupportCase> byId(long id) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + CASE_COLS + " FROM cases WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapCase(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<SupportCase> listAll() {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + CASE_COLS + " FROM cases ORDER BY created_at DESC, id DESC")) {
                try (ResultSet rs = ps.executeQuery()) {
                    List<SupportCase> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(mapCase(rs));
                    }
                    return out;
                }
            }
        });
    }

    @Override
    public void linkDocument(long caseId, long docId) {
        db.with(c -> {
            try (PreparedStatement check = c.prepareStatement(
                    "SELECT 1 FROM case_documents WHERE case_id = ? AND source_doc_id = ?")) {
                check.setLong(1, caseId);
                check.setLong(2, docId);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        return null;
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO case_documents (case_id, source_doc_id) VALUES (?, ?)")) {
                ps.setLong(1, caseId);
                ps.setLong(2, docId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Optional<SupportCase> caseForDocument(long docId) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT c.id, c.case_token, c.case_number, c.subject, c.created_at "
                            + "FROM cases c JOIN case_documents cd ON cd.case_id = c.id WHERE cd.source_doc_id = ?")) {
                ps.setLong(1, docId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapCase(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<SourceDocument> documents(long caseId) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + DOC_COLS + " FROM source_documents sd "
                            + "JOIN case_documents cd ON cd.source_doc_id = sd.id "
                            + "WHERE cd.case_id = ? ORDER BY sd.received_at, sd.id")) {
                ps.setLong(1, caseId);
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
    public int documentCount(long caseId) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM case_documents WHERE case_id = ?")) {
                ps.setLong(1, caseId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }

    @Override
    public CaseAssessment insertAssessment(long caseId, Long triggeredByDoc, String health,
                                           String customerDisposition, String customerDispositionNotes,
                                           String technicalProgress, String technicalProgressNotes,
                                           String rootCauseProgress, String rootCauseNotes,
                                           String summary) {
        Instant now = Instant.now();
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO case_assessments (case_id, triggered_by_doc, health, customer_disposition, "
                            + "disposition_notes, technical_progress, technical_notes, root_cause_progress, "
                            + "root_cause_notes, summary, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, caseId);
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
                    return new CaseAssessment(keys.getLong(1), caseId, triggeredByDoc, health,
                            customerDisposition, customerDispositionNotes, technicalProgress,
                            technicalProgressNotes, rootCauseProgress, rootCauseNotes, summary, now);
                }
            }
        });
    }

    @Override
    public List<CaseAssessment> assessments(long caseId) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + ASSESS_COLS + " FROM case_assessments WHERE case_id = ? ORDER BY created_at DESC, id DESC")) {
                ps.setLong(1, caseId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<CaseAssessment> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(mapAssessment(rs));
                    }
                    return out;
                }
            }
        });
    }

    @Override
    public Optional<CaseAssessment> latestAssessment(long caseId) {
        List<CaseAssessment> all = assessments(caseId);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    private static SupportCase mapCase(ResultSet rs) throws SQLException {
        return new SupportCase(
                rs.getLong(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                Instant.parse(rs.getString(5)));
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

    private static CaseAssessment mapAssessment(ResultSet rs) throws SQLException {
        long doc = rs.getLong("triggered_by_doc");
        Long triggeredBy = rs.wasNull() ? null : doc;
        return new CaseAssessment(
                rs.getLong("id"),
                rs.getLong("case_id"),
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
