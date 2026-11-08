package com.codewalnut.bd.assist;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.codewalnut.bd.admin.CurrentUserProvider;
import com.codewalnut.bd.contacts.ContactRegistry;
import com.codewalnut.bd.shared.Role;

@RestController
@RequestMapping("/api/v1/assist")
class AssistController {

    private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/webp", "image/gif");
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    private static final int MAX_TEXT = 20_000;

    private final ProfileReader reader;
    private final AccountResearcher researcher;
    private final GccFinder finder;
    private final ContactRegistry contacts;
    private final CurrentUserProvider currentUser;

    AssistController(ProfileReader reader, AccountResearcher researcher, GccFinder finder, ContactRegistry contacts,
                     CurrentUserProvider currentUser) {
        this.reader = reader;
        this.researcher = researcher;
        this.finder = finder;
        this.contacts = contacts;
        this.currentUser = currentUser;
    }

    @GetMapping("/status")
    Map<String, Boolean> status() {
        return Map.of("profileReading", reader.enabled(), "accountResearch", researcher.enabled(),
            "findGccs", finder.enabled());
    }

    /** Suggests contact fields from a pasted LinkedIn profile. Nothing is saved. */
    @PostMapping("/read-profile")
    ProfileReader.Suggestion readProfile(@RequestParam(required = false) String linkedinUrl,
                                         @RequestParam(required = false) String text,
                                         @RequestParam(required = false) MultipartFile image) throws IOException {
        currentUser.get().require(Role.PIPELINE_EDITORS);
        boolean hasText = text != null && !text.isBlank();
        boolean hasImage = image != null && !image.isEmpty();
        if (!hasText && !hasImage) {
            throw new IllegalArgumentException("Paste the profile text or a screenshot first.");
        }
        if (hasText && text.length() > MAX_TEXT) {
            throw new IllegalArgumentException("The pasted text is too long. Copy just the top of the profile.");
        }
        if (hasImage && (!IMAGE_TYPES.contains(image.getContentType()) || image.getSize() > MAX_IMAGE_BYTES)) {
            throw new IllegalArgumentException("Use a PNG, JPEG, WebP or GIF screenshot of at most 5 MB.");
        }
        List<String> personas = contacts.personaCodes().stream().sorted().toList();
        return reader.read(new ProfileReader.Request(linkedinUrl, hasText ? text : null,
            hasImage ? image.getBytes() : null, hasImage ? image.getContentType() : null, personas));
    }
}
