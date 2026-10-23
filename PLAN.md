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

**The recipe.** Recipe C, filesystem lockdown, activated with
`guardrails/lockdown.sh` after `guardrails/lockdown.sh --list`.
`guardrails/protected-trees.txt` lists `storage`, `notifications`,
`accounts`, `billing`, `http-api`, and `cli`. The script made each of those
`src/` trees and each `pom.xml` read-only. `core-domain` was not listed and
stays writable. It cannot catch a bad edit in a tree left open, including
`core-domain`, and a shell `chmod` can put write permission back.

**The captured block.** The ask, the edit, and the refusal are in this chat,
in the turn that said to add one comment at the top of `Money.java` and to
stop if the edit was blocked. That turn is after lockdown and before any
implementation. `Money.java` still matches `HEAD` (`4258437a3b1ab6abd15be8692d1a0dd69ff231e4`).

Asked: use the Edit tool to add a single comment at the top of
`billing/src/main/java/edu/cmu/cs214/campusreserve/billing/Money.java`.

Tool call: `StrReplace` on that path. The old text was
`package edu.cmu.cs214.campusreserve.billing;` and the new text was a
`// guardrail probe` comment directly above that same package line.

Returned: `Write permission denied: /Users/jkp11/f26-lab08/billing/src/main/java/edu/cmu/cs214/campusreserve/billing/Money.java: Permission denied`

No second method was tried. Permissions were not changed.

---

## Milestone 3: The run

Task 1 has been run. The scope, gate, and takeover point below are the
pre-run record and are unchanged. The refuter has been run. Its strongest
finding is dismissed for Task 1: waitlist promotion belongs to Task 2,
and this lab does not implement Task 2.

**Task.** Task 1 from Milestone 1: core-domain booking modification and
history. An owner can shorten, lengthen, or move an upcoming booking, or
change its attendee count, in the same room. The id, room, user, status, and
`createdAt` stay. A fifteen-minute nudge succeeds. A half-open neighbor, in
the same room or on the booker's other room, does not collide. A collision or
a broken request rule leaves the stored booking identical, including history.
A booker already at `maxActiveBookingsPerUser` can still modify. A no-op
appends nothing. A real change appends one `BookingRevision` of the previous
interval and attendees. Cancelling afterward keeps that history.

**Scope.** `core-domain` only: `Booking`, `BookingRevision`,
`ModificationResult`, `BookingService`, and a new test under
`core-domain/src/test`. `Booking.confirmed`'s signature stays. `DomainEvent`
and its permits clause stay. No other module.

**Depends on.** Nothing.

**Gate.** `mvn -pl core-domain -am test` from the repository root, without
`-q` when the result line is recorded. Done when it exits 0, core-domain's
`Tests run` line is above 167 with 0 failures and 0 errors, and the new test
shows the self-nudge and both half-open cases; `ROOM_UNAVAILABLE` and
`USER_DOUBLE_BOOKED` rejections whose reloaded booking still has the original
interval, attendees, and history; a modify that succeeds at the booking cap;
non-owner, cancelled, started, and not-found rejections; one rule rejection
(attendees above room capacity, or a too-short interval) that leaves the
original; a no-op whose history length is unchanged; and a real change whose
single revision holds the previous interval and attendees.

**Guardrail.** Still active at preparation time. `Money.java` is not
writable, and its blob equals the `HEAD` blob above. `Booking.java` is
writable. The lock was not cleared.

**Plan commit.** Milestone 1 is `09696eadf6d1e30582683d7f33a9316ed56cef83`
("Record the booking-modification plan before implementation."). That commit
is still `HEAD`. The Task 1 sources are uncommitted changes on top of it.

**The takeover point.** Stop the agent at the first of these, leave the lock
in place, and write the rest of Task 1 in `core-domain` by hand:

- It edits any path outside Task 1's scope, including another module,
  `DomainEvent`, or `Booking.confirmed`'s parameter list. Revert that edit
  and continue only inside the files named above.
- It modifies a shipped test. Restore that file from `HEAD` and add a new
  test beside it instead.
- It bypasses the guardrail (`chmod`, `guardrails/unlock.sh`, or an edit to
  `guardrails/protected-trees.txt`). Discard the out-of-scope result and
  re-lock with `guardrails/lockdown.sh`. Do not unlock to finish the task.
- It violates rule 3 or rule 4: a start/end comparison outside
  `TimeInterval.overlaps`, or a production `WaitlistStore.save` or
  `WaitlistStore.remove` outside `BookingService`. Replace that code with a
  `ConflictChecker` call, or delete the waitlist write. Task 1 does not
  promote.
- The core-domain gate fails a second time and the failing assertion still
  has no named cause. Stop retrying and fix that assertion by hand.

