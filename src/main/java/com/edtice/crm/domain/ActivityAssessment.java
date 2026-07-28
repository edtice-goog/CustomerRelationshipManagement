package com.edtice.crm.domain;

import java.time.Instant;

/**
 * One recalculation of an activity's status, produced from the full document
 * history at that point in time. The tracks carry kind-specific meaning:
 * for support — customer disposition / technical progress / root cause;
 * for evaluations — evaluator engagement / evaluation progress / fit to the
 * customer's actual business need.
 */
public record ActivityAssessment(
        long id,
        long activityId,
        Long triggeredByDoc,
        String health,
        String customerDisposition,
        String customerDispositionNotes,
        String technicalProgress,
        String technicalProgressNotes,
        String rootCauseProgress,
        String rootCauseNotes,
        String summary,
        Instant createdAt) {
}
