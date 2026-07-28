package com.edtice.crm.cases;

import com.edtice.crm.domain.SourceDocument;
import com.edtice.crm.domain.SupportCase;
import com.edtice.crm.extract.ApiCredentials;
import com.edtice.crm.extract.CaseStatus;
import com.edtice.crm.extract.Extractor;
import com.edtice.crm.store.CaseStore;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;

/**
 * Maps ticket emails to tracked cases and keeps the rolling assessment current.
 * Not ticket management — SalesForce remains the system of record.
 */
@ApplicationScoped
public class CaseService {

    private static final Logger LOG = Logger.getLogger(CaseService.class);

    private final CaseStore cases;
    private final Extractor extractor;

    CaseService(CaseStore cases, Extractor extractor) {
        this.cases = cases;
        this.extractor = extractor;
    }

    public record CaseInfo(long caseId, String token, String caseNumber, String subject,
                           boolean newlyTracked, int emailCount, String instructions) {
    }

    /**
     * If the document carries a ticket token, link it to its case (creating the
     * case on first sight). Returns info the caller can surface, including
     * pull-in instructions for newly tracked cases.
     */
    public Optional<CaseInfo> register(SourceDocument doc) {
        Optional<CaseDetector.Detection> detected = CaseDetector.detect(doc.rawContent());
        if (detected.isEmpty()) {
            return Optional.empty();
        }
        CaseDetector.Detection d = detected.get();
        Optional<SupportCase> existing = cases.byToken(d.token());
        SupportCase supportCase = existing.orElseGet(() -> cases.create(d.token(), d.caseNumber(), d.subject()));
        cases.linkDocument(supportCase.id(), doc.id());
        boolean newlyTracked = existing.isEmpty();
        int count = cases.documentCount(supportCase.id());
        return Optional.of(new CaseInfo(supportCase.id(), supportCase.caseToken(), supportCase.caseNumber(),
                supportCase.subject(), newlyTracked, count, instructions(supportCase, newlyTracked, count)));
    }

    /** Read-only lookup for duplicate submissions — reports tracking state without linking anything. */
    public Optional<CaseInfo> infoFor(String content) {
        return CaseDetector.detect(content).flatMap(d ->
                cases.byToken(d.token()).map(sc -> {
                    int count = cases.documentCount(sc.id());
                    return new CaseInfo(sc.id(), sc.caseToken(), sc.caseNumber(), sc.subject(),
                            false, count, instructions(sc, false, count));
                }));
    }

    public Optional<SupportCase> caseForDocument(long docId) {
        return cases.caseForDocument(docId);
    }

    /**
     * Recalculate the case status from the complete email history. Called every
     * time a new case email finishes extraction, so the current status is always
     * on hand.
     */
    public void reassess(long caseId, Long triggeredByDoc, ApiCredentials credentials) {
        SupportCase supportCase = cases.byId(caseId)
                .orElseThrow(() -> new IllegalStateException("No such case: " + caseId));
        List<SourceDocument> history = cases.documents(caseId);
        CaseStatus status = extractor.assessCase(supportCase, history, credentials);
        cases.insertAssessment(caseId, triggeredByDoc, status.health(),
                status.customerDisposition(), status.customerDispositionNotes(),
                status.technicalProgress(), status.technicalProgressNotes(),
                status.rootCauseProgress(), status.rootCauseNotes(),
                status.summary());
        LOG.infof("Case %d reassessed from %d emails: health=%s, disposition=%s",
                caseId, history.size(), status.health(), status.customerDisposition());
    }

    private static String instructions(SupportCase sc, boolean newlyTracked, int count) {
        if (newlyTracked) {
            return "New support case detected (" + sc.label() + "). Only this email is tracked so far. "
                    + "For an accurate status, search the mailbox for \"" + sc.caseToken()
                    + "\" and submit every matching email to POST /api/ingest — duplicates are "
                    + "detected automatically, so submit them all.";
        }
        return "Email linked to tracked case " + sc.label() + " (" + count
                + " emails). Status will be recalculated automatically.";
    }
}
