package com.edtice.crm.extract;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Structured output schema for a support-case assessment, recalculated from the
 * full email history every time a new case email arrives.
 */
public record CaseStatus(
        @JsonPropertyDescription("The customer's current disposition toward the case. One of: satisfied, calm, concerned, frustrated, escalating.")
        String customerDisposition,
        @JsonPropertyDescription("One or two sentences explaining the disposition, citing the latest signals.")
        String customerDispositionNotes,
        @JsonPropertyDescription("Whether the case is proceeding well on technical grounds. One of: resolved, on_track, slow, stalled, blocked, regressing.")
        String technicalProgress,
        @JsonPropertyDescription("One or two sentences on the technical trajectory: what has been tried, what worked, what hasn't.")
        String technicalProgressNotes,
        @JsonPropertyDescription("Whether the work is getting to the root of the customer's actual problem, not just symptoms. One of: resolved, identified, narrowing, investigating, unknown, misdiagnosed.")
        String rootCauseProgress,
        @JsonPropertyDescription("One or two sentences on root-cause understanding — is the effort aimed at the customer's real underlying problem?")
        String rootCauseNotes,
        @JsonPropertyDescription("Overall case health: green (going well), yellow (needs attention), red (at risk / customer relationship in danger).")
        String health,
        @JsonPropertyDescription("2-4 sentence status summary of the case as of the most recent email, written for someone catching up.")
        String summary) {
}
