package com.edtice.crm.extract;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/** Structured output schema for checking a communication against outstanding commitments. */
public record FulfillmentResult(
        @JsonPropertyDescription("The commitments this communication shows have been fulfilled. Empty list if none.")
        List<FulfilledCommitment> fulfilled) {

    public record FulfilledCommitment(
            @JsonPropertyDescription("The id of the fulfilled commitment, exactly as given in the candidate list.")
            long commitmentId,
            @JsonPropertyDescription("Confidence 0.0-1.0 that the communication demonstrates actual fulfillment.")
            double confidence,
            @JsonPropertyDescription("Short verbatim quote from the communication showing the promised thing was delivered or completed.")
            String evidence) {
    }
}
