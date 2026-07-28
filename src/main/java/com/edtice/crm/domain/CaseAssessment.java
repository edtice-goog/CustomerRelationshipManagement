package com.edtice.crm.domain;

import java.time.Instant;

/**
 * One recalculation of a case's status, produced from the full email history at
 * that point in time. The newest assessment is the case's current status; the
 * history shows how the case has trended.
 */
public record CaseAssessment(
        long id,
        long caseId,
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
