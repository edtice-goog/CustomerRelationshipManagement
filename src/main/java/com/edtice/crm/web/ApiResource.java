package com.edtice.crm.web;

import com.edtice.crm.activities.ActivityService;
import com.edtice.crm.domain.Activity;
import com.edtice.crm.domain.ActivityAssessment;
import com.edtice.crm.domain.ActivityState;
import com.edtice.crm.domain.HousekeepingRecord;
import com.edtice.crm.domain.HousekeepingStatus;
import com.edtice.crm.domain.Observation;
import com.edtice.crm.domain.Opportunity;
import com.edtice.crm.domain.SourceDocument;
import com.edtice.crm.extract.ApiCredentials;
import com.edtice.crm.extract.Extractor;
import com.edtice.crm.housekeeping.HousekeepingService;
import com.edtice.crm.ingest.IngestService;
import com.edtice.crm.pipeline.PipelineService;
import com.edtice.crm.store.ActivityStore;
import com.edtice.crm.store.EntityStore;
import com.edtice.crm.store.HousekeepingStore;
import com.edtice.crm.store.ObservationStore;
import com.edtice.crm.store.OpportunityStore;
import com.edtice.crm.store.StagingStore;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Machine-facing JSON API — the surface agents (Copilot, scripts, curl) drive.
 * The OpenAPI description is published at /q/openapi; interactive docs at /q/swagger-ui.
 */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ApiResource {

    private final Extractor extractor;
    private final IngestService ingest;
    private final StagingStore staging;
    private final PipelineService pipeline;
    private final ActivityService activityService;
    private final ActivityStore activities;
    private final OpportunityStore opportunities;
    private final ObservationStore observations;
    private final HousekeepingService housekeeping;
    private final HousekeepingStore housekeepingStore;
    private final EntityStore entities;

    ApiResource(Extractor extractor, IngestService ingest, StagingStore staging,
                PipelineService pipeline, ActivityService activityService, ActivityStore activities,
                OpportunityStore opportunities, ObservationStore observations,
                HousekeepingService housekeeping, HousekeepingStore housekeepingStore,
                EntityStore entities) {
        this.extractor = extractor;
        this.ingest = ingest;
        this.staging = staging;
        this.pipeline = pipeline;
        this.activityService = activityService;
        this.activities = activities;
        this.opportunities = opportunities;
        this.observations = observations;
        this.housekeeping = housekeeping;
        this.housekeepingStore = housekeepingStore;
        this.entities = entities;
    }

    // --- shared request/response shapes ---

    @Schema(description = "Optional Claude API credentials. When omitted, the server's default "
            + "(ANTHROPIC_API_KEY environment variable) is used. Supply these to route extraction "
            + "through a different key/endpoint, e.g. a corporate agreement. Never persisted.")
    public record ClaudeConfig(
            @Schema(description = "Alternative Claude API base URL, e.g. a corporate gateway. Optional.", example = "https://api.anthropic.com")
            String baseUrl,
            @Schema(description = "API key to use for this request's extraction. Optional.")
            String apiKey) {

        ApiCredentials toCredentials() {
            return new ApiCredentials(baseUrl, apiKey);
        }

        static ApiCredentials orNull(ClaudeConfig config) {
            return config == null ? null : config.toCredentials();
        }
    }

    public record IngestRequest(
            @Schema(description = "The full raw communication text (email, chat message, note), verbatim.", required = true)
            String content,
            @Schema(description = "Where this came from, e.g. outlook_email, teams_chat. Defaults to 'api'.")
            String sourceType,
            @Schema(description = "Stable id for deduplication (message id, etc). Defaults to a hash of content.")
            String externalId,
            @Schema(description = "Optional metadata as a JSON string (sender, date, subject...). Stored with the document.")
            String metadataJson,
            ClaudeConfig claude) {
    }

    @Schema(description = "Present when the communication carries a support-ticket token (e.g. a SalesForce "
            + "ref:...:ref thread token). Evaluation activities are detected during extraction and appear on "
            + "the document's activities afterward, not in this synchronous response.")
    public record ActivityInfoDto(long activityId, String kind, String label, String token,
                                  boolean newlyTracked, int documentCount, String instructions) {
        static ActivityInfoDto of(ActivityService.ActivityInfo info) {
            return info == null ? null : new ActivityInfoDto(info.activityId(), info.kind(), info.label(),
                    info.token(), info.newlyTracked(), info.documentCount(), info.instructions());
        }
    }

    public record IngestResponse(Long documentId, String status, boolean duplicate, String detail,
                                 ActivityInfoDto supportActivity) {
    }

    public record DocumentStatus(long id, String sourceType, String status, String error, Instant receivedAt,
                                 List<Long> activityIds) {
    }

    public record TestResult(boolean ok, String detail) {
    }

    private DocumentStatus docStatus(SourceDocument d) {
        List<Long> activityIds = activities.activitiesForDocument(d.id()).stream().map(Activity::id).toList();
        return new DocumentStatus(d.id(), d.sourceType(), d.status().db(), d.error(), d.receivedAt(), activityIds);
    }

    // --- core endpoints ---

    @GET
    @Path("ping")
    @Operation(summary = "Liveness check", description = "Confirms the CRM itself is up. Does not touch the Claude API.")
    public TestResult ping() {
        return new TestResult(true, "Customer Success CRM is up (" + Instant.now() + ")");
    }

    @POST
    @Path("claude/test")
    @Operation(summary = "Test Claude API connectivity",
            description = "Makes a free count_tokens call against the Claude API using the supplied "
                    + "credentials (or the server default when the body is empty/omitted). "
                    + "Call this before submitting content to confirm the key and endpoint work.")
    public Response testClaude(ClaudeConfig config) {
        try {
            return Response.ok(new TestResult(true, extractor.verifyConnectivity(ClaudeConfig.orNull(config)))).build();
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity(new TestResult(false, rootMessage(e)))
                    .build();
        }
    }

    @POST
    @Path("ingest")
    @Operation(summary = "Ingest a communication",
            description = "Stages the raw text and starts background extraction (people, organizations, "
                    + "contact details, sentiment, commitments, activity linkage). Returns the staged "
                    + "document id; poll /api/documents/{id} for extraction status and linked activities. "
                    + "Duplicate submissions (same externalId or same content) are detected and not re-processed.")
    public Response ingest(IngestRequest request) {
        if (request == null || request.content() == null || request.content().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new IngestResponse(null, null, false, "content is required", null)).build();
        }
        String sourceType = request.sourceType() == null || request.sourceType().isBlank()
                ? "api" : request.sourceType();
        String externalId = request.externalId() == null || request.externalId().isBlank()
                ? IngestService.contentHash(request.content()) : request.externalId();

        Optional<IngestService.IngestOutcome> outcome = ingest.ingest(sourceType, externalId,
                request.content(), request.metadataJson(), ClaudeConfig.orNull(request.claude()));
        if (outcome.isPresent()) {
            ActivityInfoDto info = outcome.get().activityInfo().map(ActivityInfoDto::of).orElse(null);
            return Response.accepted(new IngestResponse(outcome.get().doc().id(),
                    outcome.get().doc().status().db(), false,
                    "Staged; extraction running in background", info)).build();
        }
        Optional<SourceDocument> existing = staging.byExternalId(externalId);
        ActivityInfoDto info = activityService.infoFor(request.content()).map(ActivityInfoDto::of).orElse(null);
        return Response.ok(new IngestResponse(existing.map(SourceDocument::id).orElse(null),
                existing.map(d -> d.status().db()).orElse(null), true,
                "Already ingested; not re-processed", info)).build();
    }

    @GET
    @Path("documents/{id}")
    @Operation(summary = "Get a staged document's extraction status",
            description = "Status values: staged, processing, extracted, error. activityIds lists the "
                    + "activities this document is linked to (support cases, evaluations).")
    public DocumentStatus document(@PathParam("id") long id) {
        return staging.byId(id).map(this::docStatus).orElseThrow(NotFoundException::new);
    }

    @POST
    @Path("documents/{id}/reprocess")
    @Operation(summary = "Re-run extraction for a staged document",
            description = "Useful after an error, or to re-extract with improved prompts. "
                    + "Optionally supply Claude credentials in the body.")
    public Response reprocess(@PathParam("id") long id, ClaudeConfig config) {
        SourceDocument doc = staging.byId(id).orElseThrow(NotFoundException::new);
        pipeline.submit(doc.id(), ClaudeConfig.orNull(config));
        return Response.accepted(new IngestResponse(doc.id(), "processing", false, "Re-extraction queued",
                activityService.infoFor(doc.rawContent()).map(ActivityInfoDto::of).orElse(null))).build();
    }

    // --- activities ---

    public record AssessmentDto(String health, String customerDisposition, String customerDispositionNotes,
                                String technicalProgress, String technicalProgressNotes,
                                String rootCauseProgress, String rootCauseNotes,
                                String summary, Instant assessedAt, Long triggeredByDocument) {
        static AssessmentDto of(ActivityAssessment a) {
            return a == null ? null : new AssessmentDto(a.health(), a.customerDisposition(),
                    a.customerDispositionNotes(), a.technicalProgress(), a.technicalProgressNotes(),
                    a.rootCauseProgress(), a.rootCauseNotes(), a.summary(), a.createdAt(), a.triggeredByDoc());
        }
    }

    public record CommitmentDto(long observationId, long owedByEntityId, String owedByName, String value,
                                String status, boolean implicit, Long sourceDocId) {
    }

    public record ActivitySummaryDto(long id, String kind, String state, String label, String token,
                                     String reference, Long primaryEntityId, Long opportunityId,
                                     int documentCount, AssessmentDto currentStatus, boolean hasNextStep) {
    }

    public record ActivityDetailDto(long id, String kind, String state, String label, String token,
                                    String reference, Long primaryEntityId, Long opportunityId,
                                    List<DocumentStatus> documents, List<CommitmentDto> commitments,
                                    AssessmentDto currentStatus, List<AssessmentDto> assessmentHistory) {
    }

    private ActivitySummaryDto activitySummary(Activity a) {
        return new ActivitySummaryDto(a.id(), a.kind(), a.state().db(), a.label(), a.token(), a.reference(),
                a.primaryEntityId(), a.opportunityId(), activities.documentCount(a.id()),
                activities.latestAssessment(a.id()).map(AssessmentDto::of).orElse(null),
                observations.activeCommitmentExists(a.id()));
    }

    private List<CommitmentDto> commitmentsOf(long activityId) {
        return observations.commitmentsForActivity(activityId).stream()
                .map(o -> new CommitmentDto(o.id(), o.entityId(),
                        entities.byId(o.entityId()).map(e -> e.displayName()).orElse("?"),
                        o.value(), o.status().name().toLowerCase(),
                        ActivityService.isImplicit(o),
                        o.sourceDocId()))
                .toList();
    }

    @POST
    @Path("commitments/{id}/fulfill")
    @Operation(summary = "Manually mark a commitment fulfilled",
            description = "For commitments handled outside email, e.g. on a phone call. Per the next-step "
                    + "rule, fulfilling the last active commitment on an open activity immediately generates "
                    + "a fresh implicit commitment to set the next step.")
    public Response fulfillCommitment(@PathParam("id") long id) {
        try {
            activityService.fulfillCommitment(id);
            Observation o = observations.byId(id).orElseThrow(NotFoundException::new);
            return Response.ok(new CommitmentDto(o.id(), o.entityId(),
                    entities.byId(o.entityId()).map(e -> e.displayName()).orElse("?"),
                    o.value(), o.status().name().toLowerCase(),
                    ActivityService.isImplicit(o), o.sourceDocId())).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(new TestResult(false, e.getMessage())).build();
        }
    }

    @GET
    @Path("activities")
    @Operation(summary = "List tracked activities",
            description = "Support cases, evaluations, and relationship activities share one structure. "
                    + "Filter with ?kind=support|evaluation|relationship and ?state=open|closed. "
                    + "hasNextStep=false on an open activity means the next-step rule will generate an "
                    + "implicit commitment.")
    public List<ActivitySummaryDto> listActivities(@QueryParam("kind") String kind,
                                                   @QueryParam("state") String state) {
        ActivityState st = state == null || state.isBlank() ? null : ActivityState.fromDb(state);
        return activities.list(kind, st).stream().map(this::activitySummary).toList();
    }

    @GET
    @Path("activities/{id}")
    @Operation(summary = "Get an activity with documents, commitments, and full assessment history")
    public ActivityDetailDto activityDetail(@PathParam("id") long id) {
        Activity a = activities.byId(id).orElseThrow(NotFoundException::new);
        List<AssessmentDto> history = activities.assessments(id).stream().map(AssessmentDto::of).toList();
        return new ActivityDetailDto(a.id(), a.kind(), a.state().db(), a.label(), a.token(), a.reference(),
                a.primaryEntityId(), a.opportunityId(),
                activities.documents(id).stream().map(this::docStatus).toList(),
                commitmentsOf(id),
                history.isEmpty() ? null : history.get(0),
                history);
    }

    @POST
    @Path("activities/{id}/reassess")
    @Operation(summary = "Force an activity status recalculation from the full document history")
    public Response reassessActivity(@PathParam("id") long id, ClaudeConfig config) {
        activities.byId(id).orElseThrow(NotFoundException::new);
        try {
            activityService.reassess(id, null, ClaudeConfig.orNull(config));
            return Response.ok(activityDetail(id)).build();
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity(new TestResult(false, rootMessage(e))).build();
        }
    }

    @POST
    @Path("activities/{id}/close")
    @Operation(summary = "Mark an activity complete",
            description = "Unconditional for now. Retires the activity's open implicit commitments (a closed "
                    + "activity needs no next step); real extracted promises stay active. A future restriction "
                    + "may keep evaluations open until their opportunity is won or lost.")
    public ActivitySummaryDto closeActivity(@PathParam("id") long id) {
        activities.byId(id).orElseThrow(NotFoundException::new);
        activityService.close(id);
        return activitySummary(activities.byId(id).orElseThrow());
    }

    @POST
    @Path("activities/{id}/reopen")
    @Operation(summary = "Reopen a closed activity")
    public ActivitySummaryDto reopenActivity(@PathParam("id") long id) {
        activities.byId(id).orElseThrow(NotFoundException::new);
        activityService.reopen(id);
        return activitySummary(activities.byId(id).orElseThrow());
    }

    public record LinkOpportunityRequest(Long opportunityId) {
    }

    @POST
    @Path("activities/{id}/opportunity")
    @Operation(summary = "Link an activity to an opportunity (null opportunityId unlinks)")
    public ActivitySummaryDto linkOpportunity(@PathParam("id") long id, LinkOpportunityRequest request) {
        activities.byId(id).orElseThrow(NotFoundException::new);
        if (request != null && request.opportunityId() != null) {
            opportunities.byId(request.opportunityId()).orElseThrow(NotFoundException::new);
        }
        activities.setOpportunity(id, request == null ? null : request.opportunityId());
        return activitySummary(activities.byId(id).orElseThrow());
    }

    // --- opportunities ---

    public record OpportunityRequest(
            @Schema(description = "Display name for the opportunity.")
            String name,
            @Schema(description = "External system holding the real opportunity record, e.g. 'salesforce'. Omit for a local opportunity.")
            String externalSystem,
            @Schema(description = "The opportunity's id/reference in the external system.")
            String externalRef,
            @Schema(description = "Initial key-value attributes for local opportunities.")
            Map<String, String> attributes) {
    }

    public record OpportunityDto(long id, String guid, String name, String externalSystem,
                                 String externalRef, Instant createdAt, Map<String, String> attributes) {
    }

    private OpportunityDto opportunityDto(Opportunity o) {
        return new OpportunityDto(o.id(), o.guid(), o.name(), o.externalSystem(), o.externalRef(),
                o.createdAt(), opportunities.attributes(o.id()));
    }

    @POST
    @Path("opportunities")
    @Operation(summary = "Create a generic opportunity anchor",
            description = "This CRM is deliberately not an opportunity-management system. An opportunity is "
                    + "a GUID plus either external-system metadata (externalSystem + externalRef) or, for "
                    + "users without one, a local key-value attribute bag. Anything richer belongs in a "
                    + "real opportunity system.")
    public OpportunityDto createOpportunity(OpportunityRequest request) {
        Opportunity o = opportunities.create(
                request == null ? null : request.name(),
                request == null ? null : request.externalSystem(),
                request == null ? null : request.externalRef());
        if (request != null && request.attributes() != null) {
            request.attributes().forEach((k, v) -> opportunities.setAttribute(o.id(), k, v));
        }
        return opportunityDto(o);
    }

    @GET
    @Path("opportunities")
    @Operation(summary = "List opportunities")
    public List<OpportunityDto> listOpportunities() {
        return opportunities.listAll().stream().map(this::opportunityDto).toList();
    }

    @GET
    @Path("opportunities/{id}")
    @Operation(summary = "Get an opportunity with its attributes")
    public OpportunityDto opportunity(@PathParam("id") long id) {
        return opportunities.byId(id).map(this::opportunityDto).orElseThrow(NotFoundException::new);
    }

    @PUT
    @Path("opportunities/{id}/attributes")
    @Operation(summary = "Upsert key-value attributes on a local opportunity",
            description = "Keys are merged; existing keys are overwritten. Setting a key to empty string keeps the key.")
    public OpportunityDto setAttributes(@PathParam("id") long id, Map<String, String> attributes) {
        Opportunity o = opportunities.byId(id).orElseThrow(NotFoundException::new);
        if (attributes != null) {
            attributes.forEach((k, v) -> opportunities.setAttribute(o.id(), k, v));
        }
        return opportunityDto(o);
    }

    // --- housekeeping ---

    public record EntityRef(long id, String name, boolean merged) {
    }

    public record HousekeepingDto(long id, String kind, String status, String evidence, String reasoning,
                                  String decidedBy, double confidence, Long priorRecordId,
                                  Instant createdAt, Instant decidedAt,
                                  List<EntityRef> entities) {
    }

    public record SweepResultDto(int candidatePairs, int merged, int keptSeparate, int openForReview, int skipped) {
    }

    public record DecideRequest(
            @Schema(description = "'merge' to fold the two entities together, 'keep_separate' to record they are distinct.", required = true)
            String action,
            @Schema(description = "Optional note appended to the record's reasoning.")
            String note) {
    }

    private HousekeepingDto toDto(HousekeepingRecord r) {
        List<EntityRef> refs = r.entityIds().stream()
                .map(id -> entities.byId(id)
                        .map(e -> new EntityRef(e.id(), e.displayName(), e.isMerged()))
                        .orElse(new EntityRef(id, "?", false)))
                .toList();
        return new HousekeepingDto(r.id(), r.kind(), r.status().db(), r.evidence(), r.reasoning(),
                r.decidedBy(), r.confidence(), r.priorRecordId(), r.createdAt(), r.decidedAt(), refs);
    }

    @GET
    @Path("housekeeping")
    @Operation(summary = "List housekeeping records",
            description = "The durable history of data-hygiene deliberations (entity merges considered, "
                    + "with evidence and reasoning either way). Filter with ?status=open|merged|kept_separate.")
    public List<HousekeepingDto> housekeepingRecords(@QueryParam("status") String status) {
        List<HousekeepingRecord> list = status == null || status.isBlank()
                ? housekeepingStore.listAll()
                : housekeepingStore.byStatus(HousekeepingStatus.fromDb(status));
        return list.stream().map(this::toDto).toList();
    }

    @POST
    @Path("housekeeping/run")
    @Operation(summary = "Run a housekeeping sweep now",
            description = "Compares all entities pairwise for likely duplicates and deliberates on each "
                    + "candidate pair (evidence, verdict, reasoning — recorded either way). Also runs on a "
                    + "schedule and opportunistically on ingestion. Synchronous; may take a while when "
                    + "there are many candidate pairs. Optionally supply Claude credentials in the body.")
    public Response runHousekeeping(ClaudeConfig config) {
        try {
            HousekeepingService.SweepResult r = housekeeping.sweep(ClaudeConfig.orNull(config));
            return Response.ok(new SweepResultDto(r.candidatePairs(), r.merged(), r.keptSeparate(),
                    r.openForReview(), r.skipped())).build();
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity(new TestResult(false, rootMessage(e))).build();
        }
    }

    @POST
    @Path("housekeeping/{id}/decide")
    @Operation(summary = "Decide an open housekeeping record",
            description = "Human decision on a pair the agent left open: merge or keep_separate. "
                    + "The decision and optional note become part of the permanent record.")
    public Response decideHousekeeping(@PathParam("id") long id, DecideRequest request) {
        if (request == null || request.action() == null
                || !Set.of("merge", "keep_separate").contains(request.action())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new TestResult(false, "action must be 'merge' or 'keep_separate'")).build();
        }
        try {
            HousekeepingRecord decided = housekeeping.decideManually(id, "merge".equals(request.action()),
                    request.note());
            return Response.ok(toDto(decided)).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(new TestResult(false, e.getMessage())).build();
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null || msg.isBlank() ? t.toString() : msg;
    }
}
