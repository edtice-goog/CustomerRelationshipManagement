package com.edtice.crm.extract;

import com.edtice.crm.domain.SourceDocument;
import com.edtice.crm.domain.SupportCase;

import java.util.List;

public interface Extractor {

    /** Analyze with the given credentials, or the server default when null/blank. */
    MessageAnalysis analyze(SourceDocument doc, ApiCredentials credentials);

    /** Assess a support case from its complete email history (received order). */
    CaseStatus assessCase(SupportCase supportCase, List<SourceDocument> history, ApiCredentials credentials);

    /**
     * Cheap connectivity check (no tokens billed). Returns a human-readable detail
     * string on success; throws with a useful message on failure.
     */
    String verifyConnectivity(ApiCredentials credentials);
}
