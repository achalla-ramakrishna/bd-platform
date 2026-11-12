package com.codewalnut.bd.assist;

import java.time.LocalDate;
import java.util.List;

/**
 * Walnut's thinking (ADR-0015): given what one person's BD looks like today and the chat so far, reply and
 * propose actions. Nothing is saved or sent here; the person applies each action with a click.
 */
public interface Coach {

    /** OPEN a page, DRAFT a message for the person to send, SET_NEXT_STEP or LOG_UPDATE on a conversation. */
    List<String> ACTION_TYPES = List.of("OPEN", "DRAFT", "SET_NEXT_STEP", "LOG_UPDATE");

    record Turn(String author, String text) {}

    /** {@code snapshot} is the person's BD snapshot as JSON; {@code channels} and {@code outcomes} are the app's codes. */
    record Request(String name, LocalDate today, String snapshot, List<Turn> history, String message,
                   List<String> channels, List<String> outcomes) {}

    record Action(String type, String label, String path, String text, Long conversationId, String nextStep,
                  String nextStepDue, String channel, String summary, String outcome) {}

    record Reply(String reply, List<Action> actions) {}

    boolean enabled();

    Reply reply(Request request);
}
