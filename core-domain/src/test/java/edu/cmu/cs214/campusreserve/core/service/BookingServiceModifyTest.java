package edu.cmu.cs214.campusreserve.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.cmu.cs214.campusreserve.core.domain.Booking;
import edu.cmu.cs214.campusreserve.core.domain.BookingRevision;
import edu.cmu.cs214.campusreserve.core.domain.BookingStatus;
import edu.cmu.cs214.campusreserve.core.domain.ModificationResult;
import edu.cmu.cs214.campusreserve.core.domain.RejectionReason;
import edu.cmu.cs214.campusreserve.core.domain.TimeInterval;
import edu.cmu.cs214.campusreserve.core.policy.BookingPolicy;
import edu.cmu.cs214.campusreserve.core.support.Fixtures;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** In-place changes to a booking the caller already holds. */
class BookingServiceModifyTest extends BookingServiceTestBase {

    @Test
    void ownerCanNudgeABookingByFifteenMinutes() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        clock.setTo("2026-10-19T09:01:40Z");

        ModificationResult result = modify(id, "u-1", Fixtures.interval("10:15", "11:15"), 4);

        assertTrue(result.isModified());
        assertTrue(result.promotions().isEmpty());
        Booking stored = service.findBooking(id).orElseThrow();
        assertEquals(id, stored.id());
        assertEquals("r-1", stored.roomId());
        assertEquals("u-1", stored.userId());
        assertEquals(BookingStatus.CONFIRMED, stored.status());
        assertEquals(Instant.parse(Fixtures.NOW), stored.createdAt());
        assertEquals(Fixtures.interval("10:15", "11:15"), stored.interval());
        assertEquals(4, stored.attendees());
        assertEquals(1, stored.history().size());
        BookingRevision revision = stored.history().get(0);
        assertEquals(Instant.parse("2026-10-19T09:01:00Z"), revision.at());
        assertEquals(Fixtures.interval("10:00", "11:00"), revision.previousInterval());
        assertEquals(4, revision.previousAttendees());
        assertEquals(List.of("booking-created"), publisher.types());
    }

    @Test
    void aHalfOpenNeighborInTheSameRoomDoesNotCollide() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("12:00", "13:00"));
        bookAndGetId("r-1", "u-2", Fixtures.interval("11:00", "12:00"));

        ModificationResult result = modify(id, "u-1", Fixtures.interval("10:00", "11:00"), 4);

        assertTrue(result.isModified());
        Booking stored = service.findBooking(id).orElseThrow();
        assertEquals(id, stored.id());
        assertEquals(Fixtures.interval("10:00", "11:00"), stored.interval());
    }

    @Test
    void aHalfOpenBookingOnTheOwnersOtherRoomDoesNotCollide() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("12:00", "13:00"));
        bookAndGetId("r-2", "u-1", Fixtures.interval("11:00", "12:00"));

        ModificationResult result = modify(id, "u-1", Fixtures.interval("10:00", "11:00"), 4);

        assertTrue(result.isModified());
        assertEquals(id, service.findBooking(id).orElseThrow().id());
        assertEquals(Fixtures.interval("10:00", "11:00"), service.findBooking(id).orElseThrow().interval());
    }

    @Test
    void aRoomCollisionLeavesTheStoredBookingUnchanged() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        bookAndGetId("r-1", "u-2", Fixtures.interval("11:00", "12:00"));
        Booking before = service.findBooking(id).orElseThrow();

        ModificationResult result = modify(id, "u-1", Fixtures.interval("10:30", "11:30"), 4);

        assertEquals(Optional.of(RejectionReason.ROOM_UNAVAILABLE), result.rejectionReason());
        assertFalse(result.isModified());
        assertEquals(before, service.findBooking(id).orElseThrow());
        assertTrue(service.findBooking(id).orElseThrow().history().isEmpty());
    }

    @Test
    void theOwnersOtherRoomIsCheckedWithoutCountingThisBooking() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        bookAndGetId("r-2", "u-1", Fixtures.interval("14:00", "15:00"));
        Booking before = service.findBooking(id).orElseThrow();

        ModificationResult result = modify(id, "u-1", Fixtures.interval("14:00", "15:00"), 4);

        assertEquals(Optional.of(RejectionReason.USER_DOUBLE_BOOKED), result.rejectionReason());
        assertEquals(before, service.findBooking(id).orElseThrow());
    }

    @Test
    void theBookingCapDoesNotBlockAModification() {
        build(BookingPolicy.defaults().withMaxActiveBookingsPerUser(1));
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));

        ModificationResult result = modify(id, "u-1", Fixtures.interval("10:15", "11:15"), 4);

        assertTrue(result.isModified());
        assertEquals(Fixtures.interval("10:15", "11:15"), service.findBooking(id).orElseThrow().interval());
    }

    @Test
    void attendeesCanChangeWithoutMovingTheInterval() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        TimeInterval interval = Fixtures.interval("10:00", "11:00");

        ModificationResult result = modify(id, "u-1", interval, 6);

        Booking stored = service.findBooking(id).orElseThrow();
        assertTrue(result.isModified());
        assertEquals(interval, stored.interval());
        assertEquals(6, stored.attendees());
        assertEquals(1, stored.history().size());
        assertEquals(interval, stored.history().get(0).previousInterval());
        assertEquals(4, stored.history().get(0).previousAttendees());
    }

    @Test
    void anIdenticalModificationRecordsNothing() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        Booking before = service.findBooking(id).orElseThrow();

        ModificationResult result = modify(id, "u-1", Fixtures.interval("10:00", "11:00"), 4);

        assertTrue(result.isModified());
        assertEquals(before, result.modifiedBooking().orElseThrow());
        assertTrue(service.findBooking(id).orElseThrow().history().isEmpty());
        assertEquals(before, service.findBooking(id).orElseThrow());
    }

    @Test
    void rejectsSomeoneElsesBooking() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        Booking before = service.findBooking(id).orElseThrow();

        ModificationResult result = modify(id, "u-2", Fixtures.interval("10:15", "11:15"), 4);

        assertEquals(Optional.of(RejectionReason.NOT_BOOKING_OWNER), result.rejectionReason());
        assertEquals(before, service.findBooking(id).orElseThrow());
    }

    @Test
    void rejectsAnUnknownBooking() {
        ModificationResult result = modify("bk-missing", "u-1", Fixtures.interval("10:00", "11:00"), 4);
        assertEquals(Optional.of(RejectionReason.BOOKING_NOT_FOUND), result.rejectionReason());
    }

    @Test
    void rejectsACancelledBooking() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        service.cancelBooking(id, "u-1");
        Booking before = service.findBooking(id).orElseThrow();

        ModificationResult result = modify(id, "u-1", Fixtures.interval("10:15", "11:15"), 4);

        assertEquals(Optional.of(RejectionReason.BOOKING_ALREADY_CANCELLED), result.rejectionReason());
        assertEquals(before, service.findBooking(id).orElseThrow());
    }

    @Test
    void rejectsABookingThatHasStarted() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        clock.setTo("2026-10-20T10:30:00Z");
        Booking before = service.findBooking(id).orElseThrow();

        ModificationResult result = modify(id, "u-1", Fixtures.interval("12:00", "13:00"), 4);

        assertEquals(Optional.of(RejectionReason.BOOKING_ALREADY_STARTED), result.rejectionReason());
        assertEquals(before, service.findBooking(id).orElseThrow());
    }

    @Test
    void rejectsAPartyTheRoomCannotSeat() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        Booking before = service.findBooking(id).orElseThrow();

        ModificationResult result = modify(id, "u-1", Fixtures.interval("10:00", "11:00"), 11);

        assertEquals(Optional.of(RejectionReason.ATTENDEES_EXCEED_CAPACITY), result.rejectionReason());
        assertEquals(before, service.findBooking(id).orElseThrow());
    }

    @Test
    void rejectsAnIntervalShorterThanTheMinimum() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        Booking before = service.findBooking(id).orElseThrow();

        ModificationResult result = modify(id, "u-1", Fixtures.interval("10:00", "10:15"), 4);

        assertEquals(Optional.of(RejectionReason.INTERVAL_TOO_SHORT), result.rejectionReason());
        assertEquals(before, service.findBooking(id).orElseThrow());
    }

    @Test
    void cancellingKeepsTheRevision() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        modify(id, "u-1", Fixtures.interval("10:15", "11:15"), 4);

        service.cancelBooking(id, "u-1");

        Booking stored = service.findBooking(id).orElseThrow();
        assertEquals(BookingStatus.CANCELLED, stored.status());
        assertEquals(1, stored.history().size());
        assertEquals(Fixtures.interval("10:00", "11:00"), stored.history().get(0).previousInterval());
        assertEquals(Fixtures.interval("10:15", "11:15"), stored.interval());
    }

    private ModificationResult modify(String id, String userId, TimeInterval interval, int attendees) {
        return service.modifyBooking(id, userId, interval, attendees);
    }
}
