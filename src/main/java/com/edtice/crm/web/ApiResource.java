package com.edtice.crm.web;

import com.edtice.crm.cases.CaseService;
import com.edtice.crm.domain.CaseAssessment;
import com.edtice.crm.domain.SourceDocument;
import com.edtice.crm.domain.SupportCase;
import com.edtice.crm.domain.HousekeepingRecord;
import com.edtice.crm.domain.HousekeepingStatus;
import com.edtice.crm.extract.ApiCredentials;
import com.edtice.crm.extract.Extractor;
import com.edtice.crm.housekeeping.HousekeepingService;
import com.edtice.crm.ingest.IngestService;
import com.edtice.crm.store.EntityStore;
import com.edtice.crm.store.HousekeepingStore;
import com.edtice.crm.pipeline.PipelineService;
import com.edtice.crm.store.CaseStore;
import com.edtice.crm.store.StagingStore;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.Optional;

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
    private final CaseService caseService;
    private final CaseStore caseStore;
    private final HousekeepingService housekeeping;
    private final HousekeepingStore housekeepingStore;
    private final EntityStore entities;

    ApiResource(Extractor extractor, IngestService ingest, StagingStore staging,
                PipelineService pipeline, CaseService caseService, CaseStore caseStore,
                HousekeepingService housekeeping, HousekeepingStore housekeepingStore,
                EntityStore entities) {
        this.extractor = extractor;
        this.ingest = ingest;
        this.staging = staging;
        this.pipeline = pipeline;
        this.caseService = caseService;
        this.caseStore = caseStore;
        this.housekeeping = housekeeping;
        this.housekeepingStore = housekeepingStore;
        this.entities = entities;
    }

    // --- request/response shapes ---

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
            + "ref:...:ref thread token). Follow 'instructions' — for newly tracked cases they explain how "
            + "to pull in the rest of the thread for an accurate status.")
    public record CaseInfoDto(long caseId, String token, String caseNumber, String subject,
                              boolean newlyTracked, int emailCount, String instructions) {
        static CaseInfoDto of(CaseService.CaseInfo info) {
            return info == null ? null : new CaseInfoDto(info.caseId(), info.token(), info.caseNumber(),
                    info.subject(), info.newlyTracked(), info.emailCount(), info.instructions());
        }
    }

    public record IngestResponse(Long documentId, String status, boolean duplicate, String detail,
                                 CaseInfoDto supportCase) {
    }

    public record CaseAssessmentDto(String health, String customerDisposition, String customerDispositionNotes,
                                    String technicalProgress, String technicalProgressNotes,
                                    String rootCauseProgress, String rootCauseNotes,
                                    String summary, Instant assessedAt, Long triggeredByDocument) {
        static CaseAssessmentDto of(CaseAssessment a) {
            return a == null ? null : new CaseAssessmentDto(a.health(), a.customerDisposition(),
                    a.customerDispositionNotes(), a.technicalProgress(), a.technicalProgressNotes(),
                    a.rootCauseProgress(), a.rootCauseNotes(), a.summary(), a.createdAt(), a.triggeredByDoc());
        }
    }

    public record CaseSummaryDto(long id, String token, String caseNumber, String subject,
                                 int emailCount, CaseAssessmentDto currentStatus) {
    }

    public record CaseDetailDto(long id, String token, String caseNumber, String subject,
                                java.util.List<DocumentStatus> emails,
                                CaseAssessmentDto currentStatus,
                                java.util.List<CaseAssessmentDto> assessmentHistory) {
    }

    public record DocumentStatus(long id, String sourceType, String status, String error, Instant receivedAt) {
        static DocumentStatus of(SourceDocument d) {
            return new DocumentStatus(d.id(), d.sourceType(), d.status().db(), d.error(), d.receivedAt());
        }
    }

    public record TestResult(boolean ok, String detail) {
    }

    // --- endpoints ---

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
                    + "contact details, sentiment). Returns the staged document id; poll "
                    + "/api/documents/{id} for extraction status. Duplicate submissions "
                    + "(same externalId or same content) are detected and not re-processed.")
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
            CaseInfoDto caseInfo = outcome.get().caseInfo().map(CaseInfoDto::of).orElse(null);
            return Response.accepted(new IngestResponse(outcome.get().doc().id(),
                    outcome.get().doc().status().db(), false,
                    "Staged; extraction running in background", caseInfo)).build();
        }
        Optional<SourceDocument> existing = staging.byExternalId(externalId);
        CaseInfoDto caseInfo = caseService.infoFor(request.content()).map(CaseInfoDto::of).orElse(null);
        return Response.ok(new IngestResponse(existing.map(SourceDocument::id).orElse(null),
                existing.map(d -> d.status().db()).orElse(null), true,
                "Already ingested; not re-processed", caseInfo)).build();
    }

    @GET
    @Path("cases")
    @Operation(summary = "List tracked support cases",
            description = "Each entry carries the current (most recent) assessment: health, customer "
                    + "disposition, technical progress, and root-cause progress.")
    public java.util.List<CaseSummaryDto> cases() {
        return caseStore.listAll().stream()
                .map(sc -> new CaseSummaryDto(sc.id(), sc.caseToken(), sc.caseNumber(), sc.subject(),
                        caseStore.documentCount(sc.id()),
                        caseStore.latestAssessment(sc.id()).map(CaseAssessmentDto::of).orElse(null)))
                .toList();
    }

    @GET
    @Path("cases/{id}")
    @Operation(summary = "Get a tracked case with its emails and full assessment history")
    public CaseDetailDto caseDetail(@PathParam("id") long id) {
        SupportCase sc = caseStore.byId(id).orElseThrow(NotFoundException::new);
        java.util.List<CaseAssessmentDto> history = caseStore.assessments(id).stream()
                .map(CaseAssessmentDto::of).toList();
        return new CaseDetailDto(sc.id(), sc.caseToken(), sc.caseNumber(), sc.subject(),
                caseStore.documents(id).stream().map(DocumentStatus::of).toList(),
                history.isEmpty() ? null : history.get(0),
                history);
    }

    @POST
    @Path("cases/{id}/reassess")
    @Operation(summary = "Force a case status recalculation from the full email history",
            description = "Normally automatic on every new case email; use this after prompt changes "
                    + "or to refresh manually. Optionally supply Claude credentials in the body.")
    public Response reassessCase(@PathParam("id") long id, ClaudeConfig config) {
        SupportCase sc = caseStore.byId(id).orElseThrow(NotFoundException::new);
        try {
            caseService.reassess(sc.id(), null, ClaudeConfig.orNull(config));
            return Response.ok(caseDetail(id)).build();
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity(new TestResult(false, rootMessage(e))).build();
        }
    }

    @GET
    @Path("documents/{id}")
    @Operation(summary = "Get a staged document's extraction status",
            description = "Status values: staged, processing, extracted, error.")
    public DocumentStatus document(@PathParam("id") long id) {
        return staging.byId(id).map(DocumentStatus::of).orElseThrow(NotFoundException::new);
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
                caseService.infoFor(doc.rawContent()).map(CaseInfoDto::of).orElse(null))).build();
    }

    // --- housekeeping ---

    public record EntityRef(long id, String name, boolean merged) {
    }

    public record HousekeepingDto(long id, String kind, String status, String evidence, String reasoning,
                                  String decidedBy, double confidence, Long priorRecordId,
                                  Instant createdAt, Instant decidedAt,
                                  java.util.List<EntityRef> entities) {
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
        java.util.List<EntityRef> refs = r.entityIds().stream()
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
    public java.util.List<HousekeepingDto> housekeepingRecords(@jakarta.ws.rs.QueryParam("status") String status) {
        java.util.List<HousekeepingRecord> list = status == null || status.isBlank()
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
                || !java.util.Set.of("merge", "keep_separate").contains(request.action())) {
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
