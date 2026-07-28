package com.edtice.crm.web;

import com.edtice.crm.domain.CaseAssessment;
import com.edtice.crm.domain.Entity;
import com.edtice.crm.domain.Observation;
import com.edtice.crm.domain.ObservationStatus;
import com.edtice.crm.domain.Relationship;
import com.edtice.crm.domain.SourceDocument;
import com.edtice.crm.domain.SupportCase;
import com.edtice.crm.ingest.IngestService;
import com.edtice.crm.pipeline.PipelineService;
import com.edtice.crm.store.CaseStore;
import com.edtice.crm.store.EntityStore;
import com.edtice.crm.store.ObservationStore;
import com.edtice.crm.store.RelationshipStore;
import com.edtice.crm.store.StagingStore;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/")
@Produces(MediaType.TEXT_HTML)
public class PagesResource {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final EntityStore entities;
    private final ObservationStore observations;
    private final RelationshipStore relationships;
    private final StagingStore staging;
    private final IngestService ingest;
    private final PipelineService pipeline;
    private final CaseStore caseStore;

    PagesResource(EntityStore entities, ObservationStore observations, RelationshipStore relationships,
                  StagingStore staging, IngestService ingest, PipelineService pipeline, CaseStore caseStore) {
        this.entities = entities;
        this.observations = observations;
        this.relationships = relationships;
        this.staging = staging;
        this.ingest = ingest;
        this.pipeline = pipeline;
        this.caseStore = caseStore;
    }

    // --- rows the templates render ---

    public record PersonRow(long id, String name, String email, String title, String company, String sentiment) {
    }

    public record OrgRow(long id, String name, String website) {
    }

    public record ObsRow(long id, String value, String confidencePercent, String status,
                         String evidence, Long sourceDocId, String observedAt) {
    }

    public record AttrGroup(String attribute, List<ObsRow> rows) {
    }

    public record RelRow(String kind, long otherId, String otherName) {
    }

    public record ReviewRow(long id, long entityId, String entityName, String attribute, String value,
                            String confidencePercent, String evidence, Long sourceDocId) {
    }

    public record CaseRow(long id, String label, String subject, int emailCount, String health,
                          String disposition, String technical, String rootCause, String updated) {
    }

    public record AssessmentView(String health, String disposition, String dispositionNotes,
                                 String technical, String technicalNotes,
                                 String rootCause, String rootCauseNotes,
                                 String summary, String assessedAt, Long triggeredByDoc) {
        static AssessmentView of(CaseAssessment a) {
            return new AssessmentView(a.health(), a.customerDisposition(), a.customerDispositionNotes(),
                    a.technicalProgress(), a.technicalProgressNotes(), a.rootCauseProgress(),
                    a.rootCauseNotes(), a.summary(), TS.format(a.createdAt()), a.triggeredByDoc());
        }
    }

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance index(List<PersonRow> people, List<OrgRow> orgs,
                                                    List<SourceDocument> docs, int pendingCount);

        public static native TemplateInstance entity(Entity entity, List<AttrGroup> groups,
                                                     List<RelRow> rels, int pendingCount);

        public static native TemplateInstance review(List<ReviewRow> rows, int pendingCount);

        public static native TemplateInstance source(SourceDocument doc, int pendingCount);

        public static native TemplateInstance cases(List<CaseRow> rows, int pendingCount);

