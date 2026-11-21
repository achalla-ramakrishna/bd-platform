package com.codewalnut.bd.google;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Google's REST APIs over HTTPS, without a Google client library (ADR-0020). */
class HttpGoogleApi implements GoogleApi {

    private static final String TOKEN = "https://oauth2.googleapis.com/token";
    private static final String REVOKE = "https://oauth2.googleapis.com/revoke";
    private static final String GMAIL = "https://gmail.googleapis.com/gmail/v1/users/me";
    private static final String CALENDAR = "https://www.googleapis.com/calendar/v3";
    private static final String HEADERS = "&metadataHeaders=From&metadataHeaders=Message-ID&metadataHeaders=Auto-Submitted&metadataHeaders=Subject";

    private final GoogleProperties props;
    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    HttpGoogleApi(GoogleProperties props, JsonMapper json) {
        this.props = props;
        this.json = json;
    }

    @Override
    public Tokens exchange(String code, String redirectUri) {
        return tokens(form(Map.of("code", code, "client_id", props.clientId(), "client_secret", props.clientSecret(),
            "redirect_uri", redirectUri, "grant_type", "authorization_code")), null);
    }

    @Override
    public Tokens refresh(String refreshToken) {
        return tokens(form(Map.of("refresh_token", refreshToken, "client_id", props.clientId(),
            "client_secret", props.clientSecret(), "grant_type", "refresh_token")), refreshToken);
    }

    @Override
    public void revoke(String token) {
        call(HttpRequest.newBuilder(URI.create(REVOKE + "?token=" + enc(token)))
            .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.noBody()), true);
    }

    @Override
    public Sent send(String accessToken, String raw, String threadId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("raw", raw);
        if (threadId != null) {
            body.put("threadId", threadId);
        }
        JsonNode n = call(post(GMAIL + "/messages/send", accessToken, body), false);
        return new Sent(n.path("id").asString(), n.path("threadId").asString());
    }

    @Override
    public Message message(String accessToken, String id) {
        return message(call(get(GMAIL + "/messages/" + enc(id) + "?format=metadata" + HEADERS, accessToken), false));
    }

    @Override
    public List<Message> thread(String accessToken, String threadId) {
        JsonNode n = call(get(GMAIL + "/threads/" + enc(threadId) + "?format=metadata" + HEADERS, accessToken), false);
        List<Message> out = new ArrayList<>();
        for (JsonNode m : n.path("messages").values()) {
            out.add(message(m));
        }
        return out;
    }

    @Override
    public List<Busy> freeBusy(String accessToken, Instant from, Instant to) {
        JsonNode n = call(post(CALENDAR + "/freeBusy", accessToken, Map.of("timeMin", from.toString(), "timeMax", to.toString(),
            "items", List.of(Map.of("id", "primary")))), false);
        List<Busy> out = new ArrayList<>();
        for (JsonNode b : n.path("calendars").path("primary").path("busy").values()) {
            out.add(new Busy(Instant.parse(b.path("start").asString()), Instant.parse(b.path("end").asString())));
        }
        return out;
    }

    @Override
    public Event createEvent(String accessToken, String summary, String description, Instant start, Instant end,
                             String attendeeEmail, String zone) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("summary", summary);
        body.put("description", description);
        body.put("start", Map.of("dateTime", start.toString(), "timeZone", zone));
        body.put("end", Map.of("dateTime", end.toString(), "timeZone", zone));
        body.put("attendees", List.of(Map.of("email", attendeeEmail)));
        body.put("conferenceData", Map.of("createRequest", Map.of("requestId", UUID.randomUUID().toString(),
            "conferenceSolutionKey", Map.of("type", "hangoutsMeet"))));
        JsonNode n = call(post(CALENDAR + "/calendars/primary/events?conferenceDataVersion=1&sendUpdates=all", accessToken, body), false);
        return new Event(n.path("id").asString(), n.path("hangoutLink").isMissingNode() ? null : n.path("hangoutLink").asString(),
            n.path("htmlLink").asString());
    }

    // ---- helpers ----

    private Tokens tokens(String form, String keepRefresh) {
        JsonNode n = call(HttpRequest.newBuilder(URI.create(TOKEN)).header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form)), false);
        String refresh = n.path("refresh_token").isMissingNode() ? keepRefresh : n.path("refresh_token").asString();
        return new Tokens(n.path("access_token").asString(), refresh, n.path("expires_in").asLong(3600),
            n.path("scope").asString(), emailFrom(n.path("id_token").isMissingNode() ? null : n.path("id_token").asString()));
    }

    /** The email claim of an ID token received straight from Google's token endpoint over TLS. */
    private String emailFrom(String idToken) {
        if (idToken == null) {
            return null;
        }
        String[] parts = idToken.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        JsonNode claims = json.readTree(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        return claims.path("email").isMissingNode() ? null : claims.path("email").asString();
    }

    private static Message message(JsonNode m) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (JsonNode h : m.path("payload").path("headers").values()) {
            headers.putIfAbsent(h.path("name").asString().toLowerCase(java.util.Locale.ROOT), h.path("value").asString());
        }
        String auto = headers.getOrDefault("auto-submitted", "no");
        return new Message(m.path("id").asString(), m.path("threadId").asString(), headers.getOrDefault("from", ""),
            headers.get("message-id"), m.path("internalDate").asLong(0), m.path("snippet").asString(),
            !auto.isBlank() && !"no".equalsIgnoreCase(auto.trim()), headers.get("subject"));
    }

    private HttpRequest.Builder get(String url, String accessToken) {
        return HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + accessToken).GET();
    }

    private HttpRequest.Builder post(String url, String accessToken, Object body) {
        return HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + accessToken)
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    }

    private JsonNode call(HttpRequest.Builder request, boolean ignoreErrors) {
        try {
            HttpResponse<String> res = http.send(request.timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                if (ignoreErrors) {
                    return json.readTree("{}");
                }
                String text = res.body() == null ? "" : res.body();
                boolean unauthorized = res.statusCode() == 401 || text.contains("invalid_grant");
                throw new GoogleException("Google answered " + res.statusCode() + ": " + text.substring(0, Math.min(200, text.length())),
                    unauthorized);
            }
            return json.readTree(res.body() == null || res.body().isBlank() ? "{}" : res.body());
        } catch (IOException e) {
            throw new GoogleException("Could not reach Google: " + e.getMessage(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GoogleException("Interrupted while calling Google", false);
        }
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream().map(e -> enc(e.getKey()) + "=" + enc(e.getValue())).collect(Collectors.joining("&"));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
