package com.codewalnut.bd.assist;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.codewalnut.bd.accounts.AccountRegistry;
import com.codewalnut.bd.admin.CurrentUserProvider;
import com.codewalnut.bd.contacts.ContactRegistry;
import com.codewalnut.bd.reporting.ConnectionQueries;
import com.codewalnut.bd.reporting.ConversationQueries;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.Role;

/**
 * The BD assistant (ADR-0013): paste a note or screenshots about a touch; Claude says who it was with and
 * what happened, and this matches it to our conversations, connections and accounts so the person can
 * update or add them with one click each. Nothing is saved here; the page applies what the person accepts
 * through the usual endpoints, with the usual rules.
 */
@RestController
@RequestMapping("/api/v1/assistant")
class AssistantController {

    static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/webp", "image/gif");
    static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    static final int MAX_IMAGES = 4;
    static final int MAX_TEXT = 20_000;
    static final List<String> CHANNELS = List.of("LINKEDIN_CONNECTION", "LINKEDIN_MESSAGE", "INMAIL", "EMAIL", "CALL",
        "MEETING", "EVENT", "REFERRAL");
    static final List<String> OUTCOMES = List.of("NO_REPLY", "REPLIED", "MEETING_BOOKED", "NOT_INTERESTED", "REFERRED_ON", "DONE");

    /**
     * What was understood and what it matches. {@code suggestion} is the first thing to offer:
     * UPDATE_CONVERSATION (an open conversation with this person exists), START_CONVERSATION (we know the
     * account) or ADD_CONNECTION (we know neither).
     */
    record Answer(NoteReader.Reading reading, List<ConversationQueries.Conversation> conversations,
                  List<ConnectionQueries.Connection> connections, List<AccountRegistry.Found> accounts,
                  Long contactId, String suggestion) {}

    private final NoteReader reader;
    private final ConversationQueries conversations;
    private final ConnectionQueries connections;
    private final AccountRegistry accounts;
    private final ContactRegistry contacts;
    private final CurrentUserProvider currentUser;

    AssistantController(NoteReader reader, ConversationQueries conversations, ConnectionQueries connections,
                        AccountRegistry accounts, ContactRegistry contacts, CurrentUserProvider currentUser) {
        this.reader = reader;
        this.conversations = conversations;
        this.connections = connections;
        this.accounts = accounts;
        this.contacts = contacts;
        this.currentUser = currentUser;
    }

    @GetMapping("/status")
    Map<String, Boolean> status() {
        currentUser.get();
        return Map.of("enabled", reader.enabled());
    }

    @PostMapping("/read")
    Answer read(@RequestParam(required = false) String text,
                @RequestParam(required = false) List<MultipartFile> images) throws IOException {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        boolean hasText = text != null && !text.isBlank();
        List<NoteReader.Image> pictures = new ArrayList<>();
        for (MultipartFile image : images == null ? List.<MultipartFile>of() : images) {
            if (image.isEmpty()) {
                continue;
            }
            if (!IMAGE_TYPES.contains(image.getContentType()) || image.getSize() > MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("Use PNG, JPEG, WebP or GIF screenshots of at most 5 MB each.");
            }
            pictures.add(new NoteReader.Image(image.getBytes(), image.getContentType()));
        }
        if (!hasText && pictures.isEmpty()) {
            throw new IllegalArgumentException("Type what happened, or paste a screenshot.");
        }
        if (hasText && text.length() > MAX_TEXT) {
            throw new IllegalArgumentException("That is too long. Paste the latest part of the conversation.");
        }
        if (pictures.size() > MAX_IMAGES) {
            throw new IllegalArgumentException("Paste at most " + MAX_IMAGES + " screenshots at a time.");
        }
        NoteReader.Reading r = reader.read(new NoteReader.Request(hasText ? text : null, pictures, LocalDate.now(),
            CHANNELS, OUTCOMES));
        return match(r, user);
    }

    /** Finds what the reading is about. Public for tests with a stand-in reader. */
    Answer match(NoteReader.Reading r, CurrentUser user) {
        String who = r.fullName();
        List<ConversationQueries.Conversation> convs = who.isBlank() ? List.of()
            : conversations.list(null, null, null, null, who).items().stream().limit(5).toList();
        List<ConnectionQueries.Connection> conns = who.isBlank() ? List.of()
            : connections.list(who, null, null, null, null, user.id()).items().stream().limit(5).toList();
        List<AccountRegistry.Found> accs = new ArrayList<>(accounts.search(r.company(), 5));
        // Fall back to the account of a matching conversation or connection.
        for (var c : convs) {
            if (accs.stream().noneMatch(a -> a.id() == c.accountId())) {
                accs.addAll(accounts.search(c.websiteDomain(), 1));
            }
        }
        for (var c : conns) {
            if (c.accountId() != null && accs.stream().noneMatch(a -> a.id() == c.accountId())) {
                accs.addAll(accounts.search(c.accountName(), 1));
            }
        }
        Long contactId = accs.isEmpty() ? null : contacts.findByName(accs.get(0).id(), who).orElse(null);
        boolean openConversation = convs.stream().anyMatch(c -> !"CLOSED".equals(c.status()));
        String suggestion = openConversation ? "UPDATE_CONVERSATION" : !accs.isEmpty() ? "START_CONVERSATION" : "ADD_CONNECTION";
        return new Answer(r, convs, conns, accs.stream().distinct().limit(5).toList(), contactId, suggestion);
    }
}
