package com.codewalnut.bd.network;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public profile page at /p/{token} (ADR-0019): no sign-in, no cookies. The page, its video (with byte ranges
 * so phones can play and seek), captions, and the events its script reports.
 */
@RestController
class PublicProfileController {

    record EventBody(String kind, String value) {}

    private static final Pattern RANGE = Pattern.compile("^bytes=(\\d*)-(\\d*)$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ProfileService profiles;

    PublicProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/p/{token}")
    void page(@PathVariable String token, HttpServletResponse res) throws IOException {
        Optional<ProfileService.View> view = profiles.view(token);
        String nonce = nonce();
        headers(res, nonce);
        res.setContentType("text/html;charset=UTF-8");
        if (view.isEmpty()) {
            res.setStatus(HttpStatus.NOT_FOUND.value());
            res.getWriter().write(ProfileHtml.gone());
            return;
        }
        ProfileService.Page p = view.get().page();
        String base = "/p/" + token;
        res.getWriter().write(ProfileHtml.page(view.get(), base, p.videoBytes() == null ? null : base + "/video",
            p.hasCaptions() ? base + "/captions.vtt" : null, false, nonce));
    }

    @GetMapping("/p/{token}/video")
    void video(@PathVariable String token, HttpServletRequest req, HttpServletResponse res) throws IOException {
        Optional<ProfileService.Video> video = profiles.view(token).flatMap(v -> profiles.video(v.page().ownerId()));
        if (video.isEmpty()) {
            res.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }
        stream(profiles, video.get(), req, res);
    }

    @GetMapping("/p/{token}/captions.vtt")
    void captions(@PathVariable String token, HttpServletResponse res) throws IOException {
        Optional<String> vtt = profiles.view(token).filter(v -> v.page().id() != 0).flatMap(v -> profiles.captionsOf(v.page().id()));
        if (vtt.isEmpty()) {
            res.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }
        res.setContentType("text/vtt;charset=UTF-8");
        res.getWriter().write(vtt.get());
    }

    /** Events from the page's script. Always 204, so the page learns nothing about links or limits. */
    @PostMapping("/p/{token}/events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void event(@PathVariable String token, @RequestBody(required = false) EventBody body) {
        if (body != null) {
            profiles.record(token, body.kind(), body.value());
        }
    }

    /** Serves the video, honouring a single byte range (players ask for ranges to start and seek). */
    static void stream(ProfileService profiles, ProfileService.Video v, HttpServletRequest req, HttpServletResponse res)
            throws IOException {
        long size = v.bytes();
        long from = 0;
        long to = size - 1;
        String range = req.getHeader("Range");
        res.setHeader("Accept-Ranges", "bytes");
        res.setHeader("Cache-Control", "private, max-age=3600");
        res.setContentType(v.type());
        if (range != null) {
            Matcher m = RANGE.matcher(range.trim());
            if (!m.matches() || (m.group(1).isEmpty() && m.group(2).isEmpty())) {
                res.setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
                res.setHeader("Content-Range", "bytes */" + size);
                return;
            }
            if (m.group(1).isEmpty()) {
                from = Math.max(0, size - Long.parseLong(m.group(2)));
            } else {
                from = Long.parseLong(m.group(1));
                if (!m.group(2).isEmpty()) {
                    to = Math.min(size - 1, Long.parseLong(m.group(2)));
                }
            }
            if (from >= size || from > to) {
                res.setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
                res.setHeader("Content-Range", "bytes */" + size);
                return;
            }
            res.setStatus(HttpStatus.PARTIAL_CONTENT.value());
            res.setHeader("Content-Range", "bytes " + from + "-" + to + "/" + size);
        }
        res.setContentLengthLong(to - from + 1);
        if (!"HEAD".equals(req.getMethod())) {
            profiles.writeVideo(v.pageId(), from, to, res.getOutputStream());
        }
    }

    static void headers(HttpServletResponse res, String nonce) {
        res.setHeader("Cache-Control", "no-store");
        res.setHeader("X-Robots-Tag", "noindex, nofollow");
        res.setHeader("Referrer-Policy", "no-referrer");
        res.setHeader("Content-Security-Policy", "default-src 'none'; script-src 'nonce-" + nonce + "'; style-src 'unsafe-inline'; "
            + "media-src 'self'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
    }

    static String nonce() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }
}
