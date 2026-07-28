package com.edtice.crm.store.jdbc;

import com.edtice.crm.domain.DocStatus;
import com.edtice.crm.domain.SourceDocument;
import com.edtice.crm.store.Database;
import com.edtice.crm.store.StagingStore;
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
public class JdbcStagingStore implements StagingStore {

    private static final String COLS =
            "id, source_type, external_id, raw_content, metadata, received_at, status, error";

    private final Database db;

    JdbcStagingStore(Database db) {
        this.db = db;
    }

    @Override
    public Optional<SourceDocument> insertIfNew(String sourceType, String externalId,
                                                String rawContent, String metadataJson) {
        return db.with(c -> {
            try (PreparedStatement check = c.prepareStatement(
                    "SELECT 1 FROM source_documents WHERE external_id = ?")) {
                check.setString(1, externalId);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        return Optional.empty();
                    }
                }
            }
            Instant now = Instant.now();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO source_documents (source_type, external_id, raw_content, metadata, received_at, status) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, sourceType);
                ps.setString(2, externalId);
                ps.setString(3, rawContent);
                ps.setString(4, metadataJson);
                ps.setString(5, now.toString());
                ps.setString(6, DocStatus.STAGED.db());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return Optional.of(new SourceDocument(keys.getLong(1), sourceType, externalId,
                            rawContent, metadataJson, now, DocStatus.STAGED, null));
                }
            }
        });
    }

    @Override
    public Optional<SourceDocument> byId(long id) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + COLS + " FROM source_documents WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public Optional<SourceDocument> byExternalId(String externalId) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + COLS + " FROM source_documents WHERE external_id = ?")) {
                ps.setString(1, externalId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<SourceDocument> listRecent(int limit) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + COLS + " FROM source_documents ORDER BY received_at DESC, id DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    List<SourceDocument> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                    return out;
                }
            }
        });
    }

    @Override
    public void setStatus(long id, DocStatus status, String error) {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE source_documents SET status = ?, error = ? WHERE id = ?")) {
                ps.setString(1, status.db());
                ps.setString(2, error);
                ps.setLong(3, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static SourceDocument map(ResultSet rs) throws SQLException {
        return new SourceDocument(
                rs.getLong("id"),
                rs.getString("source_type"),
                rs.getString("external_id"),
                rs.getString("raw_content"),
                rs.getString("metadata"),
                Instant.parse(rs.getString("received_at")),
                DocStatus.fromDb(rs.getString("status")),
                rs.getString("error"));
    }
}
