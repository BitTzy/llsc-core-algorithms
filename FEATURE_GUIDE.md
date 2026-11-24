# Circle update: walkthrough and scope

## Demo with two account tabs

1. Ana creates a circle. Ben has an account but sees no membership yet.
2. Ana selects Invite member and enters Ben's registered email.
3. Ben opens Invitations and accepts. A declined invitation grants no new access.
4. Ben submits a PHP 500 contribution dated today. It appears as Pending review. Balance stays unchanged.
5. Ana selects that row and approves. Balance increases by PHP 500. The original actual/submission dates stay unchanged.
6. For another submission, Ana rejects with a reason. Balance stays unchanged and Ben can read the reason.
7. Ben can withdraw a pending entry, then leave the circle. Historic entries and balances stay intact; no refund is created.
8. Ana closes the circle once no reviews remain. All history stays available.

## Design decisions

- Entries are claims about money exchanged outside the app, not transfers.
- Only an active member can submit their own contribution. Only the organiser can review it.
- Organiser entries remain direct approved records, matching the existing organiser workflow.
- Pending, rejected and withdrawn submissions do not affect the recorded balance.
- Pending entries suppress contribution reminders in the same way as recorded contributions, so the leader's delay does not generate a duplicate payment prompt.
- Leaving stops contribution reminders and new submissions but retains shared read-only history.
- Closing blocks new entries/invitations and cancels pending invitations. Pending reviews must be resolved first.
- Loan return reliability is unchanged. No separate savings punctuality score is implemented.

## Recommended next features (not implemented)

1. Explicit scheduled-period allocation for early/late and partial contributions.
2. A review queue age indicator and organiser review reminders.
3. Linked corrections rather than deleting rejected records.
4. Exportable circle statements; optional contribution evidence.
5. Invitation expiry and organiser cancellation, beyond cancellation on circle closure.

## Presentation

The accompanying revised proposal uses the actual Java 21 Swing / Spring JDBC / H2 architecture. Its figures describe the revised source in this release; suggested future features are labelled separately. The original PDF was the only supplied presentation source, so the editable deck was rebuilt using its UE background.
