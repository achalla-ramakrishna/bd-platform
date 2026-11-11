package com.codewalnut.bd.assist;

import java.time.LocalDate;
import java.util.List;

/**
 * Reads what a person pasted about BD work (a note, an email, a screenshot of a chat, a profile, an event
 * page) and says what it is about: a touch with a person, a conference or event, or a leader interview,
 * with the details of each (ADR-0013). Nothing is saved: the person checks the
 * suggestions and applies them. Nothing is read from or sent to LinkedIn (ADR-0005).
 */
public interface NoteReader {

    /** What the material is mainly about; the page leads with the matching card. */
    List<String> ABOUT = List.of("TOUCH", "EVENT", "INTERVIEW", "ACCOUNT", "OPPORTUNITY");
    List<String> OPPORTUNITY_TYPES = List.of("STAFF_AUGMENTATION", "PROJECT");
    List<String> EVENT_KINDS = List.of("CONFERENCE", "MEETUP", "WEBINAR", "ROUNDTABLE", "OTHER");
    List<String> EVENT_ROLES = List.of("WATCHING", "SIGNED_UP", "ATTENDING", "PRESENTING", "STALL", "ORGANISING");
    List<String> INTERVIEW_FORMATS = List.of("BLOG", "PODCAST", "VIDEO", "LINKEDIN_ARTICLE");
    List<String> INTERVIEW_STATUSES = List.of("IDEA", "ASKED", "AGREED", "BOOKED", "RECORDED", "PUBLISHED", "DECLINED");

    record Image(byte[] bytes, String mediaType) {}

    /** {@code stages} are the configured opportunity stage codes, in order. */
    record Request(String text, List<Image> images, LocalDate today, List<String> channels, List<String> outcomes,
                   List<String> stages) {}

    /** Empty strings mean "not shown". Dates are ISO (YYYY-MM-DD) or empty. */
    record Reading(String reply, String fullName, String title, String company, String city, String linkedinUrl,
                   String topic, String channel, String occurredOn, String summary, String outcome, String nextStep,
                   String nextStepDue, boolean newConnection, Photo photo, String about, Event event,
                   Interview interview, Account account, Opportunity opportunity) {}

    /** A company or GCC centre and the facts shown about it; null when none. Numbers are null when not shown. */
    record Account(String name, String parentCompany, String parentHqCountry, String city, String websiteDomain,
                   String industry, Integer yearEstablished, Integer employeeCount, Integer openRoles, String notes) {}

    /** Business with an account: what they need, its size and stage; null when none. */
    record Opportunity(String name, String type, Long valueInr, String expectedCloseDate, String needSummary,
                       String targetStartDate, String stage) {}

    /** A conference, meetup or webinar mentioned in the material; null when there is none. */
    record Event(String name, String kind, String startsOn, String endsOn, String city, boolean online, String url,
                 String organiser, String ourRole, String notes) {}

    /**
     * A leader interview for our blog, podcast or video: who, the topic, the format, the stage it has reached
     * and the date of that stage (asked, booked for, recorded or published). Null when there is none.
     */
    record Interview(String leaderName, String leaderTitle, String company, String topic, String format, String status,
                     String on, String publishedUrl, String notes) {}

    /**
     * Where the person's own profile photo is in one of the screenshots: the image's index and a box as
     * fractions (0 to 1) of that image's width and height. Null when no photo of them is shown.
     */
    record Photo(int image, double x, double y, double width, double height) {}

    boolean enabled();

    Reading read(Request request);
}
