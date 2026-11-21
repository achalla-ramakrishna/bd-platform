package com.codewalnut.bd.google;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/** Plain-text RFC 2822 messages, base64url as the Gmail API wants them (GTN, ADR-0020; sequences, ADR-0025). */
public final class Mime {

    private Mime() {
    }

    /** {@code headers}: extra headers such as List-Unsubscribe; values are cleaned of line breaks. */
    public static String plainText(String fromName, String from, String toName, String to, String subject, String body,
                                   String inReplyTo, Map<String, String> headers) {
        StringBuilder m = new StringBuilder();
        m.append("From: ").append(word(fromName)).append(" <").append(clean(from)).append(">\r\n");
        m.append("To: ").append(word(toName)).append(" <").append(clean(to)).append(">\r\n");
        m.append("Subject: ").append(word(subject)).append("\r\n");
        if (inReplyTo != null && !inReplyTo.isBlank()) {
            m.append("In-Reply-To: ").append(clean(inReplyTo)).append("\r\nReferences: ").append(clean(inReplyTo)).append("\r\n");
        }
        headers.forEach((k, v) -> m.append(clean(k)).append(": ").append(clean(v)).append("\r\n"));
        m.append("MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n");
        m.append(Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII))
            .encodeToString(body.replace("\r\n", "\n").replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8)));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(m.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** An RFC 2047 encoded word, so names and subjects in any language survive. */
    private static String word(String s) {
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(clean(s).getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n]+", " ").trim();
    }
}
