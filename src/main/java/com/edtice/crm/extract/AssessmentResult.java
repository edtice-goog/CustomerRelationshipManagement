package com.edtice.crm.extract;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Structured output schema for an activity assessment, recalculated from the
 * full document history every time a new related communication arrives. The
 * three tracks carry kind-specific meaning set by the assessment prompt.
 */
public record AssessmentResult(
        @JsonPropertyDescription("The customer's current disposition toward this activity. Support: satisfied, calm, concerned, frustrated, escalating. Evaluation/relationship: enthusiastic, engaged, lukewarm, cooling, disengaged.")
        String customerDisposition,
        @JsonPropertyDescription("One or two sentences explaining the disposition, citing the latest signals.")
        String customerDispositionNotes,
        @JsonPropertyDescription("Whether the activity is proceeding well on substantive grounds. One of: resolved, on_track, slow, stalled, blocked, regressing.")
        String technicalProgress,
        @JsonPropertyDescription("One or two sentences on the trajectory: what has been done, what worked, what hasn't.")
        String technicalProgressNotes,
        @JsonPropertyDescription("Whether the work addresses the customer's actual underlying problem or goal, not just surface activity. One of: resolved, identified, narrowing, investigating, unknown, misdiagnosed.")
        String rootCauseProgress,
        @JsonPropertyDescription("One or two sentences on whether effort is aimed at the customer's real underlying need.")
        String rootCauseNotes,
        @JsonPropertyDescription("Overall health: green (going well), yellow (needs attention), red (at risk / relationship or outcome in danger).")
        String health,
        @JsonPropertyDescription("2-4 sentence status summary as of the most recent communication, written for someone catching up.")
        String summary) {
}
