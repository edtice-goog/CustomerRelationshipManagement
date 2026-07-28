package com.edtice.crm.store;

import com.edtice.crm.domain.Entity;

import java.util.List;
import java.util.Optional;

public interface EntityStore {

    Entity create(String kind, String displayName);

    Optional<Entity> byId(long id);

    Optional<Entity> findByName(String kind, String displayName);

    List<Entity> listByKind(String kind);
}
