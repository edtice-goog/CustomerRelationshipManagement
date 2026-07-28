package com.edtice.crm.store;

import com.edtice.crm.domain.DocStatus;
import com.edtice.crm.domain.SourceDocument;

import java.util.List;
import java.util.Optional;

public interface StagingStore {

    /** Insert unless a document with this externalId already exists. Returns empty on duplicate. */
    Optional<SourceDocument> insertIfNew(String sourceType, String externalId, String rawContent, String metadataJson);

    Optional<SourceDocument> byId(long id);

    Optional<SourceDocument> byExternalId(String externalId);

    List<SourceDocument> listRecent(int limit);

    void setStatus(long id, DocStatus status, String error);
}
