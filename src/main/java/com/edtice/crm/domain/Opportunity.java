package com.edtice.crm.domain;

import java.time.Instant;

/**
 * A deliberately generic opportunity anchor — this CRM is not in the
 * opportunity-management business. A GUID plus the metadata needed to reference
 * an external system; for users without one, a local key-value attribute bag
 * (see OpportunityStore). Anything richer belongs in a real opportunity system.
 */
public record Opportunity(
        long id,
        String guid,
        String name,
        String externalSystem,
        String externalRef,
        Instant createdAt) {

    public boolean isExternal() {
        return externalSystem != null && !externalSystem.isBlank();
    }
}
