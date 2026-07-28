package com.edtice.crm.domain;

public enum DocStatus {
    STAGED,
    PROCESSING,
    EXTRACTED,
    ERROR;

    public String db() {
        return name().toLowerCase();
    }

    public static DocStatus fromDb(String value) {
        return valueOf(value.toUpperCase());
    }
}
