package com.edtice.crm.store;

import com.edtice.crm.domain.CaseAssessment;
import com.edtice.crm.domain.SourceDocument;
import com.edtice.crm.domain.SupportCase;

import java.util.List;
import java.util.Optional;

public interface CaseStore {

    SupportCase create(String caseToken, String caseNumber, String subject);

    Optional<SupportCase> byToken(String caseToken);

    Optional<SupportCase> byId(long id);

    List<SupportCase> listAll();

    /** Idempotent — linking the same document twice is a no-op. */
    void linkDocument(long caseId, long docId);

    Optional<SupportCase> caseForDocument(long docId);

    /** The case's emails in received order — the history fed to assessment. */
    List<SourceDocument> documents(long caseId);

    int documentCount(long caseId);

    CaseAssessment insertAssessment(long caseId, Long triggeredByDoc, String health,
                                    String customerDisposition, String customerDispositionNotes,
                                    String technicalProgress, String technicalProgressNotes,
                                    String rootCauseProgress, String rootCauseNotes,
                                    String summary);

    /** Newest first. */
    List<CaseAssessment> assessments(long caseId);

    Optional<CaseAssessment> latestAssessment(long caseId);
}
