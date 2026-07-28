package com.edtice.crm.extract;

/**
 * Optional per-request Claude API credentials. Lets a caller use a different key
 * (e.g. a corporate key with its own data-protection agreement) and/or a different
 * endpoint than the server's default. Held in memory only — never persisted.
 */
public record ApiCredentials(String baseUrl, String apiKey) {

    public boolean isBlank() {
        return (baseUrl == null || baseUrl.isBlank()) && (apiKey == null || apiKey.isBlank());
    }

    /** Stable cache key; never logged. */
    public String cacheKey() {
        return (baseUrl == null ? "" : baseUrl) + "|" + (apiKey == null ? "" : apiKey);
    }
}
