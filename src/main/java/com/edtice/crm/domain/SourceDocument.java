package com.edtice.crm.domain;

import java.time.Instant;

/**
 * Raw captured communication in the staging area. Content is stored verbatim and
 * kept forever so extraction can be re-run when models or prompts improve.
 */
public record SourceDocument(
        long id,
        String sourceType,
        String externalId,
        String rawContent,
        String metadataJson,
        Instant receivedAt,
        DocStatus status,
        String error) {

    public static final String MANUAL_PASTE = "manual_paste";

    public String preview() {
        String flat = rawContent.replaceAll("\\s+", " ").trim();
        return flat.length() <= 120 ? flat : flat.substring(0, 117) + "...";
    }
}
