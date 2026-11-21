package com.codewalnut.bd.network;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.codewalnut.bd.contacts.Phones;
import com.codewalnut.bd.shared.AuditLog;
import com.codewalnut.bd.shared.ConflictException;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.NotFoundException;
import com.codewalnut.bd.shared.RuleViolationException;
import com.codewalnut.bd.walnut.Mailer;
import com.codewalnut.bd.walnut.WalnutProperties;

/**
 * GTN profile pages (ADR-0019): each team member's page, the intro video in chunks, one link per person, and
 * what the page reports (visits, video progress, clicks). A visit from a quiet contact makes "ask now" their
 * next action and emails the owner.
 */
@Service
public class ProfileService {

    static final int CHUNK = 1024 * 1024;
    static final long MAX_VIDEO = 40L * 1024 * 1024;
    static final int MAX_EVENTS_PER_DAY = 300;
    static final Set<String> KINDS = Set.of("VISIT", "VIDEO_PLAY", "VIDEO_PROGRESS", "CTA_CLICK", "INTERESTED");
    static final Set<String> STEPS = Set.of("25", "50", "75", "100");
    static final Set<String> CTAS = Set.of("BOOK", "WHATSAPP");
    static final Set<String> ASKABLE = Set.of("INITIATE", "UNRESPONSIVE", "MONTHLY_BEEP");
    private static final Pattern URL = Pattern.compile("^https://[^\\s]{4,390}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    static final String DEFAULT_HEADLINE = "CodeWalnut: the AI-native engineering studio";
    static final String DEFAULT_INTRO = "We help product and GCC engineering teams ship faster without losing quality: experienced "
        + "engineers, AI across the whole software lifecycle, and rigorous review of every change. Release certainty, not hope.";
    static final List<String> DEFAULT_PROOF = List.of("Measurably better release quality within 3 to 6 months",
        "React, Java and AI enablement engineers who join your squads", "Teams that own a release outcome end to end");

    /** A team member's page; {@code id} 0 means they haven't saved one yet and the defaults are shown. */
    public record Page(long id, long ownerId, String ownerName, String headline, String intro, List<String> proofPoints,
                       String bookingUrl, String whatsapp, boolean interestedEnabled, String videoType, Long videoBytes,
                       Instant videoUpdatedAt, boolean hasCaptions, int version) {}

    public record Input(String headline, String intro, List<String> proofPoints, String bookingUrl, String whatsapp,
                        Boolean interestedEnabled, Integer version) {}

    /** What /p/{token} shows: the owner's page, greeting the person by first name. */
    public record View(long linkId, long connectionId, String firstName, Page page) {}

    public record Event(String kind, String value, Instant occurredAt) {}

    /** The page's link for one person and what it reported. */
    public record Stats(String url, long visits, Instant lastVisitAt, int bestVideoPct, long ctaClicks, Instant interestedAt,
                        List<Event> events) {}

    public record Video(String type, long bytes, long pageId) {}

    private final JdbcClient jdbc;
    private final GtnProperties gtn;
    private final Mailer mailer;
    private final WalnutProperties walnut;
    private final AuditLog audit;

    ProfileService(JdbcClient jdbc, GtnProperties gtn, Mailer mailer, WalnutProperties walnut, AuditLog audit) {
        this.jdbc = jdbc;
        this.gtn = gtn;
        this.mailer = mailer;
        this.walnut = walnut;
        this.audit = audit;
    }

    // ---- the team member's page ----

    public Page of(long ownerId) {
        String name = jdbc.sql("SELECT full_name FROM app_user WHERE id = :id").param("id", ownerId).query(String.class)
            .optional().orElseThrow(() -> new NotFoundException("User", ownerId));
        return jdbc.sql("""
                SELECT id, headline, intro, proof_points, booking_url, whatsapp, interested_enabled, video_type, video_bytes,
                    video_updated_at, captions IS NOT NULL, version
                FROM profile_page WHERE owner_user_id = :o
                """).param("o", ownerId)
            .query((rs, n) -> new Page(rs.getLong(1), ownerId, name, rs.getString(2), rs.getString(3), lines(rs.getString(4)),
                rs.getString(5), rs.getString(6), rs.getBoolean(7), rs.getString(8), rs.getObject(9, Long.class),
                rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).toInstant(), rs.getBoolean(11), rs.getInt(12)))
            .optional()
            .orElse(new Page(0, ownerId, name, DEFAULT_HEADLINE, DEFAULT_INTRO, DEFAULT_PROOF, null, null, true, null, null, null,
                false, 0));
    }

