package com.codewalnut.bd.reporting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.codewalnut.bd.shared.NotFoundException;

/**
 * What the research agent knows about a person from our own records (ADR-0022): who they are, their account and its
 * latest research, our touches with them, who in the team knows them, and how good our access is.
 */
@Service
public class ProspectContextQueries {

    public static final List<String> TYPES = List.of("CONTACT", "CONNECTION");

    /** {@code optedOut}: someone who asked not to be contacted (ADR-0024); never researched or approached. */
    public record Person(String type, long id, String name, String title, String company, String city, String linkedinUrl,
                         Long accountId, String accountName, String relationship, boolean optedOut) {}

    /**
     * {@code access} 1–5 with its reason, worked out from our records; {@code accountResearch}: the latest account
     * research summary as JSON, or null.
     */
    public record Context(Person person, List<String> history, List<String> knownBy, int access, String accessReason,
                          String accountResearch, ResearchContextQueries.Context account) {}

    private final JdbcClient jdbc;
    private final ResearchContextQueries accounts;

    ProspectContextQueries(JdbcClient jdbc, ResearchContextQueries accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    public Person person(String type, long id) {
        String t = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        Optional<Person> p = switch (t) {
            case "CONTACT" -> jdbc.sql("""
                    SELECT c.id, c.full_name, c.title, a.name, c.city, c.linkedin_url, a.id, a.name, a.relationship, c.opted_out
                    FROM contact c JOIN account a ON a.id = c.account_id WHERE c.id = :id
                    """).param("id", id).query((rs, n) -> new Person("CONTACT", rs.getLong(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getString(5), rs.getString(6), rs.getLong(7), rs.getString(8), rs.getString(9),
                    rs.getBoolean(10))).optional();
            case "CONNECTION" -> jdbc.sql("""
                    SELECT n.id, n.full_name, n.current_role, n.current_company, n.city, n.linkedin_url, a.id, a.name, a.relationship, n.opted_out
                    FROM connection n LEFT JOIN account a ON a.id = n.account_id WHERE n.id = :id
                    """).param("id", id).query((rs, n) -> new Person("CONNECTION", rs.getLong(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getString(5), rs.getString(6), rs.getObject(7, Long.class), rs.getString(8), rs.getString(9),
                    rs.getBoolean(10))).optional();
            default -> Optional.empty();
        };
        return p.orElseThrow(() -> new NotFoundException(t.isEmpty() ? "Person" : t.charAt(0) + t.substring(1).toLowerCase(Locale.ROOT), id));
    }

    public Context build(String type, long id) {
        Person p = person(type, id);
        List<String> history = new ArrayList<>();
        List<String> knownBy = new ArrayList<>();
        int best = 0;
        boolean replied = false;
        if ("CONTACT".equals(p.type())) {
            jdbc.sql("""
                    SELECT x.occurred_on, x.channel, x.summary, x.outcome FROM activity x WHERE x.contact_id = :id
                    ORDER BY x.occurred_on DESC, x.id DESC LIMIT 15
                    """).param("id", id).query((rs, n) -> history.add(rs.getDate(1) + " " + rs.getString(2).toLowerCase(Locale.ROOT)
                    .replace('_', ' ') + ": " + rs.getString(3) + " (" + rs.getString(4).toLowerCase(Locale.ROOT).replace('_', ' ') + ")")).list();
            replied = jdbc.sql("SELECT COUNT(*) FROM activity WHERE contact_id = :id AND outcome IN ('REPLIED', 'MEETING_BOOKED')")
                .param("id", id).query(Integer.class).single() > 0;
            jdbc.sql("""
                    SELECT u.full_name, k.strength, k.how_known FROM connection n JOIN connection_knower k ON k.connection_id = n.id
                    JOIN app_user u ON u.id = k.user_id WHERE n.contact_id = :id
                    """).param("id", id).query((rs, n) -> knownBy.add(knower(rs.getString(1), rs.getString(2), rs.getString(3)))).list();
            best = strength(jdbc.sql("""
                    SELECT k.strength FROM connection n JOIN connection_knower k ON k.connection_id = n.id WHERE n.contact_id = :id
                    """).param("id", id).query(String.class).list());
        } else {
            jdbc.sql("""
                    SELECT x.occurred_on, x.channel, x.direction, x.summary FROM network_touch x JOIN network_track t ON t.id = x.track_id
                    WHERE t.connection_id = :id ORDER BY x.occurred_on DESC, x.id DESC LIMIT 15
                    """).param("id", id).query((rs, n) -> history.add(rs.getDate(1) + " " + rs.getString(2).toLowerCase(Locale.ROOT)
                    + ("IN".equals(rs.getString(3)) ? " from them: " : " to them: ") + rs.getString(4))).list();
            replied = jdbc.sql("""
                    SELECT COUNT(*) FROM network_touch x JOIN network_track t ON t.id = x.track_id
                    WHERE t.connection_id = :id AND x.kind = 'REPLY'
                    """).param("id", id).query(Integer.class).single() > 0;
            jdbc.sql("""
                    SELECT u.full_name, k.strength, k.how_known FROM connection_knower k JOIN app_user u ON u.id = k.user_id
                    WHERE k.connection_id = :id
                    """).param("id", id).query((rs, n) -> knownBy.add(knower(rs.getString(1), rs.getString(2), rs.getString(3)))).list();
            best = strength(jdbc.sql("SELECT strength FROM connection_knower WHERE connection_id = :id").param("id", id)
                .query(String.class).list());
        }
        int access;
        String reason;
        if (best > 0) {
            access = best;
            reason = best == 5 ? "Someone in the team knows them closely" : best == 4 ? "Someone in the team knows them well"
                : "Someone in the team knows them";
        } else if (replied) {
            access = 3;
            reason = "They have replied to us before";
        } else if (!history.isEmpty()) {
            access = 2;
            reason = "We have contacted them, no reply yet";
        } else {
            access = 1;
            reason = "No one in the team knows them yet";
        }
        if ("CLIENT".equals(p.relationship()) && access < 5) {
            access++;
            reason += "; their company is a client";
        }
        String research = p.accountId() == null ? null : jdbc.sql("""
                SELECT summary FROM account_research WHERE account_id = :a AND status = 'DONE' ORDER BY requested_at DESC LIMIT 1
                """).param("a", p.accountId()).query(String.class).optional().orElse(null);
        return new Context(p, history, knownBy, access, reason, research, p.accountId() == null ? null : accounts.build(p.accountId()));
    }

    private static String knower(String user, String strength, String how) {
        return user + " (" + strength.toLowerCase(Locale.ROOT).replace('_', ' ') + (how == null || how.isBlank() ? "" : ": " + how) + ")";
    }

    private static int strength(List<String> strengths) {
        if (strengths.contains("CLOSE")) {
            return 5;
        }
        if (strengths.contains("KNOW_WELL")) {
            return 4;
        }
        return strengths.isEmpty() ? 0 : 3;
    }
}
