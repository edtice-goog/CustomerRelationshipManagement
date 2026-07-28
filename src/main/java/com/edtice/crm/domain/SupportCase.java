package com.edtice.crm.domain;

import java.time.Instant;

/**
 * A tracked support case, recognized from ticket-system tokens in emails
 * (v1: SalesForce ref:_..._:ref thread tokens). This is not ticket management —
 * the system of record stays in SalesForce; we map the case to its emails and
 * keep a rolling assessment of how it's going.
 */
public record SupportCase(long id, String caseToken, String caseNumber, String subject, Instant createdAt) {

    public String label() {
        if (caseNumber != null && !caseNumber.isBlank()) {
            return "Case " + caseNumber;
        }
        String t = caseToken;
        return t.length() <= 24 ? t : t.substring(0, 21) + "...";
    }
}
