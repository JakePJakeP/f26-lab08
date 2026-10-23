package edu.cmu.cs214.campusreserve.core.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * One recorded change to a booking: what it used to be, and when it changed.
 *
 * <p>The booking's own interval and attendees are the current values. This
 * record holds the values they replaced. {@code at} is a whole minute in UTC,
 * the same clock the service uses for every other instant.
 */
public record BookingRevision(Instant at, TimeInterval previousInterval, int previousAttendees) {

    public BookingRevision {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(previousInterval, "previousInterval");
        if (at.getNano() != 0 || at.getEpochSecond() % 60 != 0) {
            throw new IllegalArgumentException("Revision time must land on a whole minute: " + at);
        }
        if (previousAttendees <= 0) {
            throw new IllegalArgumentException("Previous attendees must be positive: " + previousAttendees);
        }
    }
}
