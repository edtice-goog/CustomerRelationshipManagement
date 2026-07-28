package com.edtice.crm.domain;

import java.time.Instant;

/** A person or organization we know about. Everything else we know is an Observation. */
public record Entity(long id, String kind, String displayName, Instant createdAt) {

    public static final String PERSON = "person";
    public static final String ORGANIZATION = "organization";

    public boolean isPerson() {
        return PERSON.equals(kind);
    }
}
