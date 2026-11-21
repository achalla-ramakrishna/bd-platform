package com.codewalnut.bd.network;

import java.time.LocalDate;

/**
 * A reply found in Gmail on a GTN track and logged (ADR-0020); published after the transaction commits, so the
 * reply agent (ADR-0024) can read it without GTN depending on it.
 */
public record EmailReplyFound(long trackId, long ownerId, long connectionId, String gmailMessageId, String preview, LocalDate on) {}
