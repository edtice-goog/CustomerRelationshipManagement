package com.edtice.crm.domain;

public enum ObservationStatus {
    PENDING_REVIEW,
    ACTIVE,
    REJECTED,
    SUPERSEDED;

    public String db() {
        return name().toLowerCase();
    }

    public static ObservationStatus fromDb(String value) {
        return valueOf(value.toUpperCase());
    }
}
