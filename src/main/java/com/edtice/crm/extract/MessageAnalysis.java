package com.edtice.crm.extract;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * The structured output schema for extraction. The Anthropic SDK derives a JSON
 * schema from these records and guarantees the model's response parses into them.
 */
public record MessageAnalysis(
        @JsonPropertyDescription("Every person mentioned or involved in the communication. The primary correspondent (the author/sender) must be first.")
        List<ExtractedPerson> people,
        @JsonPropertyDescription("Every company or organization mentioned as an employer or business party.")
        List<ExtractedOrganization> organizations,
        @JsonPropertyDescription("Commitments or promises made in this communication — concrete things one party agreed to do for another. Empty list if none.")
        List<ExtractedCommitment> commitments,
        @JsonPropertyDescription("Overall sentiment of the communication from the customer's perspective: positive, neutral, or negative.")
        String sentiment,
        @JsonPropertyDescription("One-sentence summary of what this communication is about.")
        String summary,
        @JsonPropertyDescription("Signal for whether this communication is part of a prospect's evaluation of our product or service.")
        EvaluationSignal evaluationSignal) {

    public record ExtractedPerson(
            @JsonPropertyDescription("Full name. Empty string if unknown.")
            String fullName,
            @JsonPropertyDescription("Email address. Empty string if not present.")
            String email,
            @JsonPropertyDescription("Phone number as written. Empty string if not present.")
            String phone,
            @JsonPropertyDescription("Job title, e.g. from an email signature. Empty string if not present.")
            String title,
            @JsonPropertyDescription("Company/organization this person belongs to. Empty string if not present.")
            String company,
            @JsonPropertyDescription("Postal or street address, e.g. from a signature block. Empty string if not present.")
            String address,
            @JsonPropertyDescription("Confidence 0.0-1.0 that this person and these attributes are correctly extracted.")
            double confidence,
            @JsonPropertyDescription("Short verbatim quote from the message that supports this extraction.")
            String evidence) {
    }

    public record ExtractedCommitment(
            @JsonPropertyDescription("What was promised, stated concisely, e.g. 'Send the data import template'.")
            String description,
            @JsonPropertyDescription("Full name of the person who made the commitment (who owes it). Empty string if unclear.")
            String owedBy,
            @JsonPropertyDescription("Full name of the person the commitment was made to. Empty string if unclear.")
            String owedTo,
            @JsonPropertyDescription("Due date or timeframe exactly as stated, e.g. 'by Friday', 'mid-August'. Empty string if none given.")
            String dueDate,
            @JsonPropertyDescription("Confidence 0.0-1.0 that this is a real commitment, correctly attributed.")
            double confidence,
            @JsonPropertyDescription("Short verbatim quote from the message containing the commitment.")
            String evidence) {
    }

    public record EvaluationSignal(
            @JsonPropertyDescription("True when this communication is part of a prospective customer evaluating our product/service: demo requests, trials, proofs of concept, technical validation, purchase evaluation. False for routine support of an existing deployment and ordinary relationship correspondence.")
            boolean partOfEvaluation,
            @JsonPropertyDescription("Short stable name for the evaluation, e.g. 'Atlas Freight Logistics inventory tooling evaluation'. Empty string when partOfEvaluation is false.")
            String name,
            @JsonPropertyDescription("Confidence 0.0-1.0 that this communication is evaluation activity.")
            double confidence,
            @JsonPropertyDescription("Short verbatim quote supporting the signal. Empty string when partOfEvaluation is false.")
            String evidence) {
    }

    public record ExtractedOrganization(
            @JsonPropertyDescription("Organization name.")
            String name,
            @JsonPropertyDescription("Website or domain. Empty string if not present.")
            String website,
            @JsonPropertyDescription("Confidence 0.0-1.0.")
            double confidence,
            @JsonPropertyDescription("Short verbatim quote from the message that supports this extraction.")
            String evidence) {
    }
}
