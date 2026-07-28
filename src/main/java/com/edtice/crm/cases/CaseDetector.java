package com.edtice.crm.cases;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic recognition of ticket-system markers in raw email text.
 * v1 recognizes SalesForce thread tokens ("ref:_00D...._500....:ref"), which
 * SalesForce embeds in the subject or body of every case email. Other ticket
 * systems get their own patterns here later.
 */
public final class CaseDetector {

    /** SalesForce email-to-case thread token, e.g. ref:_00D36JGpW._5006F2vzC0P:ref */
    private static final Pattern SF_TOKEN = Pattern.compile(
            "(?i)ref:\\s*([A-Za-z0-9._]{8,})\\s*:ref");

    /** A case number when it appears near the word "case", e.g. "Case #00123456" or "Case: 00123456". */
    private static final Pattern CASE_NUMBER = Pattern.compile(
            "(?i)case\\s*(?:number|no\\.?|#)?\\s*[:#]?\\s*(\\d{7,10})");

    private static final Pattern SUBJECT = Pattern.compile("(?im)^subject:\\s*(.+)$");

    private static final Pattern REPLY_PREFIX = Pattern.compile("(?i)^(re|fwd?|aw):\\s*");

    private CaseDetector() {
    }

    public record Detection(String token, String caseNumber, String subject) {
    }

    public static Optional<Detection> detect(String content) {
        Matcher token = SF_TOKEN.matcher(content);
        if (!token.find()) {
            return Optional.empty();
        }
        String normalized = "ref:" + token.group(1) + ":ref";

        Matcher num = CASE_NUMBER.matcher(content);
        String caseNumber = num.find() ? num.group(1) : null;

        Matcher subj = SUBJECT.matcher(content);
        String subject = null;
        if (subj.find()) {
            subject = subj.group(1).strip();
            String prev;
            do {
                prev = subject;
                subject = REPLY_PREFIX.matcher(subject).replaceFirst("").strip();
            } while (!subject.equals(prev));
            // The token itself often rides in the subject; drop it for display.
            subject = SF_TOKEN.matcher(subject).replaceAll("").replaceAll("\\[\\s*\\]", "").strip();
        }
        return Optional.of(new Detection(normalized, caseNumber, subject));
    }
}
