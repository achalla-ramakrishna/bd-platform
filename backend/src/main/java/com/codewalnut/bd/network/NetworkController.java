package com.codewalnut.bd.network;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.codewalnut.bd.admin.CurrentUserProvider;
import com.codewalnut.bd.reporting.ConnectionQueries;
import com.codewalnut.bd.reporting.NetworkQueries;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.NotFoundException;
import com.codewalnut.bd.shared.Role;
import com.codewalnut.bd.shared.RuleViolationException;

/** GTN, Go To Network (ADR-0017, ADR-0018). Everyone signed in reads; pipeline roles act. */
@RestController
@RequestMapping("/api/v1/network")
class NetworkController {

    /** The thresholds, so screens can explain them. */
    record Rules(int unresponsiveDays, int nudgeSpacingDays, int nudgeCap, int beepIntervalDays) {}

    record Today(List<Suggestions.Next> items, NetworkQueries.Counts counts, Rules rules) {}

    record Board(List<NetworkQueries.Track> tracks, NetworkQueries.Counts counts, Rules rules) {}

    record TrackPage(NetworkQueries.Detail detail, Suggestions.Next next, Rules rules) {}

    /** Fill a template for a track, or for someone in Connections before they are on GTN. */
    record RenderRequest(Long trackId, Long connectionId, Long templateId, String channel) {}

    private final NetworkService network;
    private final NetworkQueries queries;
    private final Suggestions suggestions;
    private final TemplateService templates;
    private final ConnectionQueries connections;
    private final GtnProperties gtn;
    private final CurrentUserProvider currentUser;

    NetworkController(NetworkService network, NetworkQueries queries, Suggestions suggestions, TemplateService templates,
                      ConnectionQueries connections, GtnProperties gtn, CurrentUserProvider currentUser) {
        this.network = network;
        this.queries = queries;
        this.suggestions = suggestions;
        this.templates = templates;
        this.connections = connections;
        this.gtn = gtn;
        this.currentUser = currentUser;
    }

    /** Who to contact today, why, and the message (GTN FR-9.1). Runs the sweep first so nothing is stale. */
    @GetMapping("/today")
    Today today(@RequestParam(required = false) Boolean everyone) {
        CurrentUser user = currentUser.get();
        LocalDate today = network.today();
        network.sweep(today);
        Long owner = Boolean.TRUE.equals(everyone) ? null : user.id();
        return new Today(suggestions.today(queries.due(owner, today), today, user), queries.counts(owner, today), rules());
    }

    @GetMapping("/tracks")
    Board tracks(@RequestParam(required = false) String stage, @RequestParam(required = false) Boolean mine,
                 @RequestParam(required = false) Long ownerId, @RequestParam(required = false) String source,
                 @RequestParam(required = false) String reason, @RequestParam(required = false) String q,
                 @RequestParam(required = false) Boolean overdue) {
        CurrentUser user = currentUser.get();
        LocalDate today = network.today();
        Long owner = Boolean.TRUE.equals(mine) ? Long.valueOf(user.id()) : ownerId;
        return new Board(queries.list(stage, owner, source, reason, q, Boolean.TRUE.equals(overdue), today),
            queries.counts(owner, today), rules());
    }

    @GetMapping("/tracks/{id}")
    TrackPage track(@PathVariable long id) {
        return page(id, currentUser.get());
    }

    /** The track of someone in Connections, if they are on GTN (404 otherwise). */
    @GetMapping("/tracks/for-connection/{connectionId}")
    TrackPage forConnection(@PathVariable long connectionId) {
        CurrentUser user = currentUser.get();
        long id = queries.forConnection(connectionId).orElseThrow(() -> new NotFoundException("Network track", connectionId));
        return page(id, user);
    }

