package com.codewalnut.bd.assist;

import java.time.LocalDate;
import java.util.List;

import com.codewalnut.bd.reporting.ProspectContextQueries;

/**
 * The BD agent's ops step (ADR-0026): before a booked call, a call brief and a draft opportunity. No tools; nothing is
 * created until the owner clicks.
 */
public interface CallPrepWriter {

    /** {@code brief}: their research brief as text, or null; {@code extra}: replies and open opportunities, as lines. */
    record Request(ProspectContextQueries.Context person, String ownerName, LocalDate callOn, String brief, List<String> extra,
                   LocalDate today) {}

    /** {@code source}: "Our records" or "Research". */
    record Need(String point, String source) {}

    record Concern(String concern, String answer) {}

    record Prep(String goal, String about, List<Need> needs, String recap, List<String> questions, List<Concern> concerns,
                String nextStep) {}

    /** {@code verdict}: YES, MAYBE or NOT_YET; {@code type}: STAFF_AUGMENTATION or PROJECT. No value, rate or date. */
    record Draft(String verdict, String name, String type, String need, List<String> roles) {}

    record Result(Prep prep, Draft draft, String model) {}

    boolean enabled();

    Result write(Request request);
}