        public static native TemplateInstance caseDetail(SupportCase supportCase, AssessmentView current,
                                                         List<AssessmentView> history,
                                                         List<SourceDocument> docs, int pendingCount);
    }

    // --- pages ---

    @GET
    public TemplateInstance index() {
        List<PersonRow> people = new ArrayList<>();
        for (Entity e : entities.listByKind(Entity.PERSON)) {
            Map<String, String> latest = latestActive(e.id());
            people.add(new PersonRow(e.id(), e.displayName(),
                    latest.getOrDefault("email", ""),
                    latest.getOrDefault("title", ""),
                    latest.getOrDefault("company", ""),
                    latest.getOrDefault("sentiment", "")));
        }
        List<OrgRow> orgs = new ArrayList<>();
        for (Entity e : entities.listByKind(Entity.ORGANIZATION)) {
            Map<String, String> latest = latestActive(e.id());
            orgs.add(new OrgRow(e.id(), e.displayName(), latest.getOrDefault("website", "")));
        }
        return Templates.index(people, orgs, staging.listRecent(20), pendingCount());
    }

    @GET
    @Path("entity/{id}")
    public TemplateInstance entity(@PathParam("id") long id) {
        Entity entity = entities.byId(id).orElseThrow(NotFoundException::new);
        Map<String, List<ObsRow>> grouped = new LinkedHashMap<>();
        for (Observation o : observations.forEntity(id)) {
            grouped.computeIfAbsent(o.attribute(), k -> new ArrayList<>())
                    .add(new ObsRow(o.id(), o.value(), o.confidencePercent(), o.status().name().toLowerCase(),
                            o.evidence(), o.sourceDocId(), TS.format(o.observedAt())));
        }
        List<AttrGroup> groups = grouped.entrySet().stream()
                .map(en -> new AttrGroup(en.getKey(), en.getValue()))
                .toList();
        List<RelRow> rels = new ArrayList<>();
        for (Relationship r : relationships.from(id)) {
            entities.byId(r.toEntity()).ifPresent(other ->
                    rels.add(new RelRow(r.kind().replace('_', ' '), other.id(), other.displayName())));
        }
        return Templates.entity(entity, groups, rels, pendingCount());
    }

    @GET
    @Path("cases")
    public TemplateInstance cases() {
        List<CaseRow> rows = new ArrayList<>();
        for (SupportCase sc : caseStore.listAll()) {
            var latest = caseStore.latestAssessment(sc.id());
            rows.add(new CaseRow(sc.id(), sc.label(),
                    sc.subject() == null ? "" : sc.subject(),
                    caseStore.documentCount(sc.id()),
                    latest.map(CaseAssessment::health).orElse("unassessed"),
                    latest.map(CaseAssessment::customerDisposition).orElse(""),
                    latest.map(CaseAssessment::technicalProgress).orElse(""),
                    latest.map(CaseAssessment::rootCauseProgress).orElse(""),
                    latest.map(a -> TS.format(a.createdAt())).orElse("")));
        }
        return Templates.cases(rows, pendingCount());
    }

    @GET
    @Path("case/{id}")
    public TemplateInstance caseDetail(@PathParam("id") long id) {
        SupportCase sc = caseStore.byId(id).orElseThrow(NotFoundException::new);
        List<AssessmentView> history = caseStore.assessments(id).stream()
                .map(AssessmentView::of).toList();
        AssessmentView current = history.isEmpty() ? null : history.get(0);
        return Templates.caseDetail(sc, current, history, caseStore.documents(id), pendingCount());
    }

    @GET
    @Path("review")
    public TemplateInstance review() {
        List<ReviewRow> rows = new ArrayList<>();
        for (Observation o : observations.pendingReview()) {
            String name = entities.byId(o.entityId()).map(Entity::displayName).orElse("?");
            rows.add(new ReviewRow(o.id(), o.entityId(), name, o.attribute(), o.value(),
                    o.confidencePercent(), o.evidence(), o.sourceDocId()));
        }
        return Templates.review(rows, rows.size());
    }

    @GET
    @Path("source/{id}")
    public TemplateInstance source(@PathParam("id") long id) {
        SourceDocument doc = staging.byId(id).orElseThrow(NotFoundException::new);
        return Templates.source(doc, pendingCount());
    }

    // --- actions ---

    @POST
    @Path("ingest")
    public Response ingest(@FormParam("content") String content, @FormParam("note") String note) {
        if (content != null && !content.isBlank()) {
            ingest.ingestPaste(content, note);
        }
        return Response.seeOther(URI.create("/")).build();
    }

    @POST
    @Path("review/{id}/approve")
    public Response approve(@PathParam("id") long id) {
        observations.setStatus(id, ObservationStatus.ACTIVE);
        return Response.seeOther(URI.create("/review")).build();
    }

    @POST
    @Path("review/{id}/reject")
    public Response reject(@PathParam("id") long id) {
        observations.setStatus(id, ObservationStatus.REJECTED);
        return Response.seeOther(URI.create("/review")).build();
    }

    @POST
    @Path("source/{id}/reprocess")
    public Response reprocess(@PathParam("id") long id) {
        pipeline.submit(id);
        return Response.seeOther(URI.create("/")).build();
    }

    // --- helpers ---

    /** Latest active value per attribute — the projection that turns observations into a profile. */
    private Map<String, String> latestActive(long entityId) {
        Map<String, String> latest = new LinkedHashMap<>();
        for (Observation o : observations.forEntity(entityId)) {
            if (o.status() == ObservationStatus.ACTIVE) {
                latest.putIfAbsent(o.attribute(), o.value());
            }
        }
        return latest;
    }

    private int pendingCount() {
        return observations.pendingReview().size();
    }
}
