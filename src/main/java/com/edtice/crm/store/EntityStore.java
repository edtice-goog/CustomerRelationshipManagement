package com.edtice.crm.store;

import com.edtice.crm.domain.Entity;

import java.util.List;
import java.util.Optional;

public interface EntityStore {

    Entity create(String kind, String displayName);

    /** Raw row — a merged entity is returned as-is with its mergedInto pointer. */
    Optional<Entity> byId(long id);

    /**
     * Case-insensitive name lookup that follows merge pointers: a merged entity's
     * name acts as an alias resolving to the surviving entity.
     */
    Optional<Entity> findByName(String kind, String displayName);

    /** Live (unmerged) entities of a kind. */
    List<Entity> listByKind(String kind);

    /**
     * Fold the loser into the winner: observations and relationships repoint to
     * the winner; the loser remains as a tombstone with mergedInto set, so its
     * name keeps resolving. Returns the winner.
     */
    Entity merge(long loserId, long winnerId);
}
