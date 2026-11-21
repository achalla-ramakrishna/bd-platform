package com.codewalnut.bd.network;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.codewalnut.bd.google.GoogleAccounts;
import com.codewalnut.bd.google.GoogleApi;
import com.codewalnut.bd.shared.RuleViolationException;

/**
 * Booking a call on the profile page (ADR-0020): the owner's free slots from their Google Calendar, and the meeting
 * created on their calendar with a Google Meet link, inviting the person. The track moves to Call scheduled.
 */
@Service
public class BookingService {

    static final int WORKING_DAYS = 10;
    static final Duration NOTICE = Duration.ofHours(2);
    static final int MAX_SLOTS = 80;
    static final int MAX_BOOKINGS_PER_DAY = 3;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH);

    /** {@code available}: the page can book on the calendar now; {@code needEmail}: ask the person for their address. */
    public record Slots(boolean available, int minutes, String zone, List<Instant> slots, boolean needEmail) {}

    public record Booked(Instant start, String when, String meetLink) {}

    private final JdbcClient jdbc;
    private final ProfileService profiles;
    private final GoogleAccounts accounts;
    private final GoogleApi google;
    private final NetworkService network;
    private final GtnProperties gtn;

    BookingService(JdbcClient jdbc, ProfileService profiles, GoogleAccounts accounts, GoogleApi google, NetworkService network,
                   GtnProperties gtn) {
        this.jdbc = jdbc;
        this.profiles = profiles;
        this.accounts = accounts;
        this.google = google;
        this.network = network;
        this.gtn = gtn;
    }

    /** True when this page books on the owner's calendar (else the page keeps their booking link). */
    public boolean calendarBooking(ProfileService.Page page) {
        return "CALENDAR".equals(page.bookingMode()) && accounts.status(page.ownerId()).canCalendar();
    }

    public Slots slots(String token) {
        ProfileService.View view = profiles.view(token).orElse(null);
        if (view == null || !calendarBooking(view.page())) {
            return new Slots(false, 0, gtn.zone(), List.of(), false);
        }
        String access = accounts.accessToken(view.page().ownerId()).orElse(null);
        if (access == null) {
            return new Slots(false, 0, gtn.zone(), List.of(), false);
        }
        return new Slots(true, view.page().slotMinutes(), gtn.zone(), free(view.page(), access), email(view.connectionId()) == null);
    }

    @Transactional
    public Booked book(String token, String start, String givenEmail) {
        ProfileService.View view = profiles.view(token).orElseThrow(() -> new RuleViolationException(List.of("This link is no longer active.")));
        ProfileService.Page page = view.page();
        if (!calendarBooking(page)) {
            throw new RuleViolationException(List.of("Booking isn't open on this page."));
        }
        String access = accounts.accessToken(page.ownerId())
            .orElseThrow(() -> new RuleViolationException(List.of("Booking isn't available right now. Please use WhatsApp or try later.")));
        Instant at;
        try {
            at = Instant.parse(start);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new RuleViolationException(List.of("Choose a time."));
        }
        String email = email(view.connectionId());
        if (email == null) {
            String typed = givenEmail == null ? "" : givenEmail.trim().toLowerCase(Locale.ROOT);
            if (!EMAIL.matcher(typed).matches() || typed.length() > 254) {
                throw new RuleViolationException(List.of("Give your email address for the calendar invitation."));
            }
            email = typed;
            jdbc.sql("UPDATE connection SET email = COALESCE(email, :e) WHERE id = :c").param("e", email).param("c", view.connectionId()).update();
        }
        Instant dayStart = network.today().atStartOfDay(zone()).toInstant();
        int today = jdbc.sql("SELECT COUNT(*) FROM profile_event WHERE link_id = :l AND kind = 'BOOKED' AND occurred_at >= :d")
            .param("l", view.linkId()).param("d", java.sql.Timestamp.from(dayStart)).query(Integer.class).single();
        if (today >= MAX_BOOKINGS_PER_DAY) {
            throw new RuleViolationException(List.of("That's a few bookings today already. Please message us instead."));
        }
        Optional<Long> trackId = jdbc.sql("SELECT id FROM network_track WHERE connection_id = :c").param("c", view.connectionId())
            .query(Long.class).optional();
        Optional<Instant> upcoming = trackId.flatMap(id -> jdbc.sql("""
                SELECT call_at FROM network_track WHERE id = :id AND calendar_event_id IS NOT NULL AND call_at > CURRENT_TIMESTAMP(3)
                """).param("id", id).query(java.sql.Timestamp.class).optional().map(java.sql.Timestamp::toInstant));
        if (upcoming.isPresent()) {
            throw new RuleViolationException(List.of("You already have a call booked for " + when(upcoming.get())
                + ". To change it, use the link in the calendar invitation."));
        }
        if (!free(page, access).contains(at)) {
            throw new RuleViolationException(List.of("That time was just taken. Please choose another."));
        }
        String person = profiles.personName(view);
        String ownerFirst = Suggestions.firstName(page.ownerName());
        GoogleApi.Event event;
        try {
            event = google.createEvent(access, ownerFirst + " (CodeWalnut) and " + Suggestions.firstName(person) + ": "
                    + page.slotMinutes() + "-minute call",
                "Booked by " + person + " from " + ownerFirst + "'s CodeWalnut page.", at, at.plus(Duration.ofMinutes(page.slotMinutes())),
                email, gtn.zone());
        } catch (GoogleApi.GoogleException e) {
            throw new RuleViolationException(List.of("The booking didn't go through. Please try again or message us on WhatsApp."));
        }
        profiles.recordBooked(view.linkId());
        String when = when(at);
        if (trackId.isPresent()) {
            network.booked(trackId.get(), at, event.id(), "Booked a call on your page for " + when);
            profiles.alert(page.ownerId(), trackId.get(), view, person + " booked a call for " + when);
        }
        return new Booked(at, when, event.meetLink());
    }

    /** The owner's free slots: working days, their hours, after two hours' notice, minus what's busy. */
    List<Instant> free(ProfileService.Page page, String access) {
        ZoneId zone = zone();
        Instant earliest = Instant.now().plus(NOTICE);
        LocalTime from = LocalTime.parse(page.dayStart());
        LocalTime to = LocalTime.parse(page.dayEnd());
        Duration slot = Duration.ofMinutes(page.slotMinutes());
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = network.today(); days.size() < WORKING_DAYS; d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days.add(d);
            }
        }
        Instant rangeStart = days.get(0).atTime(from).atZone(zone).toInstant();
        Instant rangeEnd = days.get(days.size() - 1).atTime(to).atZone(zone).toInstant();
        List<GoogleApi.Busy> busy;
        try {
            busy = google.freeBusy(access, rangeStart.isBefore(earliest) ? earliest : rangeStart, rangeEnd);
        } catch (GoogleApi.GoogleException e) {
            return List.of();
        }
        List<Instant> out = new ArrayList<>();
        for (LocalDate d : days) {
            for (ZonedDateTime t = d.atTime(from).atZone(zone); !t.toLocalTime().plus(slot).isAfter(to) && t.toLocalDate().equals(d);
                    t = t.plus(slot)) {
                Instant s = t.toInstant();
                Instant e = s.plus(slot);
                if (s.isBefore(earliest)) {
                    continue;
                }
                boolean clash = busy.stream().anyMatch(b -> s.isBefore(b.end()) && e.isAfter(b.start()));
                if (!clash) {
                    out.add(s);
                    if (out.size() >= MAX_SLOTS) {
                        return out;
                    }
                }
            }
        }
        return out;
    }

    private String email(long connectionId) {
        return jdbc.sql("SELECT email FROM connection WHERE id = :c").param("c", connectionId).query(String.class).optional()
            .filter(e -> !e.isBlank()).orElse(null);
    }

    private String when(Instant at) {
        return at.atZone(zone()).format(WHEN) + " (" + zone().getId() + ")";
    }

    private ZoneId zone() {
        return ZoneId.of(gtn.zone());
    }
}
