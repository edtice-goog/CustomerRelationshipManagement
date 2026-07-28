package com.edtice.crm.activities;

import com.edtice.crm.cases.CaseDetector;
import com.edtice.crm.domain.Activity;
import com.edtice.crm.domain.ActivityState;
import com.edtice.crm.domain.Entity;
import com.edtice.crm.domain.Observation;
import com.edtice.crm.domain.ObservationStatus;
import com.edtice.crm.domain.SourceDocument;
import com.edtice.crm.extract.ApiCredentials;
import com.edtice.crm.extract.AssessmentResult;
import com.edtice.crm.extract.Extractor;
import com.edtice.crm.extract.MessageAnalysis;
import com.edtice.crm.store.ActivityStore;
import com.edtice.crm.store.EntityStore;
import com.edtice.crm.store.ObservationStore;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;

/**
 * Maps communications to activities (support cases, evaluations, relationship
 * work) and keeps each activity's rolling assessment current. Also enforces the
 * next-step rule: an open activity with no linked commitment gets an implicit
 * commitment, due immediately, to set a next step.
 */
@ApplicationScoped
public class ActivityService {

    private static final Logger LOG = Logger.getLogger(ActivityService.class);

    private final ActivityStore activities;
    private final EntityStore entities;
    private final ObservationStore observations;
    private final Extractor extractor;
    private final String ownerName;
    private final double evaluationThreshold;

    ActivityService(ActivityStore activities, EntityStore entities, ObservationStore observations,
                    Extractor extractor,
                    @ConfigProperty(name = "crm.owner") String ownerName,
                    @ConfigProperty(name = "crm.activities.evaluationThreshold") double evaluationThreshold) {
        this.activities = activities;
        this.entities = entities;
        this.observations = observations;
        this.extractor = extractor;
        this.ownerName = ownerName;
        this.evaluationThreshold = evaluationThreshold;
    }

    public record ActivityInfo(long activityId, String kind, String label, String token,
                               boolean newlyTracked, int documentCount, String instructions) {
    }

    /**
     * Deterministic registration at ingest time: recognize ticket-system tokens
     * (SalesForce ref:...:ref) and link the document to its support activity.
     */
    public Optional<ActivityInfo> registerSupport(SourceDocument doc) {
        Optional<CaseDetector.Detection> detected = CaseDetector.detect(doc.rawContent());
        if (detected.isEmpty()) {
            return Optional.empty();
        }
        CaseDetector.Detection d = detected.get();
        Optional<Activity> existing = activities.byToken(d.token());
        Activity activity = existing.orElseGet(() ->
                activities.create(Activity.SUPPORT, d.subject(), d.token(), d.caseNumber(), null));
        activities.linkDocument(activity.id(), doc.id());
        boolean newlyTracked = existing.isEmpty();
        int count = activities.documentCount(activity.id());
        return Optional.of(new ActivityInfo(activity.id(), activity.kind(), activity.displayLabel(),
                activity.token(), newlyTracked, count, supportInstructions(activity, newlyTracked, count)));
    }

    /** Read-only lookup for duplicate submissions. */
    public Optional<ActivityInfo> infoFor(String content) {
        return CaseDetector.detect(content).flatMap(d ->
                activities.byToken(d.token()).map(a -> {
                    int count = activities.documentCount(a.id());
                    return new ActivityInfo(a.id(), a.kind(), a.displayLabel(), a.token(),
                            false, count, supportInstructions(a, false, count));
                }));
    }

    /**
     * Model-signaled registration during extraction: when a communication is part
     * of an evaluation, link it to the open evaluation anchored to the customer
     * (org preferred, else person) — or start tracking a new one.
     */
    public Optional<Activity> registerEvaluation(SourceDocument doc, MessageAnalysis analysis,
                                                 Entity primaryOrg, Entity primaryPerson) {
        MessageAnalysis.EvaluationSignal signal = analysis.evaluationSignal();
        if (signal == null || !signal.partOfEvaluation() || signal.confidence() < evaluationThreshold) {
            return Optional.empty();
        }
        Entity anchor = primaryOrg != null ? primaryOrg : primaryPerson;
        if (anchor == null) {
            LOG.warnf("Evaluation signal on document %d but no anchor entity; skipping", doc.id());
            return Optional.empty();
        }
        Activity activity = activities.openByKindAndEntity(Activity.EVALUATION, anchor.id())
                .orElseGet(() -> {
                    String label = signal.name() == null || signal.name().isBlank()
                            ? anchor.displayName() + " evaluation" : signal.name();
                    Activity created = activities.create(Activity.EVALUATION, label, null, null, anchor.id());
                    LOG.infof("New evaluation tracked: '%s' (activity %d, anchored to %s)",
                            label, created.id(), anchor.displayName());
                    return created;
                });
        activities.linkDocument(activity.id(), doc.id());
        return Optional.of(activity);
    }

    public List<Activity> activitiesForDocument(long docId) {
        return activities.activitiesForDocument(docId);
    }

