# PLAN.md

One section per milestone. Fill them in order, with the plan before you run
anything, the guardrail before you run a task, and the run after.

Keep it short and specific. Point at modules, gates, and file paths.

---

## Milestone 1: The plan

Four tasks, in the order below. Task 1 is the Milestone 3 run: it has no
predecessor, and it does not add a permit to the sealed `DomainEvent`
hierarchy, so `notifications` still compiles and a core-domain gate can verify
it alone.

Starter counts, from the green baseline: core-domain 167, storage 63,
notifications 41, http-api 78, analytics 137, Java total 418. A task is done
only when its new tests have raised the count of every module it touches.
Shipped tests are not edited. New tests sit beside them. Gates run from the
repository root, except pytest, which runs from `analytics/`.

### Decisions that bind every task

**History.** A `BookingRevision` in `core-domain` holds `at` (the service
clock, truncated to a minute), `previousInterval`, and `previousAttendees`.
`Booking` carries `history()`, an immutable list, oldest first. The booking's
own interval and attendees stay the current values, so "what it used to be" is
the revision and "what it is now" is the booking. `Booking.confirmed` keeps
today's parameter list and starts from an empty history, which is why storage,
billing, and notifications tests keep compiling without edits.
`cancelled()` copies the list. `InMemoryBookingStore.save` stores the
`Booking` object, so no store port and no `storage` change is required for
history. A real change appends one revision of the pre-change values. A
no-op does not.

**Errors.** Business refusals return a sealed `ModificationResult`, shaped
like `CancellationResult`: `Modified(booking, promotions)` or
`Rejected(reason, detail)`. No new `RejectionReason` constants. Reuse
`BOOKING_NOT_FOUND`, `NOT_BOOKING_OWNER`, `BOOKING_ALREADY_CANCELLED`,
`BOOKING_ALREADY_STARTED`, whatever `BookingRules.checkRequest` already
returns, `ROOM_UNAVAILABLE` for a room collision, and `USER_DOUBLE_BOOKED`
for the booker's other rooms. A rejected modify does not save, does not append
history, does not publish, and does not touch the waitlist. A colliding
modify is refused. It does not join the waitlist. `USER_BOOKING_LIMIT_REACHED`
is never produced. Check order: the booking exists; the caller owns it and it
is upcoming and still holds the room (`BookingRules.checkCancellable`); if the
interval and attendees are unchanged, return the stored booking with its
history untouched; otherwise `checkRequest` for the same room and user; then
`ConflictChecker` on the room's other active bookings; then `ConflictChecker`
on the user's other active bookings. The room is not an argument of the
domain method.

**Conflict and waitlist.** Both conflict checks call `ConflictChecker`, which
calls `TimeInterval.overlaps`. The booking being modified is removed from the
collections passed in. There is no new overlap method. Promotion, when Task 2
adds it, calls the existing private `promoteWaitlist` in `BookingService`.
`promotions` is empty until that task. Publishing, when Task 3 adds it, goes
through the existing `publish`, which swallows subscriber failures (rule 5).

### Task 1: Core-domain booking modification and history

An owner can shorten, lengthen, or move an upcoming booking, or change its
attendee count, in the same room. The id, room, user, status, and `createdAt`
stay. A fifteen-minute nudge of the same booking succeeds. A half-open
neighbor, in the same room or on the booker's other room, does not collide.
A real collision or a broken request rule leaves the stored booking identical,
including history. A booker already at `maxActiveBookingsPerUser` can still
modify. A no-op succeeds and appends nothing. A real change appends one
`BookingRevision` of the previous interval and attendees. Cancelling afterward
keeps that history.

**Modules.** `core-domain` only (`Booking`, `BookingRevision`,
`ModificationResult`, `BookingService`, and a new test under
`core-domain/src/test`). `Booking.confirmed`'s signature stays. `DomainEvent`
and its permits clause stay. No other module.

**Depends on.** Nothing.

**Checkpoint.** `mvn -pl core-domain -am test`. Done when it exits 0,
core-domain's `Tests run` line is above 167 with 0 failures and 0 errors, and
a new test shows all of the following: the self-nudge and both half-open
cases stored under the same id; `ROOM_UNAVAILABLE` and `USER_DOUBLE_BOOKED`
rejections whose reloaded booking still has the original interval, attendees,
and history; a user at the booking cap whose modify succeeds; non-owner,
cancelled, started, and not-found rejections; one rule rejection (attendees
above room capacity, or a too-short interval) that also leaves the original;
a no-op whose history length is unchanged; a real change whose single
revision holds the previous interval and attendees and whose booking fields
hold the new ones.

**Green while wrong.** A private overlaps helper that copies
`TimeInterval.overlaps` exactly still passes every case above. Rule 3 is
broken, and the gate cannot see the copy.

### Task 2: Waitlist promotion on interval changes

