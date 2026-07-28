package com.edtice.crm.housekeeping;

import com.edtice.crm.domain.Entity;
import com.edtice.crm.domain.HousekeepingRecord;
import com.edtice.crm.domain.HousekeepingStatus;
import com.edtice.crm.domain.Observation;
import com.edtice.crm.domain.ObservationStatus;
import com.edtice.crm.domain.Relationship;
import com.edtice.crm.extract.ApiCredentials;
import com.edtice.crm.extract.Extractor;
import com.edtice.crm.extract.MergeVerdict;
import com.edtice.crm.store.EntityStore;
import com.edtice.crm.store.HousekeepingStore;
import com.edtice.crm.store.ObservationStore;
import com.edtice.crm.store.RelationshipStore;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Continuous, evidence-linked data hygiene. Candidate duplicate pairs are found
 * by code (name similarity); the merge/keep decision is model judgment. Every
 * deliberation — either way — is stored as a housekeeping record linked to both
 * entities, so the decision history lives inside the CRM and future
 * reconsiderations start from it.
 */
@ApplicationScoped
public class HousekeepingService {

    private static final Logger LOG = Logger.getLogger(HousekeepingService.class);

    private static final Set<String> CORPORATE_SUFFIXES = Set.of(
            "inc", "incorporated", "llc", "ltd", "limited", "co", "corp", "corporation",
            "company", "gmbh", "plc", "sa", "srl", "ag", "bv", "pty");

    private final EntityStore entities;
    private final ObservationStore observations;
    private final RelationshipStore relationships;
    private final HousekeepingStore records;
    private final Extractor extractor;
    private final double autoMergeThreshold;
    private final double autoKeepThreshold;

    HousekeepingService(EntityStore entities, ObservationStore observations,
                        RelationshipStore relationships, HousekeepingStore records, Extractor extractor,
                        @ConfigProperty(name = "crm.housekeeping.autoMergeThreshold") double autoMergeThreshold,
                        @ConfigProperty(name = "crm.housekeeping.autoKeepThreshold") double autoKeepThreshold) {
        this.entities = entities;
        this.observations = observations;
        this.relationships = relationships;
        this.records = records;
        this.extractor = extractor;
        this.autoMergeThreshold = autoMergeThreshold;
        this.autoKeepThreshold = autoKeepThreshold;
    }

    public record SweepResult(int candidatePairs, int merged, int keptSeparate, int openForReview, int skipped) {
    }

    private enum Outcome {
        MERGED, KEPT_SEPARATE, OPEN, SKIPPED
    }

    @Scheduled(cron = "{crm.housekeeping.cron}")
    void scheduledSweep() {
        try {
            SweepResult result = sweep(null);
            if (result.candidatePairs() > 0) {
                LOG.infof("Scheduled housekeeping sweep: %s", result);
            }
        } catch (Exception e) {
            LOG.error("Scheduled housekeeping sweep failed", e);
        }
    }

