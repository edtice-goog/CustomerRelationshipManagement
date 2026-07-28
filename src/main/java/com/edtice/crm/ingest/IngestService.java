package com.edtice.crm.ingest;

import com.edtice.crm.activities.ActivityService;
import com.edtice.crm.domain.SourceDocument;
import com.edtice.crm.extract.ApiCredentials;
import com.edtice.crm.pipeline.PipelineService;
import com.edtice.crm.store.StagingStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * Entry point for all sources. v1 has manual paste; future ingestors (Outlook,
 * Teams, Copilot exports) call the same {@link #ingest} with their own sourceType
 * and a stable externalId for dedup.
 */
@ApplicationScoped
public class IngestService {

    private final StagingStore staging;
    private final PipelineService pipeline;
    private final ActivityService activityService;
    private final ObjectMapper mapper;

    IngestService(StagingStore staging, PipelineService pipeline, ActivityService activityService,
                  ObjectMapper mapper) {
        this.staging = staging;
        this.pipeline = pipeline;
        this.activityService = activityService;
        this.mapper = mapper;
    }

    /** A newly staged document plus, when a ticket token was recognized, its support-activity linkage. */
    public record IngestOutcome(SourceDocument doc, Optional<ActivityService.ActivityInfo> activityInfo) {
    }

    /** Stage a manually pasted communication and kick off extraction. Returns empty if it was already ingested. */
    public Optional<IngestOutcome> ingestPaste(String content, String note) {
        String metadata = null;
        if (note != null && !note.isBlank()) {
            try {
                metadata = mapper.writeValueAsString(Map.of("note", note));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return ingest(SourceDocument.MANUAL_PASTE, contentHash(content), content, metadata, null);
    }

    public Optional<IngestOutcome> ingest(String sourceType, String externalId, String content,
                                          String metadataJson, ApiCredentials credentials) {
        Optional<SourceDocument> doc = staging.insertIfNew(sourceType, externalId, content, metadataJson);
        if (doc.isEmpty()) {
            return Optional.empty();
        }
        // Support-activity linkage is deterministic and instant, so it happens
        // synchronously here — before extraction — and the caller gets tracking
        // info in the ingest response. Evaluation linkage is model-signaled and
        // happens during extraction.
        Optional<ActivityService.ActivityInfo> activityInfo = activityService.registerSupport(doc.get());
        pipeline.submit(doc.get().id(), credentials);
        return Optional.of(new IngestOutcome(doc.get(), activityInfo));
    }

    public static String contentHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.strip().getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
