package com.codewalnut.bd.contacts;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.codewalnut.bd.shared.AuditLog;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.RuleViolationException;

/**
 * Imports people into Connections from a CSV (GTN FR-1.1, ADR-0017): our template, or the Connections export
 * LinkedIn gives a member for their own account (an upload, which ADR-0005 allows; nothing is read from
 * LinkedIn). People already in Connections are matched on LinkedIn address, email, WhatsApp number, or name and
 * company (FR-1.3): the importer is added as someone who knows them and empty details are filled in.
 */
@Service
public class ConnectionImport {

    static final int MAX_ROWS = 5000;
    static final int MAX_CHARS = 3_000_000;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern LINKEDIN = Pattern.compile("^https?://([a-z]+\\.)?linkedin\\.com/.+", Pattern.CASE_INSENSITIVE);
    private static final List<DateTimeFormatter> DATES = List.of(DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH), DateTimeFormatter.ofPattern("d-MMM-uu", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d/M/uuuu"), DateTimeFormatter.ofPattern("d-M-uuuu"));

    /**
     * {@code csv}: the file's text. {@code sourceTag}: where these people come from, e.g. "college alumni" (a
     * column in the file wins). {@code strength}: how well the importer knows them (default acquaintance).
     */
    public record Request(String csv, String sourceTag, String strength, String howKnown, Boolean dryRun) {}

    public record Problem(int row, String message) {}

    /**
     * {@code added}: new people. {@code linked}: people already in Connections whom the importer now knows too.
     * {@code alreadyKnown}: already in Connections and known by the importer. {@code sample}: the first names added.
     */
    public record Result(int rows, int added, int linked, int alreadyKnown, int skipped, List<Problem> problems,
                         List<String> sample, boolean dryRun) {}

    private record Row(int line, String name, String company, String role, String city, String linkedin, String email,
                       String whatsapp, String source, String howKnown, LocalDate connectedOn, String notes) {}

    private record Existing(long id, String linkedin, String email, String whatsapp, String nameCompany) {}

    private final JdbcClient jdbc;
    private final AuditLog audit;

    ConnectionImport(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    public Result run(Request in, CurrentUser user) {
        if (in.csv() == null || in.csv().isBlank()) {
            throw new RuleViolationException(List.of("Choose a CSV file with a header row."));
        }
        if (in.csv().length() > MAX_CHARS) {
            throw new RuleViolationException(List.of("The file is too large: split it into files of up to "
                + MAX_ROWS + " people."));
        }
        String strength = in.strength() == null || in.strength().isBlank() ? "ACQUAINTANCE"
            : in.strength().trim().toUpperCase(Locale.ROOT);
        if (!ConnectionService.STRENGTHS.contains(strength)) {
            throw new RuleViolationException(List.of("Say how well you know them."));
        }
        String tag = trim(in.sourceTag(), 100);
        boolean dryRun = Boolean.TRUE.equals(in.dryRun());
        List<Problem> problems = new ArrayList<>();
        List<Row> rows = parse(in.csv(), problems);
        if (rows.size() > MAX_ROWS) {
            throw new RuleViolationException(List.of("The file has " + rows.size() + " people; import at most " + MAX_ROWS
                + " at a time."));
        }

        List<Existing> existing = jdbc.sql("""
                SELECT id, linkedin_url, email, whatsapp, CONCAT(LOWER(full_name), '|', LOWER(COALESCE(current_company, '')))
                FROM connection
                """).query((rs, n) -> new Existing(rs.getLong(1), linkedinKey(rs.getString(2)),
                    rs.getString(3) == null ? null : rs.getString(3).toLowerCase(Locale.ROOT), rs.getString(4), rs.getString(5)))
            .list();
        Map<String, Long> byKey = new HashMap<>();
        for (Existing e : existing) {
            index(byKey, e.id(), e.linkedin(), e.email(), e.whatsapp(), e.nameCompany());
        }
        Set<Long> knownByMe = new HashSet<>(jdbc.sql("SELECT connection_id FROM connection_knower WHERE user_id = :u")
            .param("u", user.id()).query(Long.class).list());

        int added = 0;
        int linked = 0;
        int alreadyKnown = 0;
        List<String> sample = new ArrayList<>();
        Set<Long> touched = new HashSet<>();
        for (Row r : rows) {
            String nameCompany = r.name().toLowerCase(Locale.ROOT) + "|" + (r.company() == null ? "" : r.company().toLowerCase(Locale.ROOT));
            Long match = first(byKey, linkedinKey(r.linkedin()), r.email(), r.whatsapp(), nameCompany);
            String source = r.source() != null ? r.source() : tag;
            if (match == null) {
                added++;
                if (sample.size() < 10) {
                    sample.add(r.name());
                }
                long id = dryRun ? -(added) : insert(r, source, strength, user);
                index(byKey, id, linkedinKey(r.linkedin()), r.email(), r.whatsapp(), nameCompany);
                touched.add(id);
                knownByMe.add(id);
            } else if (touched.contains(match)) {
                problems.add(new Problem(r.line(), r.name() + " appears more than once in the file; the first row was used."));
            } else if (knownByMe.contains(match)) {
                alreadyKnown++;
                touched.add(match);
                if (!dryRun) {
                    fillBlanks(match, r, source);
                }
            } else {
                linked++;
                touched.add(match);
                knownByMe.add(match);
                if (!dryRun) {
                    fillBlanks(match, r, source);
                    jdbc.sql("""
                            INSERT INTO connection_knower (connection_id, user_id, strength, how_known, connected_on)
                            VALUES (:c, :u, :s, :how, :on)
                            """).param("c", match).param("u", user.id()).param("s", strength)
                        .param("how", howKnown(r, in)).param("on", connectedOn(r)).update();
                    audit.record("connection", match, AuditLog.Action.IMPORT, Map.of("knower", user.id()), user.id());
                }
            }
        }
        int skipped = (int) problems.stream().filter(p -> p.message().startsWith("Skipped")).count();
        return new Result(rows.size() + skipped, added, linked, alreadyKnown, skipped,
            problems.stream().limit(50).toList(), sample, dryRun);
    }

    private long insert(Row r, String source, String strength, CurrentUser user) {
        var key = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO connection (full_name, current_company, current_role, city, linkedin_url, email, whatsapp,
                    source_tag, notes, created_by)
                VALUES (:name, :company, :role, :city, :linkedin, :email, :whatsapp, :source, :notes, :user)
                """)
            .param("name", r.name()).param("company", r.company()).param("role", r.role()).param("city", r.city())
            .param("linkedin", r.linkedin()).param("email", r.email()).param("whatsapp", r.whatsapp())
            .param("source", source).param("notes", r.notes()).param("user", user.id())
            .update(key);
        long id = key.getKey().longValue();
        jdbc.sql("""
                INSERT INTO connection_knower (connection_id, user_id, strength, how_known, connected_on)
                VALUES (:c, :u, :s, :how, :on)
                """).param("c", id).param("u", user.id()).param("s", strength).param("how", r.howKnown())
            .param("on", connectedOn(r)).update();
        audit.record("connection", id, AuditLog.Action.IMPORT, Map.of("created", true), user.id());
        return id;
    }

    private void fillBlanks(long id, Row r, String source) {
        jdbc.sql("""
                UPDATE connection SET linkedin_url = COALESCE(linkedin_url, :linkedin), email = COALESCE(email, :email),
                    whatsapp = COALESCE(whatsapp, :whatsapp), source_tag = COALESCE(source_tag, :source),
                    current_role = COALESCE(current_role, :role), city = COALESCE(city, :city)
                WHERE id = :id
                """).param("linkedin", r.linkedin()).param("email", r.email()).param("whatsapp", r.whatsapp())
            .param("source", source).param("role", r.role()).param("city", r.city()).param("id", id).update();
    }

    private static String howKnown(Row r, Request in) {
        return r.howKnown() != null ? r.howKnown() : trim(in.howKnown(), 400);
    }

    private static LocalDate connectedOn(Row r) {
        return r.connectedOn() != null ? r.connectedOn() : LocalDate.now();
    }

    private static void index(Map<String, Long> byKey, long id, String linkedin, String email, String whatsapp,
                              String nameCompany) {
        if (linkedin != null) {
            byKey.putIfAbsent("l:" + linkedin, id);
        }
        if (email != null) {
            byKey.putIfAbsent("e:" + email.toLowerCase(Locale.ROOT), id);
        }
        if (whatsapp != null) {
            byKey.putIfAbsent("w:" + whatsapp, id);
        }
        byKey.putIfAbsent("n:" + nameCompany, id);
    }

    private static Long first(Map<String, Long> byKey, String linkedin, String email, String whatsapp, String nameCompany) {
        if (linkedin != null && byKey.containsKey("l:" + linkedin)) {
            return byKey.get("l:" + linkedin);
        }
        if (email != null && byKey.containsKey("e:" + email.toLowerCase(Locale.ROOT))) {
            return byKey.get("e:" + email.toLowerCase(Locale.ROOT));
        }
        if (whatsapp != null && byKey.containsKey("w:" + whatsapp)) {
            return byKey.get("w:" + whatsapp);
        }
        return byKey.get("n:" + nameCompany);
    }

    /** A LinkedIn profile address in one spelling: lower case, https, no www, no query or trailing slash. */
    static String linkedinKey(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String u = url.trim().toLowerCase(Locale.ROOT).replaceFirst("^https?://", "").replaceFirst("^[a-z]{2,3}\\.linkedin", "linkedin")
            .replaceFirst("^www\\.", "");
        int q = u.indexOf('?');
        if (q >= 0) {
            u = u.substring(0, q);
        }
        return u.replaceAll("/+$", "");
    }

    // ---- parsing ----

    private static final Map<String, String> HEADERS = Map.ofEntries(
        Map.entry("first name", "first"), Map.entry("firstname", "first"), Map.entry("last name", "last"),
        Map.entry("lastname", "last"), Map.entry("full name", "name"), Map.entry("name", "name"),
        Map.entry("url", "linkedin"), Map.entry("linkedin", "linkedin"), Map.entry("linkedin url", "linkedin"),
        Map.entry("profile url", "linkedin"), Map.entry("email", "email"), Map.entry("email address", "email"),
        Map.entry("company", "company"), Map.entry("current company", "company"), Map.entry("position", "role"),
        Map.entry("role", "role"), Map.entry("title", "role"), Map.entry("current role", "role"),
        Map.entry("whatsapp", "whatsapp"), Map.entry("phone", "whatsapp"), Map.entry("mobile", "whatsapp"),
        Map.entry("connected on", "connected"), Map.entry("city", "city"), Map.entry("location", "city"),
        Map.entry("notes", "notes"), Map.entry("source", "source"), Map.entry("source tag", "source"),
        Map.entry("how we know them", "how"), Map.entry("how known", "how"), Map.entry("how we know", "how"));

    private static List<Row> parse(String csv, List<Problem> problems) {
        List<List<String>> lines = Csv.parse(csv);
        int header = -1;
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < lines.size() && i < 20; i++) {
            Map<String, Integer> found = new HashMap<>();
            List<String> cells = lines.get(i);
            for (int c = 0; c < cells.size(); c++) {
                String key = HEADERS.get(cells.get(c).trim().toLowerCase(Locale.ROOT).replace('_', ' '));
                if (key != null) {
                    found.putIfAbsent(key, c);
                }
            }
            if (found.containsKey("name") || found.containsKey("first")) {
                header = i;
                col = found;
                break;
            }
        }
        if (header < 0) {
            throw new RuleViolationException(List.of("No header row found: the file needs a \"Full name\" column, or "
                + "\"First Name\" and \"Last Name\" as in LinkedIn's Connections export."));
        }
        List<Row> rows = new ArrayList<>();
        for (int i = header + 1; i < lines.size(); i++) {
            List<String> cells = lines.get(i);
            if (cells.stream().allMatch(String::isBlank)) {
                continue;
            }
            int line = i + 1;
            String name = cell(cells, col, "name");
            if (name == null) {
                String firstName = cell(cells, col, "first");
                String lastName = cell(cells, col, "last");
                name = firstName == null ? lastName : lastName == null ? firstName : firstName + " " + lastName;
            }
            if (name == null || name.length() > 200) {
                problems.add(new Problem(line, "Skipped: " + (name == null ? "no name." : "the name is longer than 200 characters.")));
                continue;
            }
            String linkedin = cell(cells, col, "linkedin");
            if (linkedin != null && (linkedin.length() > 400 || !LINKEDIN.matcher(linkedin).matches())) {
                problems.add(new Problem(line, name + ": the LinkedIn address isn't one, so it was left out."));
                linkedin = null;
            }
            String email = cell(cells, col, "email");
            if (email != null && (email.length() > 254 || !EMAIL.matcher(email).matches())) {
                problems.add(new Problem(line, name + ": the email address isn't valid, so it was left out."));
                email = null;
            }
            String rawPhone = cell(cells, col, "whatsapp");
            String whatsapp = Phones.whatsapp(rawPhone);
            if (rawPhone != null && whatsapp == null) {
                problems.add(new Problem(line, name + ": the WhatsApp number isn't one, so it was left out."));
            }
            rows.add(new Row(line, name, limit(cell(cells, col, "company"), 200), limit(cell(cells, col, "role"), 200),
                limit(cell(cells, col, "city"), 80), linkedin, email == null ? null : email.toLowerCase(Locale.ROOT), whatsapp,
                limit(cell(cells, col, "source"), 100), limit(cell(cells, col, "how"), 400), date(cell(cells, col, "connected")),
                limit(cell(cells, col, "notes"), 2000)));
        }
        return rows;
    }

    private static String cell(List<String> cells, Map<String, Integer> col, String key) {
        Integer c = col.get(key);
        return c == null || c >= cells.size() ? null : trim(cells.get(c), Integer.MAX_VALUE);
    }

    private static LocalDate date(String s) {
        if (s == null) {
            return null;
        }
        for (DateTimeFormatter f : DATES) {
            try {
                LocalDate d = LocalDate.parse(s, f);
                return d.isAfter(LocalDate.now()) ? null : d;
            } catch (DateTimeParseException e) {
                // try the next format
            }
        }
        return null;
    }

    private static String limit(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    private static String trim(String s, int max) {
        return s == null || s.isBlank() ? null : limit(s.trim(), max);
    }

    /** RFC 4180 CSV: quoted fields with commas, quotes and line breaks. */
    static final class Csv {

        private Csv() {
        }

        static List<List<String>> parse(String text) {
            List<List<String>> rows = new ArrayList<>();
            List<String> row = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            String t = text.startsWith("﻿") ? text.substring(1) : text;
            for (int i = 0; i < t.length(); i++) {
                char ch = t.charAt(i);
                if (quoted) {
                    if (ch == '"') {
                        if (i + 1 < t.length() && t.charAt(i + 1) == '"') {
                            cell.append('"');
                            i++;
                        } else {
                            quoted = false;
                        }
                    } else {
                        cell.append(ch);
                    }
                } else if (ch == '"') {
                    quoted = true;
                } else if (ch == ',') {
                    row.add(cell.toString());
                    cell.setLength(0);
                } else if (ch == '\n' || ch == '\r') {
                    if (ch == '\r' && i + 1 < t.length() && t.charAt(i + 1) == '\n') {
                        i++;
                    }
                    row.add(cell.toString());
                    cell.setLength(0);
                    rows.add(row);
                    row = new ArrayList<>();
                } else {
                    cell.append(ch);
                }
            }
            if (cell.length() > 0 || !row.isEmpty()) {
                row.add(cell.toString());
                rows.add(row);
            }
            return rows;
        }
    }
}
