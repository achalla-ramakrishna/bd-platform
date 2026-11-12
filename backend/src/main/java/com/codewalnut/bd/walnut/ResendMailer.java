package com.codewalnut.bd.walnut;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

/** Email through Resend's HTTP API (no mail library needed); set MAIL_API_KEY and MAIL_FROM. */
class ResendMailer implements Mailer {

    private static final URI ENDPOINT = URI.create("https://api.resend.com/emails");

    private final String apiKey;
    private final String from;
    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    ResendMailer(String apiKey, String from, JsonMapper json) {
        this.apiKey = apiKey.strip();
        this.from = from.strip();
        this.json = json;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void send(String to, String subject, String html) {
        String body = json.writeValueAsString(Map.of("from", from, "to", List.of(to), "subject", subject, "html", html));
        HttpRequest request = HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Email provider answered " + response.statusCode() + ": "
                    + response.body().substring(0, Math.min(200, response.body().length())));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not reach the email provider: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while sending email", e);
        }
    }
}