    /** Compare all live entities pairwise (per kind) and deliberate on similar-name candidates. */
    public SweepResult sweep(ApiCredentials credentials) {
        int candidates = 0;
        int merged = 0;
        int kept = 0;
        int open = 0;
        int skipped = 0;
        for (String kind : List.of(Entity.PERSON, Entity.ORGANIZATION)) {
            List<Entity> list = entities.listByKind(kind);
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    Entity a = list.get(i);
                    Entity b = list.get(j);
                    // Either may have been merged away by an earlier pair in this sweep.
                    if (isGone(a.id()) || isGone(b.id())) {
                        continue;
                    }
                    if (!similarNames(a.displayName(), b.displayName())) {
                        continue;
                    }
                    candidates++;
                    switch (considerPair(a, b, credentials)) {
                        case MERGED -> merged++;
                        case KEPT_SEPARATE -> kept++;
                        case OPEN -> open++;
                        case SKIPPED -> skipped++;
                    }
                }
            }
        }
        return new SweepResult(candidates, merged, kept, open, skipped);
    }

    /** Opportunistic check when extraction just created a new entity. */
    public void checkNewEntity(Entity created, ApiCredentials credentials) {
        for (Entity other : entities.listByKind(created.kind())) {
            if (other.id() == created.id() || isGone(created.id())) {
                continue;
            }
            if (similarNames(created.displayName(), other.displayName())) {
                considerPair(created, other, credentials);
            }
        }
    }

    /** Human decision on an open record. */
    public HousekeepingRecord decideManually(long recordId, boolean merge, String note) {
        HousekeepingRecord record = records.byId(recordId)
                .orElseThrow(() -> new IllegalStateException("No such housekeeping record: " + recordId));
        if (record.status() != HousekeepingStatus.OPEN) {
            throw new IllegalStateException("Record " + recordId + " is already decided (" + record.status() + ")");
        }
        String reasoning = record.reasoning() == null ? "" : record.reasoning();
        if (note != null && !note.isBlank()) {
            reasoning = reasoning + "\nUser decision note: " + note;
        }
        if (merge) {
            List<Entity> pair = liveEntities(record);
            executeMerge(pair.get(0), pair.get(1));
            records.decide(recordId, HousekeepingStatus.MERGED, "user", reasoning, record.confidence());
        } else {
            records.decide(recordId, HousekeepingStatus.KEPT_SEPARATE, "user", reasoning, record.confidence());
        }
        return records.byId(recordId).orElseThrow();
    }

    // --- deliberation ---

    private Outcome considerPair(Entity a, Entity b, ApiCredentials credentials) {
        List<HousekeepingRecord> history = records.forPair(a.id(), b.id());
        if (history.stream().anyMatch(r -> r.status() == HousekeepingStatus.OPEN)) {
            return Outcome.SKIPPED; // already awaiting a decision
        }
        Optional<HousekeepingRecord> lastDecided = history.stream()
                .filter(r -> r.status() != HousekeepingStatus.OPEN)
                .findFirst(); // list is newest-first
        if (lastDecided.isPresent()) {
            // Reconsider a settled question only when new evidence arrived after the decision.
            Instant decidedAt = lastDecided.get().decidedAt();
            Instant newest = latest(observations.latestObservedAt(a.id()), observations.latestObservedAt(b.id()));
            if (decidedAt != null && (newest == null || !newest.isAfter(decidedAt))) {
                return Outcome.SKIPPED;
            }
        }

        MergeVerdict verdict = extractor.judgeMerge(profile(a), profile(b), historyText(history), credentials);
        Long priorId = lastDecided.map(HousekeepingRecord::id).orElse(null);
        String verdictWord = verdict.verdict() == null ? "uncertain" : verdict.verdict().toLowerCase(Locale.ROOT);

        if ("merge".equals(verdictWord) && verdict.confidence() >= autoMergeThreshold) {
            HousekeepingRecord record = records.create(HousekeepingRecord.ENTITY_MERGE,
                    List.of(a.id(), b.id()), verdict.evidenceStatement(), verdict.reasoning(),
                    null, verdict.confidence(), HousekeepingStatus.OPEN, priorId);
            executeMerge(a, b);
            records.decide(record.id(), HousekeepingStatus.MERGED, "agent", verdict.reasoning(), verdict.confidence());
            LOG.infof("Housekeeping: merged '%s' and '%s' (record %d, confidence %.2f)",
                    a.displayName(), b.displayName(), record.id(), verdict.confidence());
            return Outcome.MERGED;
        }
        if ("keep_separate".equals(verdictWord) && verdict.confidence() >= autoKeepThreshold) {
            records.create(HousekeepingRecord.ENTITY_MERGE, List.of(a.id(), b.id()),
                    verdict.evidenceStatement(), verdict.reasoning(),
                    "agent", verdict.confidence(), HousekeepingStatus.KEPT_SEPARATE, priorId);
            LOG.infof("Housekeeping: keeping '%s' and '%s' separate (confidence %.2f)",
                    a.displayName(), b.displayName(), verdict.confidence());
            return Outcome.KEPT_SEPARATE;
        }
        records.create(HousekeepingRecord.ENTITY_MERGE, List.of(a.id(), b.id()),
                verdict.evidenceStatement(), verdict.reasoning(),
                null, verdict.confidence(), HousekeepingStatus.OPEN, priorId);
        LOG.infof("Housekeeping: '%s' vs '%s' needs human review (verdict %s, confidence %.2f)",
                a.displayName(), b.displayName(), verdictWord, verdict.confidence());
        return Outcome.OPEN;
    }

    /** Winner = more observations; ties go to the older entity. */
    private void executeMerge(Entity a, Entity b) {
        int obsA = observations.forEntity(a.id()).size();
        int obsB = observations.forEntity(b.id()).size();
        Entity winner = obsA > obsB || (obsA == obsB && a.id() < b.id()) ? a : b;
        Entity loser = winner == a ? b : a;
        entities.merge(loser.id(), winner.id());
    }

    private List<Entity> liveEntities(HousekeepingRecord record) {
        List<Entity> out = new ArrayList<>();
        for (Long id : record.entityIds()) {
            Entity e = entities.byId(id)
                    .orElseThrow(() -> new IllegalStateException("Record references missing entity " + id));
            if (e.isMerged()) {
                throw new IllegalStateException(
                        "Entity '" + e.displayName() + "' was already merged; record " + record.id() + " is stale");
            }
            out.add(e);
        }
        if (out.size() != 2) {
            throw new IllegalStateException("Record " + record.id() + " does not link exactly two entities");
        }
        return out;
    }

    private boolean isGone(long entityId) {
        return entities.byId(entityId).map(Entity::isMerged).orElse(true);
    }

    private static Instant latest(Optional<Instant> a, Optional<Instant> b) {
        if (a.isEmpty()) {
            return b.orElse(null);
        }
        if (b.isEmpty()) {
            return a.get();
        }
        return a.get().isAfter(b.get()) ? a.get() : b.get();
    }

    // --- evidence assembly ---

    /** Full observable profile of an entity, as text for the judgment call. */
    private String profile(Entity e) {
        StringBuilder sb = new StringBuilder();
        sb.append("Name: ").append(e.displayName()).append('\n');
        sb.append("Kind: ").append(e.kind()).append('\n');
        sb.append("First seen: ").append(e.createdAt()).append('\n');
        sb.append("Observations:\n");
        List<Observation> all = observations.forEntity(e.id());
        if (all.isEmpty()) {
            sb.append("  (none)\n");
        }
        for (Observation o : all) {
            if (o.status() == ObservationStatus.REJECTED) {
                continue;
            }
            sb.append("  - ").append(o.attribute()).append(": ").append(o.value())
                    .append(" [").append(o.status().name().toLowerCase()).append(", confidence ")
                    .append(o.confidencePercent());
            if (o.evidence() != null && !o.evidence().isBlank()) {
                sb.append(", evidence: \"").append(o.evidence()).append('"');
            }
            sb.append("]\n");
        }
        List<Relationship> rels = relationships.from(e.id());
        if (!rels.isEmpty()) {
            sb.append("Relationships:\n");
            for (Relationship r : rels) {
                entities.byId(r.toEntity()).ifPresent(other ->
                        sb.append("  - ").append(r.kind()).append(' ').append(other.displayName()).append('\n'));
            }
        }
        return sb.toString();
    }

    private String historyText(List<HousekeepingRecord> history) {
        if (history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (HousekeepingRecord r : history) {
            sb.append("Decision on ").append(r.decidedAt() == null ? r.createdAt() : r.decidedAt())
                    .append(" (by ").append(r.decidedBy() == null ? "pending" : r.decidedBy())
                    .append("): ").append(r.status().name().toLowerCase()).append('\n')
                    .append("  Evidence then: ").append(r.evidence()).append('\n')
                    .append("  Reasoning then: ").append(r.reasoning()).append('\n');
        }
        return sb.toString();
    }

    // --- name similarity (candidate detection only; the model judges) ---

    static boolean similarNames(String a, String b) {
        String na = normalize(a);
        String nb = normalize(b);
        if (na.isBlank() || nb.isBlank()) {
            return false;
        }
        if (na.equals(nb)) {
            return true;
        }
        return tokenSubset(na, nb) || tokenSubset(nb, na);
    }

    /** Every token of the shorter name matches (or is a prefix of) a token in the longer. */
    private static boolean tokenSubset(String shorter, String longer) {
        String[] st = shorter.split(" ");
        String[] lt = longer.split(" ");
        if (st.length > lt.length) {
            return false;
        }
        for (String s : st) {
            boolean found = false;
            for (String l : lt) {
                if (l.equals(s) || (s.length() >= 3 && l.startsWith(s)) || (l.length() >= 3 && s.startsWith(l))) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    static String normalize(String name) {
        String n = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .strip();
        String[] tokens = n.split(" ");
        int end = tokens.length;
        while (end > 1 && CORPORATE_SUFFIXES.contains(tokens[end - 1])) {
            end--;
        }
        return String.join(" ", java.util.Arrays.copyOfRange(tokens, 0, end));
    }
}
