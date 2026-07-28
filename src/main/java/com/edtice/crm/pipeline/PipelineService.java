package com.edtice.crm.pipeline;

import com.edtice.crm.activities.ActivityService;
import com.edtice.crm.domain.Activity;
import com.edtice.crm.domain.DocStatus;
import com.edtice.crm.domain.Entity;
import com.edtice.crm.domain.ObservationStatus;
import com.edtice.crm.domain.Relationship;
import com.edtice.crm.domain.SourceDocument;
import com.edtice.crm.extract.ApiCredentials;
import com.edtice.crm.extract.Extractor;
import com.edtice.crm.extract.MessageAnalysis;
import com.edtice.crm.housekeeping.HousekeepingService;
import com.edtice.crm.store.EntityStore;
import com.edtice.crm.store.ObservationStore;
import com.edtice.crm.store.RelationshipStore;
import com.edtice.crm.store.StagingStore;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Drives a staged document through extraction, entity resolution, activity
 * linkage, and promotion. High-confidence observations go live immediately;
 * the rest wait in the review queue.
 */
@ApplicationScoped
public class PipelineService {

    private static final Logger LOG = Logger.getLogger(PipelineService.class);

    private final StagingStore staging;
    private final EntityStore entities;
    private final ObservationStore observations;
    private final RelationshipStore relationships;
    private final Extractor extractor;
    private final ActivityService activityService;
    private final HousekeepingService housekeeping;
    private final double autoPromoteThreshold;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "extraction-pipeline");
        t.setDaemon(true);
        return t;
    });

    /**
     * Per-document credentials for in-flight jobs. In-memory only: keys are never
     * persisted, and after a restart a reprocess falls back to the server default
     * unless credentials are supplied again.
     */
    private final ConcurrentHashMap<Long, ApiCredentials> jobCredentials = new ConcurrentHashMap<>();

    PipelineService(StagingStore staging, EntityStore entities, ObservationStore observations,
                    RelationshipStore relationships, Extractor extractor, ActivityService activityService,
                    HousekeepingService housekeeping,
                    @ConfigProperty(name = "crm.autoPromoteThreshold") double autoPromoteThreshold) {
        this.staging = staging;
        this.entities = entities;
        this.observations = observations;
        this.relationships = relationships;
        this.extractor = extractor;
        this.activityService = activityService;
        this.housekeeping = housekeeping;
        this.autoPromoteThreshold = autoPromoteThreshold;
    }

    /** Queue a staged document for background extraction with the server-default credentials. */
    public void submit(long docId) {
        submit(docId, null);
    }

    /** Queue a staged document for background extraction, optionally with caller-supplied credentials. */
    public void submit(long docId, ApiCredentials credentials) {
        if (credentials != null && !credentials.isBlank()) {
            jobCredentials.put(docId, credentials);
        }
        worker.submit(() -> {
            try {
                process(docId);
            } catch (Exception e) {
                LOG.errorf(e, "Extraction failed for document %d", docId);
                staging.setStatus(docId, DocStatus.ERROR, e.getMessage());
            } finally {
                jobCredentials.remove(docId);
            }
        });
    }

    void process(long docId) {
        SourceDocument doc = staging.byId(docId)
                .orElseThrow(() -> new IllegalStateException("No such document: " + docId));
        staging.setStatus(docId, DocStatus.PROCESSING, null);

        MessageAnalysis analysis = extractor.analyze(doc, jobCredentials.get(docId));
        List<Entity> createdEntities = new ArrayList<>();
        Entity primaryOrg = null;
        Entity primaryPerson = null;

        // Organizations first so people can link to them.
        if (analysis.organizations() != null) {
            for (MessageAnalysis.ExtractedOrganization org : analysis.organizations()) {
                if (isBlank(org.name())) {
                    continue;
                }
                Entity orgEntity = resolveOrCreate(Entity.ORGANIZATION, org.name(), createdEntities);
                if (primaryOrg == null) {
                    primaryOrg = orgEntity;
                }
                record(orgEntity.id(), "website", org.website(), org.confidence(), org.evidence(), docId, null);
            }
        }

        Map<String, Long> peopleByName = new HashMap<>();
        boolean first = true;
        if (analysis.people() != null) {
            for (MessageAnalysis.ExtractedPerson person : analysis.people()) {
                if (isBlank(person.fullName()) && isBlank(person.email())) {
                    continue;
                }
                Entity personEntity = resolvePerson(person, createdEntities);
                if (!isBlank(person.fullName())) {
                    peopleByName.putIfAbsent(person.fullName().toLowerCase(), personEntity.id());
                }
                if (primaryPerson == null) {
                    primaryPerson = personEntity;
                }
                record(personEntity.id(), "email", person.email(), person.confidence(), person.evidence(), docId, null);
                record(personEntity.id(), "phone", person.phone(), person.confidence(), person.evidence(), docId, null);
                record(personEntity.id(), "title", person.title(), person.confidence(), person.evidence(), docId, null);
                record(personEntity.id(), "company", person.company(), person.confidence(), person.evidence(), docId, null);
                record(personEntity.id(), "address", person.address(), person.confidence(), person.evidence(), docId, null);

                if (!isBlank(person.company())) {
                    Entity orgEntity = resolveOrCreate(Entity.ORGANIZATION, person.company(), createdEntities);
                    if (primaryOrg == null) {
                        primaryOrg = orgEntity;
                    }
                    relationships.ensure(personEntity.id(), orgEntity.id(), Relationship.WORKS_AT, docId);
                }

                // Sentiment and summary describe the interaction; attach them to the
                // primary correspondent so health signals accrue per customer.
                if (first) {
                    record(personEntity.id(), "sentiment", analysis.sentiment(), 1.0, analysis.summary(), docId, null);
                    record(personEntity.id(), "interaction_summary", analysis.summary(), 1.0, null, docId, null);
                    first = false;
                }
            }
        }

        // Evaluation registration (model-signaled) — support registration already
        // happened deterministically at ingest. Must precede commitment recording
        // so commitments can link to the activity.
        try {
            activityService.registerEvaluation(doc, analysis, primaryOrg, primaryPerson);
        } catch (Exception e) {
            LOG.warnf(e, "Evaluation registration failed for document %d", docId);
        }
        List<Activity> docActivities = activityService.activitiesForDocument(docId);
        Long commitmentActivityId = docActivities.isEmpty() ? null : docActivities.get(0).id();

        // Commitments attach to the person who owes them and, when this document
        // belongs to an activity, to that activity — its committed next step.
        if (analysis.commitments() != null) {
            for (MessageAnalysis.ExtractedCommitment commitment : analysis.commitments()) {
                if (isBlank(commitment.description())) {
                    continue;
                }
                Long owner = null;
                if (!isBlank(commitment.owedBy())) {
                    owner = peopleByName.get(commitment.owedBy().toLowerCase());
                    if (owner == null) {
                        owner = entities.findByName(Entity.PERSON, commitment.owedBy())
                                .map(Entity::id).orElse(null);
                    }
                }
                if (owner == null) {
                    owner = primaryPerson == null ? null : primaryPerson.id();
                }
                if (owner == null) {
                    LOG.warnf("Skipping commitment with no attributable owner in document %d: %s",
                            docId, commitment.description());
                    continue;
                }
                StringBuilder value = new StringBuilder(commitment.description());
                if (!isBlank(commitment.dueDate())) {
                    value.append(" — due ").append(commitment.dueDate());
                }
                if (!isBlank(commitment.owedTo())) {
                    value.append(" (to ").append(commitment.owedTo()).append(")");
                }
                record(owner, "commitment", value.toString(), commitment.confidence(),
                        commitment.evidence(), docId, commitmentActivityId);
            }
        }

        // Opportunistic housekeeping: any entity created by this document is checked
        // against similarly named existing entities. Non-fatal — hygiene must never
        // sink the extraction itself.
        for (Entity created : createdEntities) {
            try {
                housekeeping.checkNewEntity(created, jobCredentials.get(docId));
            } catch (Exception e) {
                LOG.warnf(e, "Housekeeping check failed for new entity '%s' (document %d)",
                        created.displayName(), docId);
            }
        }

        // Reassess every activity this document belongs to, then enforce the
        // next-step rule: an open activity with no committed next step gets an
        // implicit commitment due immediately.
        String assessmentError = null;
        for (Activity activity : docActivities) {
            try {
                activityService.reassess(activity.id(), docId, jobCredentials.get(docId));
            } catch (Exception e) {
                LOG.errorf(e, "Assessment failed for activity %d (document %d)", activity.id(), docId);
                assessmentError = "Extraction succeeded, but assessment of "
                        + activity.displayLabel() + " failed: " + e.getMessage();
            }
            try {
                activityService.ensureNextStep(activity);
            } catch (Exception e) {
                LOG.errorf(e, "Next-step check failed for activity %d", activity.id());
            }
        }

        staging.setStatus(docId, DocStatus.EXTRACTED, assessmentError);
        LOG.infof("Extracted document %d: %d people, %d organizations, %d commitments, %d activities", docId,
                analysis.people() == null ? 0 : analysis.people().size(),
                analysis.organizations() == null ? 0 : analysis.organizations().size(),
                analysis.commitments() == null ? 0 : analysis.commitments().size(),
                docActivities.size());
    }

    private Entity resolvePerson(MessageAnalysis.ExtractedPerson person, List<Entity> createdSink) {
        // Email is the strongest identity signal; fall back to name matching.
        if (!isBlank(person.email())) {
            Optional<Long> byEmail = observations.entityIdByAttributeValue("email", person.email());
            if (byEmail.isPresent()) {
                return entities.byId(byEmail.get()).orElseThrow();
            }
        }
        String name = isBlank(person.fullName()) ? person.email() : person.fullName();
        return resolveOrCreate(Entity.PERSON, name, createdSink);
    }

    private Entity resolveOrCreate(String kind, String displayName, List<Entity> createdSink) {
        return entities.findByName(kind, displayName)
                .orElseGet(() -> {
                    Entity created = entities.create(kind, displayName);
                    createdSink.add(created);
                    return created;
                });
    }

    private void record(long entityId, String attribute, String value, double confidence,
                        String evidence, long docId, Long activityId) {
        if (isBlank(value) || observations.duplicateExists(entityId, attribute, value)) {
            return;
        }
        ObservationStatus status = confidence >= autoPromoteThreshold
                ? ObservationStatus.ACTIVE
                : ObservationStatus.PENDING_REVIEW;
        observations.insert(entityId, attribute, value.strip(), confidence, evidence, docId, activityId, status);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @PreDestroy
    void shutdown() {
        worker.shutdown();
    }
}
