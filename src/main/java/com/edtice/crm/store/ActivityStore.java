package com.edtice.crm.store;

import com.edtice.crm.domain.Activity;
import com.edtice.crm.domain.ActivityAssessment;
import com.edtice.crm.domain.ActivityState;
import com.edtice.crm.domain.SourceDocument;

import java.util.List;
import java.util.Optional;

public interface ActivityStore {

    Activity create(String kind, String label, String token, String reference, Long primaryEntityId);

    Optional<Activity> byId(long id);

    Optional<Activity> byToken(String token);

    /** All activities, newest first; optionally filtered by kind and/or state (null = any). */
    List<Activity> list(String kind, ActivityState state);

    /** Open activity of a kind anchored to an entity — how evaluation emails find their evaluation. */
    Optional<Activity> openByKindAndEntity(String kind, long primaryEntityId);

    void setState(long id, ActivityState state);

    void setOpportunity(long id, Long opportunityId);

    /** Idempotent — linking the same document twice is a no-op. */
    void linkDocument(long activityId, long docId);

    List<Activity> activitiesForDocument(long docId);

    /** The activity's documents in received order — the history fed to assessment. */
    List<SourceDocument> documents(long activityId);

    int documentCount(long activityId);

    ActivityAssessment insertAssessment(long activityId, Long triggeredByDoc, String health,
                                        String customerDisposition, String customerDispositionNotes,
                                        String technicalProgress, String technicalProgressNotes,
                                        String rootCauseProgress, String rootCauseNotes,
                                        String summary);

    /** Newest first. */
    List<ActivityAssessment> assessments(long activityId);

    Optional<ActivityAssessment> latestAssessment(long activityId);
}