**The evidence.** Task 1. Gate, without `-q`:

```
mvn -pl core-domain -am test
Tests run: 182, Failures: 0, Errors: 0, Skipped: 0
```

Closing cold build, run again outside the sandbox at 2026-10-23T01:01:52-04:00:

```
mvn -B clean test
BUILD SUCCESS
```

Every reactor module was `SUCCESS`. Counts from that run, each with 0
failures and 0 errors: core-domain 182, storage 63, notifications 41,
accounts 19, billing 19, http-api 78, cli 31. Java total 433, which is the
starter 418 plus the 15 new modify tests. `git diff --check` exited 0.

The filesystem guardrail was still active after that build. `Money.java` was
not writable. `Booking.java` was. The lock was not cleared.

**The diff.** `git add -N . && git diff --stat` lists seven paths. Against
Task 1's declared scope:

- `core-domain/.../domain/Booking.java` (27). In scope. Adds `history()` and
  `revised`. `Booking.confirmed` keeps its six-argument signature and starts
  from an empty history. `cancelled()` copies the list.
- `core-domain/.../domain/BookingRevision.java` (25, new). In scope. Holds
  `at`, `previousInterval`, and `previousAttendees`.
- `core-domain/.../domain/ModificationResult.java` (53, new). In scope. The
  sealed `Modified` / `Rejected` result. Promotions are empty.
- `core-domain/.../service/BookingService.java` (59). In scope. Adds
  `modifyBooking` only. No new event, and `promoteWaitlist` is not called.
- `core-domain/.../service/BookingServiceModifyTest.java` (219, new). In
  scope. The new test. No shipped test differs from `HEAD`.

`PLAN.md` is this lab record. `guardrails/protected-trees.txt` is the
Recipe C list. Neither is product code. No file under `storage`,
`notifications`, `accounts`, `billing`, `http-api`, `cli`, or `analytics`
differs from `HEAD`.

The line read hardest, in `BookingService.java`:

```
if (conflictChecker.hasConflict(except(bookings.findActiveByRoom(booking.roomId()), booking.id()), interval)) {
```

Excluding that id is required because `findActiveByRoom` still contains the
booking being modified, and a fifteen-minute nudge overlaps that booking's
own current interval. Leaving it in the collection would make
`ConflictChecker` report `ROOM_UNAVAILABLE` for a legal move. The same
exclusion is applied to the booker's other active bookings, or the booking
would look double-booked against itself. `except` compares ids only. The
overlap decision stays in `TimeInterval.overlaps`, so a neighbor that merely
touches at the endpoint still does not collide.

**The refuter.** A fresh-context refuter was given `REFUTER-BRIEF.txt` and
told to use only that file. The ask was to read the change request, the Task 1
scope line, and the Task 1 diff, then find concrete inputs where this task
behaves incorrectly. The brief said a missing product surface that the scope
line does not assign is not a finding, and it said not to modify files. The
scope line in the brief is Task 1's module sentence from Milestone 1. The
brief then pasted `CHANGE-REQUEST.md` and the Task 1 diff. The original brief
is copied below. `REFUTER-BRIEF.txt` was removed after this section was written.

**Strongest finding.** Modifying a booking's interval does not trigger
waitlist promotion. `BookingService.modifyBooking` saves the revised booking
and returns `new ModificationResult.Modified(revised, List.of())`.
`promoteWaitlist` is called only from `cancelBooking`. The proposed sequence:
`u-1` holds `r-1` for 10:00–11:00, `u-2` and `u-3` are waitlisted for that
slot, and `u-1` moves the booking to 14:00–15:00. Expected against the whole
change request: `u-2` is promoted into 10:00–11:00 and `u-3` stays queued.
Predicted actual: the move and the history entry succeed, `promotions` is
empty, and both waiters stay queued. A shorten of 10:00–12:00 to 10:00–11:00
with `u-2` waiting for 11:00–12:00 was the same gap. The refuter treated a
throw on non-positive attendees as a precondition, not a product-rule defect,
and reported no other defect.

**Disposition.** Dismissed for Task 1. The finding is valid against the
complete change request. It is outside Task 1's declared scope. No failing
test was added. Task 1's source and tests were not changed.

The binding split is commit `09696eadf6d1e30582683d7f33a9316ed56cef83`
("Record the booking-modification plan before implementation."), which is
still `HEAD`. Its Milestone 1 text is unchanged in this file.

- Lines 56–58: "Promotion, when Task 2 adds it, calls the existing private
  `promoteWaitlist` in `BookingService`. `promotions` is empty until that
  task."
- Lines 61–90, Task 1 ("Core-domain booking modification and history"): the
  nudge, both half-open neighbors, collision and rule rejections, the booking
  cap, the no-op, and one history revision. The checkpoint does not require a
  promoted waiter.