    @Transactional
    public Page save(Input in, CurrentUser user) {
        List<String> problems = new ArrayList<>();
        String headline = trim(in.headline());
        if (headline == null || headline.length() > 200) {
            problems.add("Write a headline of up to 200 characters.");
        }
        String intro = trim(in.intro());
        if (intro != null && intro.length() > 1500) {
            problems.add("Keep the intro under 1,500 characters: brief is compelling.");
        }
        List<String> proof = in.proofPoints() == null ? List.of()
            : in.proofPoints().stream().map(ProfileService::trim).filter(s -> s != null).toList();
        if (proof.size() > 3 || proof.stream().anyMatch(p -> p.length() > 300)) {
            problems.add("Give up to three proof points of up to 300 characters each.");
        }
        String booking = trim(in.bookingUrl());
        if (booking != null && !URL.matcher(booking).matches()) {
            problems.add("The booking link should be an https:// address, e.g. your Google Calendar booking page.");
        }
        String whatsapp = Phones.whatsapp(in.whatsapp());
        if (trim(in.whatsapp()) != null && whatsapp == null) {
            problems.add("The WhatsApp number should have 10 to 15 digits, with the country code.");
        }
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
        Page current = of(user.id());
        if (current.id() != 0 && in.version() != null && in.version() != current.version()) {
            throw new ConflictException("You changed your page in another tab. Reload and try again.");
        }
        Map<String, Object> p = new java.util.HashMap<>();
        p.put("o", user.id());
        p.put("h", headline);
        p.put("i", intro);
        p.put("p", proof.isEmpty() ? null : String.join("\n", proof));
        p.put("b", booking);
        p.put("w", whatsapp);
        p.put("x", in.interestedEnabled() == null || in.interestedEnabled());
        if (current.id() == 0) {
            jdbc.sql("""
                    INSERT INTO profile_page (owner_user_id, headline, intro, proof_points, booking_url, whatsapp, interested_enabled)
                    VALUES (:o, :h, :i, :p, :b, :w, :x)
                    """).params(p).update();
        } else {
            jdbc.sql("""
                    UPDATE profile_page SET headline = :h, intro = :i, proof_points = :p, booking_url = :b, whatsapp = :w,
                        interested_enabled = :x, version = version + 1
                    WHERE owner_user_id = :o
                    """).params(p).update();
        }
        Page saved = of(user.id());
        audit.record("profile_page", saved.id(), current.id() == 0 ? AuditLog.Action.CREATE : AuditLog.Action.UPDATE, Map.of(), user.id());
        return saved;
    }

