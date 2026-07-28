package com.edtice.crm.domain;

public enum HousekeepingStatus {
    OPEN,
    MERGED,
    KEPT_SEPARATE;

    public String db() {
        return name().toLowerCase();
    }

    public static HousekeepingStatus fromDb(String value) {
        return valueOf(value.toUpperCase());
    }
}
