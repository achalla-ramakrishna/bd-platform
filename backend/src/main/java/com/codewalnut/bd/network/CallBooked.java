package com.codewalnut.bd.network;

import java.time.LocalDate;

/** A call was booked on a GTN track, by hand or on the profile page (ADR-0026). Listeners act after it commits. */
public record CallBooked(long trackId, long connectionId, long ownerId, LocalDate on) {}
