package com.codewalnut.bd.google;

import java.time.Instant;
import java.util.List;

/**
 * Everything GTN asks of Google (ADR-0020): OAuth tokens, Gmail send and thread metadata, calendar free/busy and
 * events. One adapter, so tests use a stub and nothing else calls Google directly.
 */
public interface GoogleApi {

    /** {@code email}: the account's address, from the ID token. */
    record Tokens(String accessToken, String refreshToken, long expiresInSeconds, String scope, String email) {}

    record Sent(String id, String threadId) {}

    /** A message's headers and Gmail's short preview; never the body. */
    record Message(String id, String threadId, String from, String rfcMessageId, long internalDateMillis, String snippet,
                   boolean autoReply, String subject) {}

    record Busy(Instant start, Instant end) {}

    record Event(String id, String meetLink, String htmlLink) {}

    Tokens exchange(String code, String redirectUri);

    Tokens refresh(String refreshToken);

    void revoke(String token);

    /** {@code raw}: the RFC 2822 message, base64url; {@code threadId}: to continue a thread, or null. */
    Sent send(String accessToken, String raw, String threadId);

    Message message(String accessToken, String id);

    List<Message> thread(String accessToken, String threadId);

    List<Busy> freeBusy(String accessToken, Instant from, Instant to);

    Event createEvent(String accessToken, String summary, String description, Instant start, Instant end,
                      String attendeeEmail, String zone);

    /** Google said no; {@code unauthorized} when the grant was revoked or expired. */
    final class GoogleException extends RuntimeException {
        private final boolean unauthorized;

        public GoogleException(String message, boolean unauthorized) {
            super(message);
            this.unauthorized = unauthorized;
        }

        public boolean unauthorized() {
            return unauthorized;
        }
    }
}
