# LENDING LEDGER AND SAVING CIRCLE

## Run on one computer

This Java 21 desktop app uses Swing and the full lending project's embedded H2 database. A standard account can run it from a writable folder such as Documents. The app opens its own window and stores records in `data/lendly.mv.db` inside the working folder. You can use it offline once Java and the packaged JAR are available.

1. Extract the entire ZIP to a writable folder.
2. With Java 21 installed, double-click `run.cmd` on Windows or run `sh run.sh` from the extracted folder on macOS or Linux.
3. Select **Create account** and sign in. Click **New account tab** and sign in with a different account. Switch between the account tabs as each person takes their turn.

You can also start the packaged app from the project folder with:

```sh
java -jar target/lending-ledger-desktop.jar
```

## Classroom demonstration

1. Create accounts for Ana and Ben with different emails and passwords of at least 12 characters that include letters and numbers. Keep both signed in using separate account tabs.
2. In Ben’s tab, choose **Borrowing > Request a loan**. Enter Ana’s registered email, choose **Money**, use `250.00` `PHP`, set the handover date to today and the due date to tomorrow, then acknowledge and save.
3. In Ana’s tab, open **Lending ledger**, select the loan and click **Confirm terms**. Check who is lending, who is borrowing, the PHP 250.00 amount and both dates. Tick the acknowledgement and confirm. Ana then uses **Record handover** once the exchange has happened.
4. In Ben’s **Agreement** tab, choose **Review handover**. Check the record and open the receipt if one was attached. Confirming activates the loan on both tabs.
5. In Ben’s **Agreement** tab, choose **Record repayment** and enter `250.00`. Ana selects **Review repayment** and confirms. Both tabs show **Completed**, and Ben’s Overview shows his updated trust percentage.
6. To reverse roles, have Ana request a different loan from Ben. The same accounts can also track an item such as a charger or umbrella.

Saved changes refresh all signed-in tabs. Open the **Notifications** subtab under **Reminders & trust** to see the other party’s actions. Select a notice and choose **Open loan** or **Mark as read** to clear just that notice. The inbox and badge refresh every five seconds.

While the app runs, the reminder job checks every minute after 09:00 Manila time. Both parties can receive one advance reminder within the three days before the deadline, a due-today reminder, and one reminder per overdue day.

## Calendar reminders

1. Sign in and open **Reminders & trust**.
2. Select **Export calendar (.ics)** to save all active deadlines that fall today or later. Exported events contain the due date and the generic title “Lendly loan due.”
3. On a Mac, open Calendar, choose **File > Import**, and select the file and your iCloud calendar. Check the imported event’s alert settings. The export requests a day-before alert.

Calendar import is manual. If a loan is settled or its date changes, update or remove the imported event in your calendar. In-app reminders require the desktop app to be running.

## Edit and test

Open `pom.xml` as a Java 21 Maven project in NetBeans or IntelliJ IDEA. In NetBeans, **Run Project** uses the included Maven configuration. In IntelliJ, run `ph.edu.lendly.DesktopApplication` or use the Maven command below.

```sh
mvn package
mvn spring-boot:run
```

To put the source on GitHub, commit the extracted files including `.github`. GitHub Actions runs the service tests and packages the app. The first source build needs ordinary access to Maven Central. See `VERIFICATION.md` for the optional desktop interaction test.

## Savings pools - invitations and reviewed contributions

Create a circle with a name, currency, target, contribution amount, frequency and first contribution date. New features work in the same local database and across the signed-in account tabs; they do not send email or connect separate computers.

1. Organiser: select the circle and choose **Invite member**. Enter a registered email. This sends an in-app invitation, not immediate membership.
2. Invitee: open **Savings pools > Invitations**, choose the invitation and **Accept** or **Decline**. Acceptance creates or reactivates membership.
3. Member: select the circle, enter their own contribution amount, actual exchange date and note, then choose **Submit contribution**. It appears as **Pending review** and does not change the recorded balance.
4. Organiser: select the entry in **Entries** and choose **Approve selected entry** or **Reject selected entry**. Rejection requires a reason. Only approved entries count toward the balance and goal.
5. Member: select their pending entry and choose **Withdraw my pending entry** if it was entered incorrectly. Withdrawing keeps history and prevents later approval. Submit a new corrected record if needed.
6. **Leave circle** keeps the member and their records; it does not delete contributions or issue a refund. Pending entries must be reviewed or withdrawn first. Former members retain read-only access to shared history, including later records. They cannot submit entries and stop receiving scheduled contribution reminders. Rejoining requires another accepted invitation.
7. The organiser may directly record contributions, expenses and withdrawals. Members may submit only their own contributions. The organiser closes the circle instead of leaving it. Resolve pending reviews first; closing cancels pending invitations and preserves history.

### Dates and score fairness

The actual exchange date, original submission timestamp and review timestamp are separate. Approval never rewrites the first two dates. A contribution submitted on the due date and approved later keeps its original submission date. The entry history shows submission date and rejection reason. The full timestamps remain in the database.

**The current return reliability percentage measures completed loans only. Circle contribution submissions, rejection and delayed approval do not change that percentage.** This update does not invent a separate contribution score. A future circle punctuality feature should link contributions and partial amounts to explicit scheduled periods, evaluate approved on-time submissions, and never use the leader's review date as the member's payment date.

Existing memberships migrate as ACTIVE and existing circle entries migrate as APPROVED, preserving prior balances. The migration is additive and can safely run again. Back up an existing database with the app closed before upgrading.

## Records

`src/main/resources/schema.sql` contains the database schema. The first run in a fresh folder creates an empty ledger. To bring over records from an earlier compatible release, close the old app, keep a backup of `data/lendly.mv.db`, and copy that file to the extracted project's `data` folder before starting it. A copied database with a configured password requires the same `DB_PASSWORD`. Keep personal records and database files out of GitHub.
