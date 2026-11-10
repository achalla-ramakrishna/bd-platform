package com.codewalnut.bd.engagement;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.codewalnut.bd.admin.CurrentUserProvider;
import com.codewalnut.bd.reporting.EventQueries;
import com.codewalnut.bd.shared.AuditLog;
import com.codewalnut.bd.shared.ConflictException;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.NotFoundException;
import com.codewalnut.bd.shared.Role;
import com.codewalnut.bd.shared.RuleViolationException;

/**
 * Conferences and events (backlog item 6): attend, present, organise, and record who we met. Everyone
 * reads; pipeline roles add and change events. Anyone may say they are going.
 */
@RestController
@RequestMapping("/api/v1/events")
class EventController {

    static final Set<String> KINDS = Set.of("CONFERENCE", "MEETUP", "WEBINAR", "ROUNDTABLE", "OTHER");
    static final Set<String> ROLES = Set.of("WATCHING", "SIGNED_UP", "ATTENDING", "PRESENTING", "STALL", "ORGANISING");

    record Input(String name, String kind, LocalDate startsOn, LocalDate endsOn, String city, Boolean online, String url,
                 String organiser, String ourRole, String notes, String outcome, Integer version) {}

    private final JdbcClient jdbc;
    private final EventQueries queries;
    private final CurrentUserProvider currentUser;
    private final AuditLog audit;

    EventController(JdbcClient jdbc, EventQueries queries, CurrentUserProvider currentUser, AuditLog audit) {
        this.jdbc = jdbc;
        this.queries = queries;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @GetMapping
    EventQueries.Page list(@RequestParam(required = false) String when, @RequestParam(required = false) String role,
                           @RequestParam(required = false) String kind, @RequestParam(required = false) Boolean mine) {
        return queries.list(when, role, kind, mine, currentUser.get().id());
    }

    @GetMapping("/{id}")
    EventQueries.Detail get(@PathVariable long id) {
        currentUser.get();
        return queries.detail(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    EventQueries.Detail add(@RequestBody Input in) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        check(in);
        var key = new GeneratedKeyHolder();
        bind(jdbc.sql("""
                INSERT INTO bd_event (name, kind, starts_on, ends_on, city, online, url, organiser, our_role, notes, outcome, created_by)
                VALUES (:name, :kind, :starts, :ends, :city, :online, :url, :organiser, :role, :notes, :outcome, :user)
                """), in).param("user", user.id()).update(key);
        long id = key.getKey().longValue();
        audit.record("bd_event", id, AuditLog.Action.CREATE, Map.of("role", upper(in.ourRole())), user.id());
        return queries.detail(id);
    }

    @PutMapping("/{id}")
    @Transactional
    EventQueries.Detail update(@PathVariable long id, @RequestBody Input in) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        Integer stored = jdbc.sql("SELECT version FROM bd_event WHERE id = :id FOR UPDATE").param("id", id)
            .query(Integer.class).optional().orElseThrow(() -> new NotFoundException("Event", id));
        if (in.version() != null && !in.version().equals(stored)) {
            throw new ConflictException("Someone else changed this event. Reload and try again.");
        }
        check(in);
        bind(jdbc.sql("""
                UPDATE bd_event SET name = :name, kind = :kind, starts_on = :starts, ends_on = :ends, city = :city,
                    online = :online, url = :url, organiser = :organiser, our_role = :role, notes = :notes,
                    outcome = :outcome, version = version + 1
                WHERE id = :id
                """), in).param("id", id).update();
        audit.record("bd_event", id, AuditLog.Action.UPDATE, Map.of("role", upper(in.ourRole())), user.id());
        return queries.detail(id);
    }

    /** I am going. */
    @PutMapping("/{id}/attendees/me")
    EventQueries.Detail attend(@PathVariable long id) {
        CurrentUser user = currentUser.get();
        queries.get(id);
        jdbc.sql("INSERT IGNORE INTO bd_event_attendee (event_id, user_id) VALUES (:e, :u)")
            .param("e", id).param("u", user.id()).update();
        return queries.detail(id);
    }

    /** I am not going any more. */
    @DeleteMapping("/{id}/attendees/me")
    EventQueries.Detail skip(@PathVariable long id) {
        CurrentUser user = currentUser.get();
        queries.get(id);
        jdbc.sql("DELETE FROM bd_event_attendee WHERE event_id = :e AND user_id = :u")
            .param("e", id).param("u", user.id()).update();
        return queries.detail(id);
    }

    private static void check(Input in) {
        List<String> problems = new ArrayList<>();
        if (in == null || in.name() == null || in.name().isBlank()) {
            problems.add("Give the event a name.");
        } else if (in.name().trim().length() > 200) {
            problems.add("The name can be at most 200 characters.");
        }
        if (in == null) {
            throw new RuleViolationException(problems);
        }
        if (!KINDS.contains(String.valueOf(upper(in.kind())))) {
            problems.add("Choose the kind of event.");
        }
        if (!ROLES.contains(String.valueOf(upper(in.ourRole())))) {
            problems.add("Choose our role at the event.");
        }
        if (in.startsOn() == null) {
            problems.add("Give the date it starts.");
        } else if (in.endsOn() != null && in.endsOn().isBefore(in.startsOn())) {
            problems.add("It cannot end before it starts.");
        }
        if (in.url() != null && !in.url().isBlank() && (!in.url().trim().matches("(?i)^https?://\\S+$") || in.url().length() > 500)) {
            problems.add("The link should start with http:// or https://");
        }
        if ((in.city() != null && in.city().trim().length() > 80) || (in.organiser() != null && in.organiser().trim().length() > 200)) {
            problems.add("City can be at most 80 characters and organiser at most 200.");
        }
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Input in) {
        return spec.param("name", in.name().trim()).param("kind", upper(in.kind())).param("starts", in.startsOn())
            .param("ends", in.endsOn()).param("city", trim(in.city())).param("online", Boolean.TRUE.equals(in.online()))
            .param("url", trim(in.url())).param("organiser", trim(in.organiser())).param("role", upper(in.ourRole()))
            .param("notes", trim(in.notes())).param("outcome", trim(in.outcome()));
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String upper(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase(Locale.ROOT);
    }
}
