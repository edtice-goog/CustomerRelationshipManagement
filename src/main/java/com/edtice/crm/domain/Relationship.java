package com.edtice.crm.domain;

/** Directed edge between entities, e.g. person works_at organization. */
public record Relationship(long id, long fromEntity, long toEntity, String kind, Long sourceDocId) {

    public static final String WORKS_AT = "works_at";
}
