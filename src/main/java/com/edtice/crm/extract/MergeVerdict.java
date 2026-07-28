package com.edtice.crm.extract;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** Structured output schema for an entity-merge judgment. */
public record MergeVerdict(
        @JsonPropertyDescription("One of: merge (same real-world entity), keep_separate (distinct entities), uncertain (evidence insufficient either way).")
        String verdict,
        @JsonPropertyDescription("Confidence 0.0-1.0 in the verdict.")
        double confidence,
        @JsonPropertyDescription("Statement of evidence: the observable facts bearing on whether these records refer to the same real-world entity — name relationship, shared email domains/phones/addresses, shared relationships, source overlap. Facts only, no conclusion.")
        String evidenceStatement,
        @JsonPropertyDescription("The reasoning behind the verdict: why the evidence is or is not conclusive. If prior housekeeping decisions were provided, state whether this verdict upholds or overturns them and why.")
        String reasoning) {
}
