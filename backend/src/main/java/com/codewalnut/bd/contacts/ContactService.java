package com.codewalnut.bd.contacts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.codewalnut.bd.accounts.AccountRegistry;
import com.codewalnut.bd.shared.AuditLog;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.RuleViolationException;

/** Adds one contact by hand, for example from a LinkedIn profile the user is looking at (FR-CON-01, FR-CON-06). */
@Service
public class ContactService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern LINKEDIN = Pattern.compile("^https?://([a-z]+\\.)?linkedin\\.com/.+", Pattern.CASE_INSENSITIVE);

    public record Input(Long accountId, String fullName, String title, String persona, String linkedinUrl, String email,
                        String phone, String city, String source, String consentBasis, String notes) {}

    private final ContactRegistry contacts;
    private final AccountRegistry accounts;
    private final AuditLog audit;

    ContactService(ContactRegistry contacts, AccountRegistry accounts, AuditLog audit) {
        this.contacts = contacts;
        this.accounts = accounts;
        this.audit = audit;
    }

    @Transactional
    public long create(Input in, CurrentUser user) {
        List<String> problems = new ArrayList<>();
        if (in.accountId() == null || !accounts.exists(in.accountId())) {
            problems.add("Choose an existing account.");
        }
        String name = trim(in.fullName());
        if (name == null) {
            problems.add("Enter the person's full name.");
        } else if (name.length() > 200) {
            problems.add("The name can be at most 200 characters.");
        }
        String persona = upper(in.persona());
        if (persona == null || !contacts.personaCodes().contains(persona)) {
            problems.add("Choose a persona.");
        }
        String linkedin = trim(in.linkedinUrl());
        if (linkedin != null && !LINKEDIN.matcher(linkedin).matches()) {
            problems.add("The LinkedIn address should start with https://www.linkedin.com/");
        }
        String email = trim(in.email());
        if (email != null && !EMAIL.matcher(email).matches()) {
            problems.add("The email address is not valid.");
        }
        String source = trim(in.source());
        if (source == null) {
            problems.add("Say where this contact came from (FR-CON-06).");
        }
        String consent = upper(in.consentBasis());
        if (consent == null || !ContactRegistry.CONSENT_BASES.contains(consent)) {
            problems.add("Choose a consent basis.");
        }
        if (problems.isEmpty() && contacts.allKeys().containsKey(ContactRegistry.key(in.accountId(), name))) {
            problems.add(name + " is already a contact at this account.");
        }
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
        var result = contacts.upsert(new ContactRegistry.ContactData(in.accountId(), name, trim(in.title()), persona,
            linkedin, email == null ? null : email.toLowerCase(Locale.ROOT), trim(in.phone()), trim(in.city()), source,
            consent, trim(in.notes())));
        audit.record("contact", result.id(), AuditLog.Action.CREATE, Map.of("source", source), user.id());
        return result.id();
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String upper(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase(Locale.ROOT);
    }
}
