package com.edtice.crm.domain;

public enum ObservationStatus {
    PENDING_REVIEW,
    ACTIVE,
    REJECTED,
    SUPERSEDED,
    /** Commitment lifecycle: the promised thing was delivered (detected or manually marked). */
    FULFILLED;

    public String db() {
        return name().toLowerCase();
    }

    public static ObservationStatus fromDb(String value) {
        return valueOf(value.toUpperCase());
    }
}
