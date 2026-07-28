package com.edtice.crm.store.jdbc;

import com.edtice.crm.domain.Relationship;
import com.edtice.crm.store.Database;
import com.edtice.crm.store.RelationshipStore;
import jakarta.inject.Singleton;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

@Singleton
public class JdbcRelationshipStore implements RelationshipStore {

    private final Database db;

    JdbcRelationshipStore(Database db) {
        this.db = db;
    }

    @Override
    public void ensure(long fromEntity, long toEntity, String kind, Long sourceDocId) {
        db.with(c -> {
            try (PreparedStatement check = c.prepareStatement(
                    "SELECT 1 FROM relationships WHERE from_entity = ? AND to_entity = ? AND kind = ?")) {
                check.setLong(1, fromEntity);
                check.setLong(2, toEntity);
                check.setString(3, kind);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        return null;
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO relationships (from_entity, to_entity, kind, source_doc_id) VALUES (?, ?, ?, ?)")) {
                ps.setLong(1, fromEntity);
                ps.setLong(2, toEntity);
                ps.setString(3, kind);
                if (sourceDocId == null) {
                    ps.setNull(4, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(4, sourceDocId);
                }
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<Relationship> from(long entityId) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, from_entity, to_entity, kind, source_doc_id FROM relationships WHERE from_entity = ?")) {
                ps.setLong(1, entityId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Relationship> out = new ArrayList<>();
                    while (rs.next()) {
                        long src = rs.getLong("source_doc_id");
                        Long srcId = rs.wasNull() ? null : src;
                        out.add(new Relationship(rs.getLong("id"), rs.getLong("from_entity"),
                                rs.getLong("to_entity"), rs.getString("kind"), srcId));
                    }
                    return out;
                }
            }
        });
    }
}
