package com.edtice.crm.extract;

import com.edtice.crm.domain.Activity;
import com.edtice.crm.domain.SourceDocument;

import java.util.List;

public interface Extractor {

    /** Analyze with the given credentials, or the server default when null/blank. */
    MessageAnalysis analyze(SourceDocument doc, ApiCredentials credentials);

    /** Assess an activity (support case, evaluation, relationship) from its complete document history. */
    AssessmentResult assessActivity(Activity activity, List<SourceDocument> history, ApiCredentials credentials);

    /**
     * Check whether a communication fulfills any of the outstanding commitments in
     * candidatesText (one per line, each prefixed with its id).
     */
    FulfillmentResult checkFulfillment(SourceDocument doc, String candidatesText, ApiCredentials credentials);

    /**
     * Judge whether two entity profiles refer to the same real-world entity.
     * priorHistory carries earlier housekeeping deliberations for this pair (may be empty).
     */
    MergeVerdict judgeMerge(String profileA, String profileB, String priorHistory, ApiCredentials credentials);

    /**
     * Cheap connectivity check (no tokens billed). Returns a human-readable detail
     * string on success; throws with a useful message on failure.
     */
    String verifyConnectivity(ApiCredentials credentials);
}