    /** Recalculate the activity's status from its complete document history. */
    public void reassess(long activityId, Long triggeredByDoc, ApiCredentials credentials) {
        Activity activity = activities.byId(activityId)
                .orElseThrow(() -> new IllegalStateException("No such activity: " + activityId));
        List<SourceDocument> history = activities.documents(activityId);
        AssessmentResult status = extractor.assessActivity(activity, history, credentials);
        activities.insertAssessment(activityId, triggeredByDoc, status.health(),
                status.customerDisposition(), status.customerDispositionNotes(),
                status.technicalProgress(), status.technicalProgressNotes(),
                status.rootCauseProgress(), status.rootCauseNotes(),
                status.summary());
        LOG.infof("Activity %d (%s) reassessed from %d documents: health=%s",
                activityId, activity.kind(), history.size(), status.health());
    }

    /**
     * The next-step rule: an open activity without an active linked commitment
     * gets an implicit commitment — owed by the CRM owner, due immediately — to
     * set a next step. Nothing open is allowed to sit with no one owing anything.
     */
    public boolean ensureNextStep(Activity activity) {
        if (!activity.isOpen() || observations.activeCommitmentExists(activity.id())) {
            return false;
        }
        Entity owner = entities.findByName(Entity.PERSON, ownerName)
                .orElseGet(() -> entities.create(Entity.PERSON, ownerName));
        observations.insert(owner.id(), Observation.COMMITMENT,
                "Set a next step for " + activity.kindLabel().toLowerCase() + " '" + activity.displayLabel()
                        + "' — due immediately",
                1.0,
                "(implicit: open activity with no committed next step)",
                null, activity.id(), ObservationStatus.ACTIVE);
        LOG.infof("Implicit next-step commitment created for activity %d ('%s')",
                activity.id(), activity.displayLabel());
        return true;
    }

    /** Daily safety net — catches open activities that drifted commitment-less between emails. */
    @Scheduled(cron = "{crm.activities.nextStepCron}")
    void nextStepSweep() {
        int created = 0;
        for (Activity activity : activities.list(null, ActivityState.OPEN)) {
            try {
                if (ensureNextStep(activity)) {
                    created++;
                }
            } catch (Exception e) {
                LOG.errorf(e, "Next-step check failed for activity %d", activity.id());
            }
        }
        if (created > 0) {
            LOG.infof("Next-step sweep created %d implicit commitments", created);
        }
    }

    /** True for commitments generated by the next-step rule rather than extracted from a communication. */
    public static boolean isImplicit(Observation commitment) {
        return commitment.evidence() != null && commitment.evidence().startsWith("(implicit");
    }

    /**
     * Manually mark a commitment fulfilled (e.g. it was handled on a phone call).
     * Per the next-step rule, fulfilling the last active commitment on an open
     * activity immediately generates a fresh implicit commitment to set the next step.
     */
    public void fulfillCommitment(long observationId) {
        Observation commitment = observations.byId(observationId)
                .orElseThrow(() -> new IllegalStateException("No such observation: " + observationId));
        if (!Observation.COMMITMENT.equals(commitment.attribute())) {
            throw new IllegalStateException("Observation " + observationId + " is not a commitment");
        }
        if (commitment.status() != ObservationStatus.ACTIVE) {
            throw new IllegalStateException("Commitment " + observationId + " is not active ("
                    + commitment.status() + ")");
        }
        observations.setStatus(observationId, ObservationStatus.FULFILLED);
        if (commitment.activityId() != null) {
            activities.byId(commitment.activityId()).ifPresent(this::ensureNextStep);
        }
    }

    /**
     * A real commitment recorded on an activity IS its next step — the implicit
     * "set a next step" commitment is thereby fulfilled. Deterministic, no model call.
     */
    public void fulfillImplicitCommitments(long activityId) {
        for (Observation commitment : observations.commitmentsForActivity(activityId)) {
            if (commitment.status() == ObservationStatus.ACTIVE && isImplicit(commitment)) {
                observations.setStatus(commitment.id(), ObservationStatus.FULFILLED);
                LOG.infof("Implicit commitment %d fulfilled by a real next step on activity %d",
                        commitment.id(), activityId);
            }
        }
    }

    /**
     * Mark the activity complete. Unconditional for now (a future restriction may
     * keep evaluations open until their opportunity is won or lost). Open implicit
     * commitments are retired — a closed activity needs no next step — while real
     * extracted promises stay active: a promise outlives the activity.
     */
    public void close(long activityId) {
        for (Observation commitment : observations.commitmentsForActivity(activityId)) {
            if (commitment.status() == ObservationStatus.ACTIVE && isImplicit(commitment)) {
                observations.setStatus(commitment.id(), ObservationStatus.SUPERSEDED);
            }
        }
        activities.setState(activityId, ActivityState.CLOSED);
    }

    public void reopen(long activityId) {
        activities.setState(activityId, ActivityState.OPEN);
        activities.byId(activityId).ifPresent(this::ensureNextStep);
    }

    private static String supportInstructions(Activity a, boolean newlyTracked, int count) {
        if (newlyTracked) {
            return "New support case detected (" + a.displayLabel() + "). Only this email is tracked so far. "
                    + "For an accurate status, search the mailbox for \"" + a.token()
                    + "\" and submit every matching email to POST /api/ingest — duplicates are "
                    + "detected automatically, so submit them all.";
        }
        return "Email linked to tracked " + a.kindLabel().toLowerCase() + " " + a.displayLabel() + " (" + count
                + " emails). Status will be recalculated automatically.";
    }
}
