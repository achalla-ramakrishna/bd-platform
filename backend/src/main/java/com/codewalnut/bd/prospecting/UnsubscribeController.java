package com.codewalnut.bd.prospecting;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The unsubscribe link in every sequence email (ADR-0025). GET shows a page with one button (mail scanners open links,
 * so a GET never unsubscribes); POST unsubscribes, from that button or from Gmail's own button (RFC 8058).
 */
@RestController
class UnsubscribeController {

    private final SequenceService sequences;

    UnsubscribeController(SequenceService sequences) {
        this.sequences = sequences;
    }

    @GetMapping(value = "/u/{token}", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> page(@PathVariable String token) {
        return sequences.unsubscribeName(token)
            .map(name -> ResponseEntity.ok(html("Unsubscribe", "<p>Stop emails from CodeWalnut to you" + (name.isBlank() ? "" : ", "
                + escape(name)) + "?</p><form method=\"post\"><button type=\"submit\">Unsubscribe</button></form>")))
            .orElse(ResponseEntity.status(404).body(html("Link not found", "<p>This unsubscribe link is not valid.</p>")));
    }

    @PostMapping(value = "/u/{token}", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> unsubscribe(@PathVariable String token) {
        if (!sequences.unsubscribe(token)) {
            return ResponseEntity.status(404).body(html("Link not found", "<p>This unsubscribe link is not valid.</p>"));
        }
        return ResponseEntity.ok(html("Unsubscribed", "<p>Done. You will not get more emails from us.</p>"));
    }

    private static String html(String title, String body) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
            + "<meta name=\"robots\" content=\"noindex\"><title>" + title + "</title><style>body{font-family:system-ui,sans-serif;max-width:32rem;"
            + "margin:4rem auto;padding:0 1rem;color:#222}button{font:inherit;padding:.5rem 1rem;cursor:pointer}</style></head><body><h1>"
            + title + "</h1>" + body + "</body></html>";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