- Lines 96–121, Task 2 ("Waitlist promotion on interval changes"): a
  successful modify that changed the interval runs the same FIFO pass
  `cancelBooking` runs, including the skipped double-booked waiter, the
  lengthen pass, and the attendee-only case that does not start the pass.
  Task 2 depends on Task 1. This lab does not require Task 2.
- Lines 231–233 select Task 1 as the Milestone 3 run.
- The pre-run takeover in this section already says "Task 1 does not
  promote."

`CHANGE-REQUEST.md` lines 40–46 (item 4, "Freed time goes to the waitlist")
and lines 92–93 ("Someone on the waitlist for the time that was freed gets
that time.") are the whole-product requirement the refuter applied. Milestone
1 assigns that requirement to Task 2. The brief's own rule, that a surface
the Task 1 scope line does not assign is not a Task 1 finding, points the
same way: the scope line names the files, and the committed plan says
`promotions` stays empty until Task 2.

The gate line (`Tests run: 182, Failures: 0, Errors: 0, Skipped: 0`) and the
closing `mvn -B clean test` (`BUILD SUCCESS`) above are unchanged. No shipped
test differs from `HEAD`. The only new test is
`BookingServiceModifyTest.java`. No source file outside `core-domain` differs
from `HEAD`.

Original brief:

```text
You are an independent refuter for a single implementation task. Do not modify any file. Do not fix the implementation. Do not apply a patch.

Read the change request, the task scope line, and the diff. Identify concrete inputs or call sequences where this task behaves incorrectly. Focus on edge cases, boundary conditions, multiple modifications, history, conflict checking, and rejection atomicity.

Rank every finding from most serious to least serious. For each finding, give a reproducible test case: the call sequence, the inputs, and the incorrect result that would be observed. If you find nothing, say so and name the strongest case you checked.

A missing product surface that the scope line does not assign to this task is not a finding.

===== CHANGE REQUEST =====
# Change request: booking modification

From: Product, CampusReserve
Status: approved for the next release

## Why

Bookers cannot change a booking they already hold. Today they cancel and rebook,
which loses their slot to whoever is waiting and generates a support ticket
roughly once a day. We want them to be able to adjust a booking in place.

## What we want

A booker may change the time interval, the attendee count, or both, on an
upcoming booking they own. The room stays the same.

The full behavior we are asking for:

1. **Ownership and timing.** Only the booker may modify their own booking, and
   only while the booking is still upcoming. A booking that has started or that
   has been cancelled cannot be modified.
2. **Conflict re-check.** A modification re-runs the room conflict check against
   the new interval. If the new interval collides with another booking, the
   modification is rejected and the original booking stands unchanged. A
   rejected modification changes nothing at all. The check ignores the booking
   being modified. A booking does not collide with itself, so nudging a booking
   by fifteen minutes, one slot, has to be allowed. The booker's own calendar is
   checked the same way, ignoring the booking being modified, so a booker still
   cannot end up holding two rooms at once. The cap on how many bookings a person
   may hold does not come into it. The booking already has its place in that
   count.
3. **The rules still apply.** A modified booking has to satisfy every rule in the
   booking request check, and that list is the whole list: the minimum and
   maximum duration, slot alignment, opening hours, staying inside one UTC day,
   the room's capacity against the new attendee count, the advance window,
   starting in the future rather than the past, and the room still being in
   service. The room conflict re-check and the booker's own calendar are clause
   2's business, and the cap on bookings held stays waived
   there.
4. **Freed time goes to the waitlist.** Time that a successful modification
   frees up triggers the same first in, first out waitlist promotion that a
   cancellation triggers today, with the same no-double-booking re-check. The
   promotion pass runs when a successful modification changed the interval. A
   change to the attendee count alone frees no time and starts no promotion. A
   modification that only lengthens a booking frees nothing either. Running the
   pass and promoting nobody is the right outcome there.
5. **History.** The booking retains its modification history: what changed and
   when. A booker or an administrator can see what a booking used to be, on the
   read surface the booking already has, without a new endpoint to ask for it.
   A modification that changes nothing succeeds and records nothing at all (no
   history entry, no notification to the owner, and no event in the log).
6. **Notification.** The owner gets a notification carrying both the old details
   and the new ones. Anyone promoted off the waitlist gets the promotion notice
   that already exists.
7. **HTTP.** The change is exposed as `PATCH` on the booking resource,
   owner-only, alongside the endpoints that already exist.
8. **Event log.** A `booking-modified` event goes in the event log. The
   utilization report must count a booking whose interval changed at its
   current length only.
9. **The conventions files stay true.** `AGENTS.md` and `ARCHITECTURE.md` both
   promise to describe the platform as it is, but this change makes three of
   their statements false (the event-type list in each file, and the table of the
   HTTP surface in `ARCHITECTURE.md`, which will be missing the new endpoint).
   Correcting them is part of this change.

## What is explicitly out of scope

These are decisions. Do not do them.

- **The price does not change.** A modified booking keeps the price it was
  booked at. Billing is not part of this change request. Do not re-price,
  re-issue, or void and re-invoice.
- **The room does not change.** Moving a booking to a different room is a
  separate request that we have not approved.
- **Nothing in the CLI.** The administrative commands render fields that already
  exist and do not need a new command.
- **Nothing in account management.** Modification uses the token and ownership
  checks that already exist. Accounts does not change.
- **The revenue report is not part of this change.** It prices time from the
  event stream, so after a modification it can disagree with the invoice we
  actually issued, which the price rule above freezes. We accept that for this
  release. Do not filter modified bookings out of it either, and do
  not fork the reading to keep them apart. The ledger learns the new event once,
  and both reports go on reading the same fold of it. The utilization report is
  the only report in scope.

## How we will know it works

- A booker can shorten, lengthen, or move a booking within the same room and see
  the new times.
- A modification into a taken slot is refused and the original booking survives
  intact.
- Someone on the waitlist for the time that was freed gets that time.
- The owner's notification names both the old and the new details. There is no
  HTTP surface on the outbox, so a notifications test is the evidence for this
  one.
- The utilization report over a window containing a modified booking reports its
  current length.

===== TASK 1 SCOPE =====
`core-domain` only (`Booking`, `BookingRevision`, `ModificationResult`, `BookingService`, and a new test under `core-domain/src/test`). `Booking.confirmed`'s signature stays. `DomainEvent` and its permits clause stay. No other module.

===== TASK 1 DIFF =====
diff --git a/core-domain/src/main/java/edu/cmu/cs214/campusreserve/core/domain/Booking.java b/core-domain/src/main/java/edu/cmu/cs214/campusreserve/core/domain/Booking.java
index 5889591..80587a9 100644
--- a/core-domain/src/main/java/edu/cmu/cs214/campusreserve/core/domain/Booking.java
+++ b/core-domain/src/main/java/edu/cmu/cs214/campusreserve/core/domain/Booking.java
@@ -1,6 +1,8 @@
 package edu.cmu.cs214.campusreserve.core.domain;

 import java.time.Instant;
+import java.util.ArrayList;
+import java.util.List;
 import java.util.Objects;

 /**
@@ -8,6 +10,8 @@ import java.util.Objects;
  *
  * <p>Bookings reference the room and the user by id rather than by object, so a
  * stored booking stays valid when a room is renamed or taken out of service.
+ * {@code history} is the previous intervals and attendee counts, oldest first.
+ * The fields on the booking itself are the current ones.
  */
 public record Booking(
     String id,
@@ -16,7 +20,8 @@ public record Booking(
     TimeInterval interval,
     int attendees,
     BookingStatus status,
-    Instant createdAt) {
+    Instant createdAt,
+    List<BookingRevision> history) {

     public Booking {
         id = Identifiers.require(id, "booking id");
@@ -25,20 +30,32 @@ public record Booking(
         Objects.requireNonNull(interval, "interval");
         Objects.requireNonNull(status, "status");
         Objects.requireNonNull(createdAt, "createdAt");
+        history = List.copyOf(Objects.requireNonNull(history, "history"));
         if (attendees <= 0) {
             throw new IllegalArgumentException("Attendees must be positive: " + attendees);
         }
     }

-    /** Builds a confirmed booking. */
+    /** Builds a confirmed booking with an empty history. */
     public static Booking confirmed(
         String id, String roomId, String userId, TimeInterval interval, int attendees, Instant createdAt) {
-        return new Booking(id, roomId, userId, interval, attendees, BookingStatus.CONFIRMED, createdAt);
+        return new Booking(id, roomId, userId, interval, attendees, BookingStatus.CONFIRMED, createdAt, List.of());
     }

-    /** Returns a cancelled copy of this booking. */
+    /** Returns a cancelled copy of this booking, history included. */
     public Booking cancelled() {
-        return new Booking(id, roomId, userId, interval, attendees, BookingStatus.CANCELLED, createdAt);
+        return new Booking(id, roomId, userId, interval, attendees, BookingStatus.CANCELLED, createdAt, history);
+    }
+
+    /**
+     * A copy that holds {@code interval} and {@code attendees} now, and records
+     * the values they replaced.
+     */
+    public Booking revised(TimeInterval interval, int attendees, Instant at) {
+        List<BookingRevision> next = new ArrayList<>(history.size() + 1);
+        next.addAll(history);
+        next.add(new BookingRevision(at, this.interval, this.attendees));
+        return new Booking(id, roomId, userId, interval, attendees, status, createdAt, next);
     }

     /** Does this booking still hold the room? */
diff --git a/core-domain/src/main/java/edu/cmu/cs214/campusreserve/core/domain/BookingRevision.java b/core-domain/src/main/java/edu/cmu/cs214/campusreserve/core/domain/BookingRevision.java
new file mode 100644
index 0000000..a4d1bf8
--- /dev/null
+++ b/core-domain/src/main/java/edu/cmu/cs214/campusreserve/core/domain/BookingRevision.java
@@ -0,0 +1,25 @@
+package edu.cmu.cs214.campusreserve.core.domain;
+
+import java.time.Instant;
+import java.util.Objects;
+
+/**
+ * One recorded change to a booking: what it used to be, and when it changed.
+ *
+ * <p>The booking's own interval and attendees are the current values. This
+ * record holds the values they replaced. {@code at} is a whole minute in UTC,
+ * the same clock the service uses for every other instant.
+ */
+public record BookingRevision(Instant at, TimeInterval previousInterval, int previousAttendees) {
+
+    public BookingRevision {
+        Objects.requireNonNull(at, "at");
+        Objects.requireNonNull(previousInterval, "previousInterval");
+        if (at.getNano() != 0 || at.getEpochSecond() % 60 != 0) {
+            throw new IllegalArgumentException("Revision time must land on a whole minute: " + at);
+        }
+        if (previousAttendees <= 0) {
+            throw new IllegalArgumentException("Previous attendees must be positive: " + previousAttendees);
+        }
+    }
+}
diff --git a/core-domain/src/main/java/edu/cmu/cs214/campusreserve/core/domain/ModificationResult.java b/core-domain/src/main/java/edu/cmu/cs214/campusreserve/core/domain/ModificationResult.java
new file mode 100644
index 0000000..188b25b
--- /dev/null
+++ b/core-domain/src/main/java/edu/cmu/cs214/campusreserve/core/domain/ModificationResult.java
@@ -0,0 +1,53 @@
+package edu.cmu.cs214.campusreserve.core.domain;
+
+import java.util.List;
+import java.util.Objects;
+import java.util.Optional;
+
+/**
+ * What came of a modification. A successful one reports the booking as it is
+ * now. Promotions stay empty until a later change runs the waitlist pass.
+ */
+public sealed interface ModificationResult {
+
+    /** The booking was updated. {@code promotions} may be empty. */
+    record Modified(Booking booking, List<CancellationResult.Promotion> promotions) implements ModificationResult {
+        public Modified {
+            Objects.requireNonNull(booking, "booking");
+            promotions = List.copyOf(promotions);
+        }
+    }
+
+    /** The modification was refused. Nothing was stored. */
+    record Rejected(RejectionReason reason, String detail) implements ModificationResult {
+        public Rejected {
+            Objects.requireNonNull(reason, "reason");
+            detail = detail == null ? reason.message() : detail;
+        }
+    }
+
+    /** Builds a rejection carrying the reason's own message. */
+    static ModificationResult rejected(RejectionReason reason) {
+        return new Rejected(reason, reason.message());
+    }
+
+    /** Did the modification go through? */
+    default boolean isModified() {
+        return this instanceof Modified;
+    }
+
+    /** The booking as stored, when the modification went through. */
+    default Optional<Booking> modifiedBooking() {
+        return this instanceof Modified modified ? Optional.of(modified.booking()) : Optional.empty();
+    }
+
+    /** Promotions triggered by the modification, empty when it was refused. */
+    default List<CancellationResult.Promotion> promotions() {
+        return this instanceof Modified modified ? modified.promotions() : List.of();
+    }
+
+    /** The reason, when the modification was refused. */
+    default Optional<RejectionReason> rejectionReason() {
+        return this instanceof Rejected rejected ? Optional.of(rejected.reason()) : Optional.empty();
+    }
+}
diff --git a/core-domain/src/main/java/edu/cmu/cs214/campusreserve/core/service/BookingService.java b/core-domain/src/main/java/edu/cmu/cs214/campusreserve/core/service/BookingService.java
index 47113a6..a80f7ec 100644
--- a/core-domain/src/main/java/edu/cmu/cs214/campusreserve/core/service/BookingService.java
+++ b/core-domain/src/main/java/edu/cmu/cs214/campusreserve/core/service/BookingService.java
@@ -4,6 +4,7 @@ import edu.cmu.cs214.campusreserve.core.domain.Booking;
 import edu.cmu.cs214.campusreserve.core.domain.BookingOutcome;
 import edu.cmu.cs214.campusreserve.core.domain.BookingRequest;
 import edu.cmu.cs214.campusreserve.core.domain.CancellationResult;
+import edu.cmu.cs214.campusreserve.core.domain.ModificationResult;
 import edu.cmu.cs214.campusreserve.core.domain.RejectionReason;
 import edu.cmu.cs214.campusreserve.core.domain.Room;
 import edu.cmu.cs214.campusreserve.core.domain.TimeInterval;
@@ -151,6 +152,59 @@ public final class BookingService {
         return new CancellationResult.Cancelled(cancelled, promotions);
     }

+    /**
+     * Changes the interval, the attendee count, or both, on a booking the caller
+     * owns. The room stays the one the booking already holds.
+     *
+     * <p>The order is fixed: the booking has to exist, the caller has to be
+     * allowed to cancel it (owner, still holding the room, still upcoming), and
+     * an ask that changes nothing returns the stored booking with its history
+     * untouched. Anything else re-runs the booking rules, then the room conflict
+     * check and the booker's own calendar, both with this booking left out. The
+     * cap on how many bookings a person may hold is not applied. A refusal
+     * stores nothing. The waitlist is not walked.
+     */
+    public ModificationResult modifyBooking(
+        String bookingId, String requesterId, TimeInterval interval, int attendees) {
+        Objects.requireNonNull(bookingId, "bookingId");
+        Objects.requireNonNull(requesterId, "requesterId");
+        Objects.requireNonNull(interval, "interval");
+
+        Optional<Booking> stored = bookings.findById(bookingId);
+        if (stored.isEmpty()) {
+            return ModificationResult.rejected(RejectionReason.BOOKING_NOT_FOUND);
+        }
+        Booking booking = stored.get();
+
+        Optional<RejectionReason> failure = rules.checkCancellable(booking, requesterId);
+        if (failure.isPresent()) {
+            return ModificationResult.rejected(failure.get());
+        }
+        if (booking.interval().equals(interval) && booking.attendees() == attendees) {
+            return new ModificationResult.Modified(booking, List.of());
+        }
+
+        Optional<Room> room = rooms.findById(booking.roomId());
+        if (room.isEmpty()) {
+            return ModificationResult.rejected(RejectionReason.ROOM_NOT_FOUND);
+        }
+        BookingRequest request = BookingRequest.of(booking.roomId(), booking.userId(), interval, attendees);
+        Optional<RejectionReason> ruleFailure = rules.checkRequest(request, room.get());
+        if (ruleFailure.isPresent()) {
+            return ModificationResult.rejected(ruleFailure.get());
+        }
+        if (conflictChecker.hasConflict(except(bookings.findActiveByRoom(booking.roomId()), booking.id()), interval)) {
+            return ModificationResult.rejected(RejectionReason.ROOM_UNAVAILABLE);
+        }
+        if (conflictChecker.hasConflict(except(bookings.findActiveByUser(booking.userId()), booking.id()), interval)) {
+            return ModificationResult.rejected(RejectionReason.USER_DOUBLE_BOOKED);
+        }
+
+        Booking revised = booking.revised(interval, attendees, now());
+        bookings.save(revised);
+        return new ModificationResult.Modified(revised, List.of());
+    }
+
     /**
      * Drops the asker's own waitlist entry. This is the only other place waitlist
      * rows are removed, and it lives here for the same reason promotion does.
@@ -280,6 +334,11 @@ public final class BookingService {
         return Optional.empty();
     }

+    /** The active bookings in {@code existing} other than {@code bookingId}. */
+    private static List<Booking> except(List<Booking> existing, String bookingId) {
+        return existing.stream().filter(booking -> !booking.id().equals(bookingId)).toList();
+    }
+
     /** Does the user already hold a booking over this interval, in any room? */
     private boolean hasOwnOverlap(String userId, TimeInterval interval) {
         return conflictChecker.hasConflict(bookings.findActiveByUser(userId), interval);
diff --git a/core-domain/src/test/java/edu/cmu/cs214/campusreserve/core/service/BookingServiceModifyTest.java b/core-domain/src/test/java/edu/cmu/cs214/campusreserve/core/service/BookingServiceModifyTest.java
new file mode 100644
index 0000000..acd5530
--- /dev/null
+++ b/core-domain/src/test/java/edu/cmu/cs214/campusreserve/core/service/BookingServiceModifyTest.java
@@ -0,0 +1,219 @@
+package edu.cmu.cs214.campusreserve.core.service;
+
+import static org.junit.jupiter.api.Assertions.assertEquals;
+import static org.junit.jupiter.api.Assertions.assertFalse;
+import static org.junit.jupiter.api.Assertions.assertTrue;
+
+import edu.cmu.cs214.campusreserve.core.domain.Booking;
+import edu.cmu.cs214.campusreserve.core.domain.BookingRevision;
+import edu.cmu.cs214.campusreserve.core.domain.BookingStatus;
+import edu.cmu.cs214.campusreserve.core.domain.ModificationResult;
+import edu.cmu.cs214.campusreserve.core.domain.RejectionReason;
+import edu.cmu.cs214.campusreserve.core.domain.TimeInterval;
+import edu.cmu.cs214.campusreserve.core.policy.BookingPolicy;
+import edu.cmu.cs214.campusreserve.core.support.Fixtures;
+import java.time.Instant;
+import java.util.List;
+import java.util.Optional;
+import org.junit.jupiter.api.Test;
+
+/** In-place changes to a booking the caller already holds. */
+class BookingServiceModifyTest extends BookingServiceTestBase {
+
+    @Test
+    void ownerCanNudgeABookingByFifteenMinutes() {
+        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
+        clock.setTo("2026-10-19T09:01:40Z");
+
+        ModificationResult result = modify(id, "u-1", Fixtures.interval("10:15", "11:15"), 4);
+
+        assertTrue(result.isModified());
+        assertTrue(result.promotions().isEmpty());
+        Booking stored = service.findBooking(id).orElseThrow();
+        assertEquals(id, stored.id());
+        assertEquals("r-1", stored.roomId());
+        assertEquals("u-1", stored.userId());
+        assertEquals(BookingStatus.CONFIRMED, stored.status());
+        assertEquals(Instant.parse(Fixtures.NOW), stored.createdAt());
+        assertEquals(Fixtures.interval("10:15", "11:15"), stored.interval());
+        assertEquals(4, stored.attendees());
+        assertEquals(1, stored.history().size());
+        BookingRevision revision = stored.history().get(0);
+        assertEquals(Instant.parse("2026-10-19T09:01:00Z"), revision.at());
+        assertEquals(Fixtures.interval("10:00", "11:00"), revision.previousInterval());
+        assertEquals(4, revision.previousAttendees());
+        assertEquals(List.of("booking-created"), publisher.types());
+    }
+
+    @Test
+    void aHalfOpenNeighborInTheSameRoomDoesNotCollide() {
+        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("12:00", "13:00"));
+        bookAndGetId("r-1", "u-2", Fixtures.interval("11:00", "12:00"));
+
+        ModificationResult result = modify(id, "u-1", Fixtures.interval("10:00", "11:00"), 4);
+
+        assertTrue(result.isModified());
+        Booking stored = service.findBooking(id).orElseThrow();
+        assertEquals(id, stored.id());
+        assertEquals(Fixtures.interval("10:00", "11:00"), stored.interval());
+    }
+
+    @Test
+    void aHalfOpenBookingOnTheOwnersOtherRoomDoesNotCollide() {
+        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("12:00", "13:00"));
+        bookAndGetId("r-2", "u-1", Fixtures.interval("11:00", "12:00"));
+
+        ModificationResult result = modify(id, "u-1", Fixtures.interval("10:00", "11:00"), 4);
+
+        assertTrue(result.isModified());
+        assertEquals(id, service.findBooking(id).orElseThrow().id());
+        assertEquals(Fixtures.interval("10:00", "11:00"), service.findBooking(id).orElseThrow().interval());
+    }
+
+    @Test
+    void aRoomCollisionLeavesTheStoredBookingUnchanged() {
+        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
+        bookAndGetId("r-1", "u-2", Fixtures.interval("11:00", "12:00"));
+        Booking before = service.findBooking(id).orElseThrow();
+
+        ModificationResult result = modify(id, "u-1", Fixtures.interval("10:30", "11:30"), 4);
+
+        assertEquals(Optional.of(RejectionReason.ROOM_UNAVAILABLE), result.rejectionReason());
+        assertFalse(result.isModified());
+        assertEquals(before, service.findBooking(id).orElseThrow());
+        assertTrue(service.findBooking(id).orElseThrow().history().isEmpty());
+    }
+
+    @Test
+    void theOwnersOtherRoomIsCheckedWithoutCountingThisBooking() {
+        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
+        bookAndGetId("r-2", "u-1", Fixtures.interval("14:00", "15:00"));
+        Booking before = service.findBooking(id).orElseThrow();
+
+        ModificationResult result = modify(id, "u-1", Fixtures.interval("14:00", "15:00"), 4);
+
+        assertEquals(Optional.of(RejectionReason.USER_DOUBLE_BOOKED), result.rejectionReason());
+        assertEquals(before, service.findBooking(id).orElseThrow());
+    }
+
+    @Test
+    void theBookingCapDoesNotBlockAModification() {
+        build(BookingPolicy.defaults().withMaxActiveBookingsPerUser(1));
+        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
+
+        ModificationResult result = modify(id, "u-1", Fixtures.interval("10:15", "11:15"), 4);
+
+        assertTrue(result.isModified());
+        assertEquals(Fixtures.interval("10:15", "11:15"), service.findBooking(id).orElseThrow().interval());
+    }
+
+    @Test
+    void attendeesCanChangeWithoutMovingTheInterval() {
+        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
+        TimeInterval interval = Fixtures.interval("10:00", "11:00");
+
+        ModificationResult result = modify(id, "u-1", interval, 6);
+
+        Booking stored = service.findBooking(id).orElseThrow();
+        assertTrue(result.isModified());
+        assertEquals(interval, stored.interval());
+        assertEquals(6, stored.attendees());
+        assertEquals(1, stored.history().size());
+        assertEquals(interval, stored.history().get(0).previousInterval());
+        assertEquals(4, stored.history().get(0).previousAttendees());
+    }
+
+    @Test
+    void anIdenticalModificationRecordsNothing() {
+        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
+        Booking before = service.findBooking(id).orElseThrow();
+
+        ModificationResult result = modify(id, "u-1", Fixtures.interval("10:00", "11:00"), 4);
+
+        assertTrue(result.isModified());
+        assertEquals(before, result.modifiedBooking().orElseThrow());
+        assertTrue(service.findBooking(id).orElseThrow().history().isEmpty());
+        assertEquals(before, service.findBooking(id).orElseThrow());
+    }
+
+    @Test
+    void rejectsSomeoneElsesBooking() {
+        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
+        Booking before = service.findBooking(id).orElseThrow();
+
+        ModificationResult result = modify(id, "u-2", Fixtures.interval("10:15", "11:15"), 4);
+
+        assertEquals(Optional.of(RejectionReason.NOT_BOOKING_OWNER), result.rejectionReason());
+        assertEquals(before, service.findBooking(id).orElseThrow());
+    }
+
+    @Test
+    void rejectsAnUnknownBooking() {
+        ModificationResult result = modify("bk-missing", "u-1", Fixtures.interval("10:00", "11:00"), 4);
+        assertEquals(Optional.of(RejectionReason.BOOKING_NOT_FOUND), result.rejectionReason());
+    }
+
+    @Test
+    void rejectsACancelledBooking() {
+        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
+        service.cancelBooking(id, "u-1");
+        Booking before = service.findBooking(id).orElseThrow();
+
+        ModificationResult result = modify(id, "u-1", Fixtures.interval("10:15", "11:15"), 4);
+
+        assertEquals(Optional.of(RejectionReason.BOOKING_ALREADY_CANCELLED), result.rejectionReason());
+        assertEquals(before, service.findBooking(id).orElseThrow());
+    }
+
+    @Test
+    void rejectsABookingThatHasStarted() {
+        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
+        clock.setTo("2026-10-20T10:30:00Z");
+        Booking before = service.findBooking(id).orElseThrow();
+
+        ModificationResult result = modify(id, "u-1", Fixtures.interval("12:00", "13:00"), 4);
+
+        assertEquals(Optional.of(RejectionReason.BOOKING_ALREADY_STARTED), result.rejectionReason());
+        assertEquals(before, service.findBooking(id).orElseThrow());
+    }
+
+    @Test
+    void rejectsAPartyTheRoomCannotSeat() {
+        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
+        Booking before = service.findBooking(id).orElseThrow();
+
+        ModificationResult result = modify(id, "u-1", Fixtures.interval("10:00", "11:00"), 11);
+
+        assertEquals(Optional.of(RejectionReason.ATTENDEES_EXCEED_CAPACITY), result.rejectionReason());
+        assertEquals(before, service.findBooking(id).orElseThrow());
+    }
+
+    @Test
+    void rejectsAnIntervalShorterThanTheMinimum() {
+        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
+        Booking before = service.findBooking(id).orElseThrow();
+
+        ModificationResult result = modify(id, "u-1", Fixtures.interval("10:00", "10:15"), 4);
+
+        assertEquals(Optional.of(RejectionReason.INTERVAL_TOO_SHORT), result.rejectionReason());
+        assertEquals(before, service.findBooking(id).orElseThrow());
+    }
+
+    @Test
+    void cancellingKeepsTheRevision() {
+        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
+        modify(id, "u-1", Fixtures.interval("10:15", "11:15"), 4);
+
+        service.cancelBooking(id, "u-1");
+
+        Booking stored = service.findBooking(id).orElseThrow();
+        assertEquals(BookingStatus.CANCELLED, stored.status());
+        assertEquals(1, stored.history().size());
+        assertEquals(Fixtures.interval("10:00", "11:00"), stored.history().get(0).previousInterval());
+        assertEquals(Fixtures.interval("10:15", "11:15"), stored.interval());
+    }
+
+    private ModificationResult modify(String id, String userId, TimeInterval interval, int attendees) {
+        return service.modifyBooking(id, userId, interval, attendees);
+    }
+}
```
