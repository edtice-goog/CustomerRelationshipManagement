package com.edtice.crm.extract;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCountTokensParams;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.edtice.crm.domain.SourceDocument;
import com.edtice.crm.domain.SupportCase;
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
            """;

    private static final String CASE_PROMPT = """
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
    public CaseStatus assessCase(SupportCase supportCase, List<SourceDocument> history,
                                 ApiCredentials credentials) {
        StringBuilder thread = new StringBuilder();
        thread.append("Support case ").append(supportCase.caseToken());
        if (supportCase.caseNumber() != null && !supportCase.caseNumber().isBlank()) {
            thread.append(" (case number ").append(supportCase.caseNumber()).append(")");
        }
        thread.append("\nEmail history, in the order received (").append(history.size()).append(" emails):\n");
        int n = 0;
        for (SourceDocument doc : history) {
            n++;
            thread.append("\n===== EMAIL ").append(n).append(" of ").append(history.size())
                    .append(" (ingested ").append(doc.receivedAt()).append(") =====\n")
                    .append(doc.rawContent()).append('\n');
        }
        thread.append("\n===== END OF HISTORY =====\nAssess the case as of the most recent email.");

        StructuredMessageCreateParams<CaseStatus> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(CASE_PROMPT)
                .outputConfig(CaseStatus.class)
                .addUserMessage(thread.toString())
                .build();

        var response = clientFor(credentials).messages().create(params);

        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(typed -> typed.text())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Case assessment returned no structured content (stop_reason=" + response.stopReason() + ")"));
    }
}
