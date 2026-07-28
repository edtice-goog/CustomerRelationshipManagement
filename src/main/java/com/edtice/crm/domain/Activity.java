package com.edtice.crm.domain;

import java.time.Instant;

/**
 * A tracked stream of work with a customer — support case, evaluation, or
 * relationship management. All kinds share one structure: documents map to the
 * activity, a rolling assessment is recalculated from full history, and
 * commitments link to it. Only detection and assessment prompts differ by kind.
 */
public record Activity(
        long id,
        String kind,
        ActivityState state,
        String label,
        String token,
        String reference,
        Long primaryEntityId,
        Long opportunityId,
        Instant createdAt,
        Instant closedAt) {

    public static final String SUPPORT = "support";
    public static final String EVALUATION = "evaluation";
    public static final String RELATIONSHIP = "relationship";

    public String displayLabel() {
        if (reference != null && !reference.isBlank()) {
            return kindLabel() + " " + reference;
        }
        if (label != null && !label.isBlank()) {
            return label;
        }
        if (token != null && !token.isBlank()) {
            return token.length() <= 24 ? token : token.substring(0, 21) + "...";
        }
        return kindLabel() + " #" + id;
    }

    public String kindLabel() {
        return switch (kind) {
            case SUPPORT -> "Case";
            case EVALUATION -> "Evaluation";
            case RELATIONSHIP -> "Relationship";
            default -> kind;
        };
    }

    public boolean isOpen() {
        return state == ActivityState.OPEN;
    }
}
