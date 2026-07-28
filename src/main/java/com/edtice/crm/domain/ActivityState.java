package com.edtice.crm.domain;

public enum ActivityState {
    OPEN,
    CLOSED;

    public String db() {
        return name().toLowerCase();
    }

    public static ActivityState fromDb(String value) {
        return valueOf(value.toUpperCase());
    }
}