A successful modify that changed the interval runs the same FIFO pass
`cancelBooking` runs. Each candidate is re-checked against the room and
against that waiter's own calendar, so a promotion cannot double-book. A
shorten or a move that frees a waited-for slot promotes the earliest fitting
waiter and leaves a later waiter for that same slot waiting; a waiter who
would double-book is skipped and the next fitting waiter is considered. A
lengthen frees nothing: a waiter still covered by the longer booking stays
waiting, and the pass does run, which is visible because an already-expired
waitlist entry for that room is dropped, as it is on cancel. An attendee-only
change does not start the pass: an expired entry on that room is still there
afterward. The owner of the original booking is still the only writer of
waitlist rows, and that writer is still `BookingService`.

**Modules.** `core-domain` only (`BookingService` and a new test). No new
production caller of `WaitlistStore.save` or `WaitlistStore.remove`.

**Depends on.** Task 1.

**Checkpoint.** `mvn -pl core-domain -am test`. Done when it exits 0,
core-domain's `Tests run` line is above Task 1's count with 0 failures and 0
errors, and a new test shows the shorten/move FIFO outcome including the
skipped double-booked waiter, the lengthen outcome (covered waiter still
waiting, expired entry removed), and the attendee-only outcome (expired
entry still present, no promotion).

**Green while wrong.** `BookingService` delegates to a new class that calls
`WaitlistStore.remove` itself. The tests only observe who holds the slot, so
the gate stays green while rule 4 is broken.

### Task 3: Booking-modified events, notifications, event logging, and analytics utilization

A successful modify that changed something publishes one `booking-modified`
event after the save. `interval()` and `attendees()` on that event are the
new values. The event also carries the previous interval and previous
attendees for the notification. A no-op publishes nothing and writes no
outbox message. The owner is notified with both the old and the new interval
and both attendee counts. Someone promoted by the modify gets the existing
`waitlist-promoted` notice, not the modify notice. A failing subscriber does
not undo the stored booking.

`EventLogFormat.toLine` writes the new start, end, and attendees because
those are the `DomainEvent` fields. `eventTypes()` includes
`booking-modified`. The analytics fold, in `build_ledger`, updates the
existing record's start, end, and attendees in place. It does not open a new
booking, does not clear `cancelled_at`, and does not clear `from_waitlist`.
`HOLDING_EVENT_TYPES` stays `booking-created` and `waitlist-promoted`.
Utilization already sums `duration_minutes` of that folded record, and there
is still no interval arithmetic on the Python side, so a shortened booking
counts once, at its new length. Revenue keeps reading that same ledger.
`revenue.py` grows no special case and does not drop the booking. A
`booking-modified` line whose booking was never opened is recorded and does
not become a held booking, the same way an orphan cancellation is recorded.

The two event-type lists in `AGENTS.md` and `ARCHITECTURE.md` stay stale
until Task 4, which corrects every statement change-request item 9 names.

**Modules.** `core-domain` (`BookingModifiedEvent`, the `DomainEvent` permit,
the publish from `BookingService`), `storage` (`EventLogFormat` and a new
test), `notifications` (a kind, both exhaustive switches, one template, a new
test), and `analytics` (`events.py`, new tests in `tests/`). Not `revenue.py`'s
pricing logic, and not `utilization.py` unless a folded duration is being
ignored. Not `cli`: `export-events` already writes whatever
`EventLogFormat` emits. Not the convention files.

**Depends on.** Task 1. Run it after Task 2; both edit `BookingService`.

**Checkpoint.** All of these exit 0, and each touched module's count rises
(storage above 63, notifications above 41, pytest above 137, core-domain
above Task 2):

- `mvn -pl storage -am test`: a new storage test shows `eventTypes()` and
  `toLine` for a real modify include `booking-modified` and the new start,
  end, and attendees. A new core-domain test, which this gate also runs,
  shows a no-op appends no event.
- `mvn -pl notifications -am test`: the owner's message body contains the old
  interval, the new interval, the old attendee count, and the new one. A
  promoted waiter still gets `waitlist-promoted`.
- `pytest` from `analytics/`: a created booking that is then shortened
  contributes its new length only; a promoted booking that is then modified
  stays `from_waitlist` and still counts once at the new length; revenue
  still includes that booking.
- `mvn -B test`, because the new sealed permit is a cross-module compile.

**Green while wrong.** The revenue check only requires the booking to appear.
`revenue.py` can price the pre-change interval from its own reading while
utilization uses the updated record. Both gates stay green, and the shared
fold has been forked.

### Task 4: HTTP PATCH endpoint, booking JSON history, and documentation

