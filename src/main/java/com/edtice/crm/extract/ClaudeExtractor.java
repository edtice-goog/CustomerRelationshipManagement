package com.edtice.crm.extract;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCountTokensParams;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.edtice.crm.domain.Activity;
import com.edtice.crm.domain.SourceDocument;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class ClaudeExtractor implements Extractor {

    private static final String SYSTEM_PROMPT = """
            You extract structured CRM data from customer communications (emails, chat messages, notes).

            Rules:
            - Only extract information actually present in the communication. Never invent or guess values.
            - Use an empty string for any field that is not present.
            - Email signatures are a rich source: names, titles, companies, phone numbers, postal addresses.
            - The primary correspondent (the person who wrote the message) must be listed first in people.
            - Exclude the CRM owner themselves if they are clearly the recipient of the communication; focus on customers and external parties. If unsure, include the person.
            - confidence reflects how certain you are the value is correct for that person (1.0 = stated verbatim in a signature, lower for inferences).
            - evidence must be a short verbatim quote from the message.
            - sentiment is the customer's apparent disposition in this communication: positive, neutral, or negative.
            - commitments are concrete promises one party made to another ("I'll send X", "we'll have that fixed by Friday").
              Requests are not commitments until someone agrees to them. Attribute each commitment to the person who owes it.
            - evaluationSignal: mark partOfEvaluation true when the communication belongs to a prospective customer
              evaluating our product or service (demo requests, trials, proofs of concept, technical validation,
              purchase evaluation). Routine support of an existing deployment is NOT an evaluation.
            """;

    private static final String SUPPORT_ASSESS_PROMPT = """
            You assess the status of a customer support case from its email history. You are advising the
            account owner on customer success, not managing the ticket itself.

            Assess three independent tracks:
            1. customerDisposition — how the customer currently feels about the case and the vendor relationship.
            2. technicalProgress — whether the case is proceeding well on technical grounds: are actions being
               taken, are they working, is there momentum?
            3. rootCauseProgress — whether the work is getting to the root of the customer's actual underlying
               problem. A case can show technical activity while chasing symptoms; call that out.

            Rules:
            - The emails are given in the order received. Weight the most recent email most heavily, but judge
              trajectory across the whole history.
            - health is green when all three tracks are sound, yellow when any track needs attention, red when
              the customer relationship or the case outcome is at risk.
            - Be direct. If the case is going badly, say so plainly and say why.
            - The summary should let the account owner catch up in one read: what the problem is, where things
              stand, and what (if anything) needs to happen next.
            """;

    private static final String EVALUATION_ASSESS_PROMPT = """
            You assess the status of a prospective customer's evaluation of our product or service from its
            email history. You are advising the account owner on winning the evaluation by serving the
            customer well.

            Assess three independent tracks:
            1. customerDisposition — the evaluator's engagement and enthusiasm: are they leaning in
               (scheduling, asking substantive questions, involving colleagues) or cooling off?
            2. technicalProgress — whether the evaluation itself is progressing: demos happening, trials
               running, technical questions being answered, milestones advancing toward a decision.
            3. rootCauseProgress — whether the evaluation addresses the customer's actual business need.
               An evaluation can be busy yet aimed at the wrong problem; call that out.

            Rules:
            - The emails are given in the order received. Weight the most recent email most heavily, but judge
              trajectory across the whole history.
            - health is green when engagement and progress are sound, yellow when momentum or fit needs
              attention, red when the evaluation is at risk of stalling out or being lost.
            - Be direct about risks: unanswered questions, slipping timelines, missing stakeholders.
            - The summary should let the account owner catch up in one read: what the customer is evaluating,
              why, where it stands, and what needs to happen next.
            """;

    private static final String RELATIONSHIP_ASSESS_PROMPT = """
            You assess the health of an ongoing customer relationship-management activity from its email
            history. You are advising the account owner on customer success.

            Assess three independent tracks:
            1. customerDisposition — the customer's engagement and warmth toward the relationship.
            2. technicalProgress — whether the activity's substance is moving: meetings happening,
               follow-ups honored, value being delivered.
            3. rootCauseProgress — whether the relationship work addresses what the customer actually
               needs from the vendor, not just calendar upkeep.

            Rules:
            - The emails are given in the order received. Weight the most recent most heavily; judge trajectory.
            - health: green = sound, yellow = needs attention, red = relationship at risk.
            - Be direct, and make the summary a one-read catch-up with a clear next step.
            """;

    private static final String FULFILLMENT_PROMPT = """
            You check whether a communication fulfills any of a list of outstanding commitments.

            Rules:
            - Mark a commitment fulfilled ONLY when the communication shows the promised thing was actually
              delivered, completed, or done — an attachment sent, results shared, a fix confirmed, a meeting
              that happened.
            - A commitment merely being mentioned, re-promised, rescheduled, or apologized for is NOT fulfillment.
            - Partial delivery is not fulfillment unless the communication treats the commitment as satisfied.
            - Use the commitment ids exactly as given. If nothing is fulfilled, return an empty list.
            - evidence must be a short verbatim quote demonstrating the fulfillment.
            """;

    private static final String MERGE_PROMPT = """
            You are the data-housekeeping agent for a CRM. Decide whether two entity records refer to the
            same real-world person or organization.

            Rules:
            - Strong evidence for merging: matching email addresses or domains, matching phone numbers or
              postal addresses, one name being an obvious variant of the other (abbreviation, missing legal
              suffix, nickname) combined with shared context (same people, same relationships, same sources).
            - Similar names alone are NOT sufficient — distinct businesses often have similar names. When the
              profiles show no overlapping hard identifiers and no shared context, prefer keep_separate.
            - Merging is consequential (records are folded together); use verdict merge only when the evidence
              would convince a careful human. When genuinely torn, use uncertain — a human will decide.
            - If prior housekeeping decisions are provided, respect them: uphold the previous conclusion unless
              evidence that arrived after that decision genuinely contradicts it, and say which you did.
            - evidenceStatement is facts only; reasoning is where you draw the conclusion.
            """;

    private final String model;
    private final ConcurrentHashMap<String, AnthropicClient> clients = new ConcurrentHashMap<>();

    ClaudeExtractor(@ConfigProperty(name = "crm.extraction.model") String model) {
        this.model = model;
    }

    private AnthropicClient clientFor(ApiCredentials credentials) {
        String key = credentials == null || credentials.isBlank() ? "" : credentials.cacheKey();
        return clients.computeIfAbsent(key, k -> build(credentials));
    }

    private AnthropicClient build(ApiCredentials credentials) {
        try {
            if (credentials == null || credentials.isBlank()) {
                return AnthropicOkHttpClient.fromEnv();
            }
            var builder = AnthropicOkHttpClient.builder();
            if (credentials.apiKey() != null && !credentials.apiKey().isBlank()) {
                builder.apiKey(credentials.apiKey());
            } else {
                String envKey = System.getenv("ANTHROPIC_API_KEY");
                if (envKey == null || envKey.isBlank()) {
                    throw new IllegalStateException("No apiKey supplied and ANTHROPIC_API_KEY is not set");
                }
                builder.apiKey(envKey);
            }
            if (credentials.baseUrl() != null && !credentials.baseUrl().isBlank()) {
                builder.baseUrl(credentials.baseUrl());
            }
            return builder.build();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not create Anthropic client — check the API key / endpoint (" + e.getMessage() + ")", e);
        }
    }

    @Override
    public String verifyConnectivity(ApiCredentials credentials) {
        // count_tokens hits the real endpoint with real auth but bills nothing.
        long tokens = clientFor(credentials).messages().countTokens(
                MessageCountTokensParams.builder()
                        .model(model)
                        .addUserMessage("connectivity check")
                        .build())
                .inputTokens();
        return "Connected. Model '" + model + "' reachable"
                + (credentials != null && credentials.baseUrl() != null && !credentials.baseUrl().isBlank()
                        ? " via " + credentials.baseUrl() : "")
                + " (test prompt = " + tokens + " tokens).";
    }

    @Override
    public MessageAnalysis analyze(SourceDocument doc, ApiCredentials credentials) {
        String userMessage = "Source type: " + doc.sourceType() + "\n"
                + (doc.metadataJson() == null || doc.metadataJson().isBlank()
                        ? "" : "Metadata: " + doc.metadataJson() + "\n")
                + "--- COMMUNICATION START ---\n"
                + doc.rawContent()
                + "\n--- COMMUNICATION END ---";

        StructuredMessageCreateParams<MessageAnalysis> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(SYSTEM_PROMPT)
                .outputConfig(MessageAnalysis.class)
                .addUserMessage(userMessage)
                .build();

        var response = clientFor(credentials).messages().create(params);

        Optional<MessageAnalysis> result = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(typed -> typed.text())
                .findFirst();

        return result.orElseThrow(() -> new IllegalStateException(
                "Extraction returned no structured content (stop_reason=" + response.stopReason() + ")"));
    }

    @Override
    public AssessmentResult assessActivity(Activity activity, List<SourceDocument> history,
                                           ApiCredentials credentials) {
        String prompt = switch (activity.kind()) {
            case Activity.SUPPORT -> SUPPORT_ASSESS_PROMPT;
            case Activity.EVALUATION -> EVALUATION_ASSESS_PROMPT;
            default -> RELATIONSHIP_ASSESS_PROMPT;
        };

        StringBuilder thread = new StringBuilder();
        thread.append(activity.kindLabel()).append(": ").append(activity.displayLabel());
        if (activity.token() != null && !activity.token().isBlank()) {
            thread.append(" (token ").append(activity.token()).append(")");
        }
        thread.append("\nCommunication history, in the order received (")
                .append(history.size()).append(" items):\n");
        int n = 0;
        for (SourceDocument doc : history) {
            n++;
            thread.append("\n===== ITEM ").append(n).append(" of ").append(history.size())
                    .append(" (ingested ").append(doc.receivedAt()).append(") =====\n")
                    .append(doc.rawContent()).append('\n');
        }
        thread.append("\n===== END OF HISTORY =====\nAssess the ")
                .append(activity.kindLabel().toLowerCase())
                .append(" as of the most recent communication.");

        StructuredMessageCreateParams<AssessmentResult> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(prompt)
                .outputConfig(AssessmentResult.class)
                .addUserMessage(thread.toString())
                .build();

        var response = clientFor(credentials).messages().create(params);

        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(typed -> typed.text())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Assessment returned no structured content (stop_reason=" + response.stopReason() + ")"));
    }

    @Override
    public FulfillmentResult checkFulfillment(SourceDocument doc, String candidatesText,
                                              ApiCredentials credentials) {
        String userMessage = "Outstanding commitments:\n" + candidatesText
                + "\n--- COMMUNICATION START ---\n"
                + doc.rawContent()
                + "\n--- COMMUNICATION END ---\n"
                + "Which of the outstanding commitments, if any, does this communication fulfill?";

        StructuredMessageCreateParams<FulfillmentResult> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(FULFILLMENT_PROMPT)
                .outputConfig(FulfillmentResult.class)
                .addUserMessage(userMessage)
                .build();

        var response = clientFor(credentials).messages().create(params);

        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(typed -> typed.text())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Fulfillment check returned no structured content (stop_reason=" + response.stopReason() + ")"));
    }

    @Override
    public MergeVerdict judgeMerge(String profileA, String profileB, String priorHistory,
                                   ApiCredentials credentials) {
        String userMessage = "Are these two records the same real-world entity?\n\n"
                + "===== RECORD A =====\n" + profileA + "\n\n"
                + "===== RECORD B =====\n" + profileB + "\n\n"
                + (priorHistory == null || priorHistory.isBlank()
                        ? "No prior housekeeping decisions exist for this pair.\n"
                        : "===== PRIOR HOUSEKEEPING DECISIONS FOR THIS PAIR =====\n" + priorHistory + "\n");

        StructuredMessageCreateParams<MergeVerdict> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(MERGE_PROMPT)
                .outputConfig(MergeVerdict.class)
                .addUserMessage(userMessage)
                .build();

        var response = clientFor(credentials).messages().create(params);

        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(typed -> typed.text())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Merge judgment returned no structured content (stop_reason=" + response.stopReason() + ")"));
    }
}