    /** Replaces the intro video with the uploaded MP4 or WebM (up to 40 MB), stored in 1 MB chunks. */
    @Transactional
    public Page uploadVideo(String contentType, InputStream in, CurrentUser user) throws IOException {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).split(";")[0].trim();
        if (!type.equals("video/mp4") && !type.equals("video/webm")) {
            throw new RuleViolationException(List.of("Upload an MP4 or WebM video (most phones and screen recorders save MP4)."));
        }
        long pageId = ensurePage(user);
        jdbc.sql("DELETE FROM profile_video_chunk WHERE page_id = :p").param("p", pageId).update();
        long total = 0;
        int seq = 0;
        while (true) {
            byte[] chunk = in.readNBytes(CHUNK);
            if (chunk.length == 0) {
                break;
            }
            if (seq == 0 && !looksLike(type, chunk)) {
                throw new RuleViolationException(List.of("That file doesn't look like an " + (type.endsWith("mp4") ? "MP4" : "WebM")
                    + " video."));
            }
            total += chunk.length;
            if (total > MAX_VIDEO) {
                throw new RuleViolationException(List.of("Keep the video under 40 MB: a 1–2 minute intro at 720p is plenty."));
            }
            jdbc.sql("INSERT INTO profile_video_chunk (page_id, seq, data) VALUES (:p, :s, :d)")
                .param("p", pageId).param("s", seq++).param("d", chunk).update();
        }
        if (total == 0) {
            throw new RuleViolationException(List.of("The video is empty."));
        }
        jdbc.sql("""
                UPDATE profile_page SET video_type = :t, video_bytes = :b, video_updated_at = CURRENT_TIMESTAMP(3), version = version + 1
                WHERE id = :p
                """).param("t", type).param("b", total).param("p", pageId).update();
        audit.record("profile_page", pageId, AuditLog.Action.UPDATE, Map.of("video", total), user.id());
        return of(user.id());
    }

    @Transactional
    public Page deleteVideo(CurrentUser user) {
        Page page = of(user.id());
        if (page.id() != 0) {
            jdbc.sql("DELETE FROM profile_video_chunk WHERE page_id = :p").param("p", page.id()).update();
            jdbc.sql("""
                    UPDATE profile_page SET video_type = NULL, video_bytes = NULL, video_updated_at = NULL, captions = NULL,
                        version = version + 1 WHERE id = :p
                    """).param("p", page.id()).update();
            audit.record("profile_page", page.id(), AuditLog.Action.UPDATE, Map.of("video", "removed"), user.id());
        }
        return of(user.id());
    }

    /** WebVTT captions for the video (FR-8: captioned), or empty to remove them. */
    @Transactional
    public Page captions(String vtt, CurrentUser user) {
        String text = vtt == null ? "" : vtt.replace("\r\n", "\n").strip();
        if (!text.isEmpty() && (!text.startsWith("WEBVTT") || text.length() > 200_000)) {
            throw new RuleViolationException(List.of("Captions must be a WebVTT (.vtt) file of up to 200 KB."));
        }
        long pageId = ensurePage(user);
        jdbc.sql("UPDATE profile_page SET captions = :c, version = version + 1 WHERE id = :p")
            .param("c", text.isEmpty() ? null : text).param("p", pageId).update();
        return of(user.id());
    }

    public Optional<String> captionsOf(long pageId) {
        return jdbc.sql("SELECT captions FROM profile_page WHERE id = :p AND captions IS NOT NULL").param("p", pageId)
            .query(String.class).optional();
    }

    public Optional<Video> video(long ownerId) {
        return jdbc.sql("SELECT video_type, video_bytes, id FROM profile_page WHERE owner_user_id = :o AND video_bytes IS NOT NULL")
            .param("o", ownerId).query((rs, n) -> new Video(rs.getString(1), rs.getLong(2), rs.getLong(3))).optional();
    }

    /** Writes bytes {@code from}..{@code to} (inclusive) of the video, one chunk at a time. */
    public void writeVideo(long pageId, long from, long to, OutputStream out) throws IOException {
        int first = (int) (from / CHUNK);
        int last = (int) (to / CHUNK);
        for (int seq = first; seq <= last; seq++) {
            byte[] data = jdbc.sql("SELECT data FROM profile_video_chunk WHERE page_id = :p AND seq = :s")
                .param("p", pageId).param("s", seq).query(byte[].class).optional().orElse(null);
            if (data == null) {
                return;
            }
            long chunkStart = (long) seq * CHUNK;
            int start = (int) Math.max(0, from - chunkStart);
            int end = (int) Math.min(data.length - 1, to - chunkStart);
            if (end >= start) {
                out.write(data, start, end - start + 1);
            }
        }
        out.flush();
    }

    // ---- links ----

    /** The person's link token, made the first time it is needed (ADR-0019). */
    @Transactional
    public String token(long connectionId, long ownerId) {
        Optional<String> existing = jdbc.sql("SELECT token FROM profile_link WHERE connection_id = :c").param("c", connectionId)
            .query(String.class).optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        byte[] random = new byte[16];
        RANDOM.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        jdbc.sql("INSERT INTO profile_link (connection_id, owner_user_id, token) VALUES (:c, :o, :t)")
            .param("c", connectionId).param("o", ownerId).param("t", token).update();
        return token;
    }

    /** The page for a link: the GTN owner's page if the person is on GTN, otherwise whoever made the link. */
    public Optional<View> view(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{22}")) {
            return Optional.empty();
        }
        record Link(long id, long connectionId, String name, long owner) {}
        return jdbc.sql("""
                SELECT l.id, l.connection_id, n.full_name, COALESCE(t.owner_user_id, l.owner_user_id)
                FROM profile_link l JOIN connection n ON n.id = l.connection_id
                LEFT JOIN network_track t ON t.connection_id = l.connection_id
                WHERE l.token = :t
                """).param("t", token)
            .query((rs, n) -> new Link(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4))).optional()
            .map(l -> new View(l.id(), l.connectionId(), Suggestions.firstName(l.name()), of(l.owner())));
    }

    public Stats stats(long connectionId, String baseUrl) {
        Optional<Long> link = jdbc.sql("SELECT id FROM profile_link WHERE connection_id = :c").param("c", connectionId)
            .query(Long.class).optional();
        String token = jdbc.sql("SELECT token FROM profile_link WHERE connection_id = :c").param("c", connectionId)
            .query(String.class).optional().orElse(null);
        if (link.isEmpty()) {
            return new Stats(null, 0, null, 0, 0, null, List.of());
        }
        List<Event> events = jdbc.sql("""
                SELECT kind, value, occurred_at FROM profile_event WHERE link_id = :l ORDER BY occurred_at DESC, id DESC LIMIT 30
                """).param("l", link.get())
            .query((rs, n) -> new Event(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant())).list();
        Object[] agg = jdbc.sql("""
                SELECT COALESCE(SUM(kind = 'VISIT'), 0), MAX(CASE WHEN kind = 'VISIT' THEN occurred_at END),
                    COALESCE(MAX(CASE WHEN kind = 'VIDEO_PROGRESS' THEN CAST(value AS UNSIGNED) END), 0),
                    COALESCE(SUM(kind = 'CTA_CLICK'), 0), MAX(CASE WHEN kind = 'INTERESTED' THEN occurred_at END)
                FROM profile_event WHERE link_id = :l
                """).param("l", link.get())
            .query((rs, n) -> new Object[] {rs.getLong(1), rs.getTimestamp(2), rs.getInt(3), rs.getLong(4), rs.getTimestamp(5)})
            .single();
        return new Stats(baseUrl + "/p/" + token, (Long) agg[0],
            agg[1] == null ? null : ((java.sql.Timestamp) agg[1]).toInstant(), (Integer) agg[2], (Long) agg[3],
            agg[4] == null ? null : ((java.sql.Timestamp) agg[4]).toInstant(), events);
    }

    // ---- what the page reports ----

    /**
     * Records an event from the page and acts on it: a timeline touch (once per day per kind), and for someone at
     * Initiate, Unresponsive or Monthly beep, "ask now" as their next action, with an email to the owner. Returns
     * false when the event was ignored (unknown link, bad value, or over the daily limit).
     */
    @Transactional
    public boolean record(String token, String kind, String value) {
        Optional<View> view = view(token);
        String k = kind == null ? null : kind.trim().toUpperCase(Locale.ROOT);
        if (view.isEmpty() || k == null || !KINDS.contains(k)) {
            return false;
        }
        String v = value == null ? null : value.trim().toUpperCase(Locale.ROOT);
        if (("VIDEO_PROGRESS".equals(k) && (v == null || !STEPS.contains(v))) || ("CTA_CLICK".equals(k) && (v == null || !CTAS.contains(v)))) {
            return false;
        }
        if (!"VIDEO_PROGRESS".equals(k) && !"CTA_CLICK".equals(k)) {
            v = null;
        }
        long linkId = view.get().linkId();
        Instant dayStart = today().atStartOfDay(zone()).toInstant();
        int today = jdbc.sql("SELECT COUNT(*) FROM profile_event WHERE link_id = :l AND occurred_at >= :d")
            .param("l", linkId).param("d", java.sql.Timestamp.from(dayStart)).query(Integer.class).single();
        if (today >= MAX_EVENTS_PER_DAY) {
            return false;
        }
        jdbc.sql("INSERT INTO profile_event (link_id, kind, value) VALUES (:l, :k, :v)")
            .param("l", linkId).param("k", k).param("v", v).update();
        act(view.get(), k, v, dayStart);
        return true;
    }

    private void act(View view, String kind, String value, Instant dayStart) {
        record T(long id, long owner, String stage, String why, boolean askNow, Instant visitedAt, int best) {}
        T t = jdbc.sql("""
                SELECT id, owner_user_id, stage, why_reason, ask_now, visited_at, best_video_pct
                FROM network_track WHERE connection_id = :c FOR UPDATE
                """).param("c", view.connectionId())
            .query((rs, n) -> new T(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getBoolean(5),
                rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(), rs.getInt(7)))
            .optional().orElse(null);
        if (t == null) {
            return;
        }
        String touch = null;
        String ask = null;
        switch (kind) {
            case "VISIT" -> {
                if (t.visitedAt() == null || t.visitedAt().isBefore(dayStart)) {
                    touch = "Opened your profile page";
                }
                jdbc.sql("UPDATE network_track SET visited_at = CURRENT_TIMESTAMP(3) WHERE id = :id").param("id", t.id()).update();
                ask = "they opened your page";
            }
            case "VIDEO_PROGRESS" -> {
                int pct = Integer.parseInt(value);
                if (pct > t.best()) {
                    jdbc.sql("UPDATE network_track SET best_video_pct = :p WHERE id = :id").param("p", pct).param("id", t.id()).update();
                    if (pct >= 75 && t.best() < 75) {
                        touch = pct == 100 ? "Watched all of your video" : "Watched 75% of your video";
                    } else if (pct == 100 && t.best() < 100) {
                        touch = "Watched all of your video";
                    }
                }
                ask = "they watched " + pct + "% of your video";
            }
            case "CTA_CLICK" -> {
                String what = "BOOK".equals(value) ? "Book a call" : "Chat on WhatsApp";
                touch = "Clicked \"" + what + "\" on your page";
                ask = "they clicked \"" + what + "\"";
            }
            case "INTERESTED" -> {
                touch = "Clicked \"Interested, tell me more\"";
                jdbc.sql("UPDATE network_track SET interested_at = CURRENT_TIMESTAMP(3) WHERE id = :id").param("id", t.id()).update();
                ask = "they clicked \"Interested\"";
            }
            default -> {
                // A play alone says little; progress follows.
            }
        }
        if (touch != null) {
            jdbc.sql("""
                    INSERT INTO network_touch (track_id, user_id, channel, direction, kind, summary, occurred_on)
                    VALUES (:t, :u, 'OTHER', 'IN', 'VISIT', :s, :on)
                    """).param("t", t.id()).param("u", t.owner()).param("s", touch).param("on", today()).update();
        }
        boolean newAsk = ask != null && !t.askNow() && ASKABLE.contains(t.stage());
        if (newAsk) {
            jdbc.sql("""
                    UPDATE network_track SET ask_now = TRUE, next_action = :what, next_action_on = :today,
                        why_reason = CASE WHEN stage = 'UNRESPONSIVE' AND why_reason IS NULL THEN 'INERTIA' ELSE why_reason END,
                        version = version + 1
                    WHERE id = :id
                    """).param("what", "Ask now: " + ask).param("today", today()).param("id", t.id()).update();
        }
        if (newAsk || "INTERESTED".equals(kind)) {
            email(t.owner(), t.id(), view, "INTERESTED".equals(kind)
                ? name(view) + " clicked \"Interested, tell me more\" on your page"
                : name(view) + " " + ask.replaceFirst("^they ", ""));
        }
    }

    private void email(long ownerId, long trackId, View view, String subject) {
        if (!mailer.enabled()) {
            return;
        }
        String to = jdbc.sql("SELECT email FROM app_user WHERE id = :id AND active").param("id", ownerId).query(String.class)
            .optional().orElse(null);
        if (to == null) {
            return;
        }
        String link = walnut.baseUrl() + "/network/" + trackId;
        String html = "<p>" + escape(subject) + ".</p><p>This is the moment for the direct ask. Your message is ready in GTN: "
            + "<a href=\"" + escape(link) + "\">" + escape(link) + "</a></p><p style=\"color:#666\">Sent by the CodeWalnut BD platform.</p>";
        try {
            mailer.send(to, subject, html);
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(ProfileService.class).warn("GTN alert email to user {} not sent: {}", ownerId, e.getMessage());
        }
    }

    private String name(View view) {
        return jdbc.sql("SELECT full_name FROM connection WHERE id = :c").param("c", view.connectionId()).query(String.class).single();
    }

    // ---- helpers ----

    private long ensurePage(CurrentUser user) {
        Page page = of(user.id());
        if (page.id() != 0) {
            return page.id();
        }
        jdbc.sql("""
                INSERT INTO profile_page (owner_user_id, headline, intro, proof_points) VALUES (:o, :h, :i, :p)
                """).param("o", user.id()).param("h", DEFAULT_HEADLINE).param("i", DEFAULT_INTRO)
            .param("p", String.join("\n", DEFAULT_PROOF)).update();
        return of(user.id()).id();
    }

    private static boolean looksLike(String type, byte[] head) {
        if (type.endsWith("webm")) {
            return head.length >= 4 && (head[0] & 0xFF) == 0x1A && (head[1] & 0xFF) == 0x45 && (head[2] & 0xFF) == 0xDF
                && (head[3] & 0xFF) == 0xA3;
        }
        return head.length >= 8 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p';
    }

    private ZoneId zone() {
        return ZoneId.of(gtn.zone());
    }

    private LocalDate today() {
        return LocalDate.now(zone());
    }

    private static List<String> lines(String s) {
        return s == null || s.isBlank() ? List.of() : Arrays.stream(s.split("\n")).map(String::strip).filter(x -> !x.isEmpty()).toList();
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
            .replace("'", "&#39;");
    }
}