`PATCH /bookings/{bookingId}` modifies the caller's own booking. The body is
form fields, like `POST /bookings`: `start` plus `end` or `minutes`, and
`attendees`. An omitted interval keeps the stored interval. An omitted
attendee count keeps the stored count. A supplied `roomId` that differs from
the booking's room is a 400 from the handler. The domain method is not given
a room. The caller is the token's user, as on `DELETE`. An administrator who
does not own the booking gets 403 (`NOT_BOOKING_OWNER`) and the booking is
unchanged. A conflict comes back 409 through the existing `ErrorMapping`.
No new rejection code is added there. The response, like cancel, carries the
booking and any promotions. `GET /bookings/{bookingId}` and the bookings list
gain a `history` array on the booking object they already return: each entry
has `at`, `start`, `end`, and `attendees` of the previous values, oldest
first. There is no new path. `Router` gains `patch` beside `post` and
`delete`.

Documentation in the same task, the three statements item 9 names:
`AGENTS.md` rule 6's event-type list, `ARCHITECTURE.md`'s event-type
paragraph, and `ARCHITECTURE.md`'s HTTP table (`PATCH /bookings/{bookingId}`,
owner).

**Modules.** `http-api` (`Router`, `BookingsHandler`, `JsonViews`, and a new
test), plus `AGENTS.md` and `ARCHITECTURE.md`. No domain rule changes.

**Depends on.** Tasks 1, 2, and 3.

**Checkpoint.** `mvn -pl http-api -am test` exits 0, http-api's count is
above 78 with 0 failures and 0 errors, and a new test shows: the owner's
form PATCH shortens the booking and the following GET returns the new times
plus one history entry of the old start, end, and attendees; a second user's
PATCH returns 403 and the following GET is unchanged; a PATCH into a taken
slot returns 409 and the GET is unchanged; a shorten that frees a waited-for
slot returns that promotion. Then read the three doc statements and confirm
each names `booking-modified` or the PATCH row. `mvn -B clean test` exits 0
afterward, because this task is the last one and CI builds cold.

**Green while wrong.** The second caller in the test is an ordinary member.
An administrator who does not own the booking is still accepted, and the
gate stays green.

### AGENTS.md rules 3 and 4

Task 1's self-nudge and half-open cases fail a rule 3 predicate that disagrees with `TimeInterval.overlaps`; Task 2's FIFO order, skipped double-booked waiter, lengthen pass, and attendee-only case fail a rule 4 promotion that is not first-in-first-out, that double-books, or that runs when only the attendee count changed.

### Milestone 3 selection

Run Task 1. Its dependency list is empty, and it does not change sealed `DomainEvent`, so Task 3's notifications and storage work is not required for the cold build to compile.

### Out of scope

- `billing`. Change request: "The price does not change. Billing is not part
  of this change request. Do not re-price, re-issue, or void and re-invoice."
  The invoice already keeps the `PriceQuote` it was issued with. `Booking`
  has no price.
- `accounts`. Change request: "Nothing in account management. Modification
  uses the token and ownership checks that already exist."
- `cli`. Change request: "Nothing in the CLI. The administrative commands
  render fields that already exist and do not need a new command."
- Revenue behavior in `analytics`. Change request: "The revenue report is
  not part of this change. ... Do not filter modified bookings out of it
  either, and do not fork the reading to keep them apart." Utilization is
  the only report in scope. Both reports keep reading `build_ledger`.
- A room change. Change request: "The room does not change." The domain
  method takes no room id.
- A new Maven dependency. `AGENTS.md` rule 1: the edges listed there are the
  only edges. This change needs none.
- A second overlap implementation, a waitlist writer outside
  `BookingService`, a booking path that fails when notification fails, or a
  JSON library. `AGENTS.md` rules 3, 4, 5, and 6.

---

## Milestone 2: The guardrail

Pick one recipe from `guardrails/README.md`. Configure it, then prove it binds.

**The recipe.** Which one you picked, the exact paths or trees it covers, and
one line on what it cannot catch.

**The captured block.** Paste the excerpt of the agent's own turn showing what
you asked it to do, its tool call, and the block the guardrail returned. A bare
terminal paste with no agent turn around it does not show the boundary binding.

---

## Milestone 3: The run

Run exactly ONE task from your plan with the agent, under the guardrail, through
its gate. Any task whose dependencies are done qualifies, including the
smallest one.

**The takeover point.** Write this before you start the run. A concrete,
conditional statement of when you would stop the agent and write the code
yourself. "If something looks wrong" does not count. Name the trigger and name
what you would do.

**The evidence.** Name the task from Milestone 1. Paste the result line of its
gate command (run it without `-q` so the line is in the output) and the result
line of one `mvn -B clean test` run after the task was finished. The module
gate cannot see a cross-module compile break on a warm tree. A cold build can,
and so can CI.

**The diff.** The files the agent changed, and how each one maps to that task's
declared scope (plain `git diff` leaves out new files, but
`git add -N . && git diff --stat` includes them). Note anything it touched that
you did not expect, including any shipped test. Then quote the one line you
read hardest and say what you were checking for.

**The refuter.** What you gave it and what you asked it to do (paste the
brief). Then the one finding you settled, and how (the new failing test with
its red run, or the evidence that dismissed it). If nothing it reported held
up, say how you checked its strongest claim.