    @PostMapping("/tracks")
    @ResponseStatus(HttpStatus.CREATED)
    TrackPage start(@RequestBody NetworkService.Start start) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        return page(network.start(start, user), user);
    }

    @PostMapping("/tracks/{id}/actions")
    TrackPage act(@PathVariable long id, @RequestBody NetworkService.Action action) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        network.act(id, action, user);
        return page(id, user);
    }

    @PatchMapping("/tracks/{id}")
    TrackPage change(@PathVariable long id, @RequestBody NetworkService.Change change) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        network.change(id, change, user);
        return page(id, user);
    }

    @DeleteMapping("/tracks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void stop(@PathVariable long id) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        network.stop(id, user);
    }

    @GetMapping("/templates")
    List<Suggestions.Template> templates(@RequestParam(required = false) Boolean all) {
        currentUser.get();
        return suggestions.templates(!Boolean.TRUE.equals(all));
    }

    @PostMapping("/templates")
    @ResponseStatus(HttpStatus.CREATED)
    Suggestions.Template createTemplate(@RequestBody TemplateService.Input in) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        long id = templates.create(in, user);
        return template(id);
    }

    @PutMapping("/templates/{id}")
    Suggestions.Template updateTemplate(@PathVariable long id, @RequestBody TemplateService.Input in) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        templates.update(id, in, user);
        return template(id);
    }

    @DeleteMapping("/templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteTemplate(@PathVariable long id) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        templates.delete(id, user);
    }

    @PostMapping("/render")
    Suggestions.Suggestion render(@RequestBody RenderRequest in) {
        CurrentUser user = currentUser.get();
        Suggestions.Template tpl = in.templateId() == null ? null
            : suggestions.templates(false).stream().filter(t -> t.id() == in.templateId()).findFirst().orElse(null);
        if (tpl == null) {
            throw new RuleViolationException(List.of("Choose a message template."));
        }
        NetworkQueries.Track track;
        if (in.trackId() != null) {
            track = queries.get(in.trackId(), network.today());
        } else if (in.connectionId() != null) {
            track = asTrack(connections.get(in.connectionId()), user);
        } else {
            throw new RuleViolationException(List.of("Say who the message is for."));
        }
        String channel = in.channel() == null || in.channel().isBlank() ? Suggestions.channel(track, "CHECK")
            : in.channel().trim().toUpperCase(Locale.ROOT);
        return suggestions.render(tpl, channel, track, user);
    }

    private TrackPage page(long id, CurrentUser user) {
        LocalDate today = network.today();
        NetworkQueries.Detail detail = queries.detail(id, today);
        return new TrackPage(detail, suggestions.next(detail.track(), today, user), rules());
    }

    private Suggestions.Template template(long id) {
        return suggestions.templates(false).stream().filter(t -> t.id() == id).findFirst()
            .orElseThrow(() -> new NotFoundException("Message template", id));
    }

    private Rules rules() {
        return new Rules(gtn.unresponsiveDays(), gtn.nudgeSpacingDays(), gtn.nudgeCap(), gtn.beepIntervalDays());
    }

    /** Someone not yet on GTN, shaped as a track at Initiate so templates can be filled for the first message. */
    private static NetworkQueries.Track asTrack(ConnectionQueries.Connection c, CurrentUser user) {
        String how = c.knownBy().stream().filter(k -> k.userId() == user.id()).map(ConnectionQueries.Knower::howKnown)
            .filter(h -> h != null && !h.isBlank()).findFirst()
            .orElse(c.knownBy().stream().map(ConnectionQueries.Knower::howKnown).filter(h -> h != null && !h.isBlank())
                .findFirst().orElse(null));
        return new NetworkQueries.Track(0, c.id(), c.fullName(), c.currentCompany(), c.currentRole(), c.linkedinUrl(),
            c.email(), c.whatsapp(), c.sourceTag(), c.photoVersion(), user.id(), user.name(), "INITIATE",
            java.time.Instant.now(), 0, null, "", LocalDate.now(), false, null, null, null, 0, null, 30, null, how, null, 0);
    }
}
