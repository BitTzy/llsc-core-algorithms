package ph.edu.lendly;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import ph.edu.lendly.Models.Account;
import ph.edu.lendly.Models.Loan;
import ph.edu.lendly.Models.Notice;
import ph.edu.lendly.Rules.InvalidInput;
import ph.edu.lendly.Rules.NotFound;

import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import javax.imageio.ImageIO;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DesktopWorkflowTest {
    private static final String PASSWORD = "LendingTest!12345";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);
    private static final LocalDate START = TODAY.minusDays(3);
    private static final LocalDate DUE = TODAY.minusDays(1);
    private static final ZoneId MANILA = ZoneId.of("Asia/Manila");

    private ConfigurableApplicationContext context;
    private AccountService accounts;
    private LoanService loans;
    private ReceiptService receipts;
    private NotificationService notices;
    private CircleService circles;
    private Account first;
    private Account second;
    private Account outsider;

    @BeforeAll
    void startApplication() {
        context =
                new SpringApplicationBuilder(DesktopApplication.class, TestBeans.class)
                        .headless(true)
                        .run(
                                "--spring.datasource.url=jdbc:h2:mem:desktop-workflow;DB_CLOSE_DELAY=-1");
        assertInstanceOf(AnnotationConfigApplicationContext.class, context);
        accounts = context.getBean(AccountService.class);
        loans = context.getBean(LoanService.class);
        receipts = context.getBean(ReceiptService.class);
        notices = context.getBean(NotificationService.class);
        circles = context.getBean(CircleService.class);
    }

    @AfterAll
    void stopApplication() {
        if (context != null) context.close();
    }

    @BeforeEach
    void registerParties() {
        String suffix = UUID.randomUUID().toString();
        first = register("Alex", "alex-" + suffix + "@example.test");
        second = register("Ben", "ben-" + suffix + "@example.test");
        outsider = register("Other", "other-" + suffix + "@example.test");
    }

    @Test
    void twoAccountsSwapRolesAndBothConfirmTheSameTerms() {
        assertEquals(first, accounts.signIn(first.email(), PASSWORD));
        assertEquals(second, accounts.signIn(second.email(), PASSWORD));
        assertThrows(InvalidInput.class, () -> accounts.signIn(first.email(), "wrong password"));

        long money = propose(first, second, "MONEY", DUE);
        long item = propose(second, first, "ITEM", DUE);
        assertEquals(List.of(money), loanIds(first, "lending"));
        assertEquals(List.of(item), loanIds(first, "borrowing"));
        assertEquals(List.of(item), loanIds(second, "lending"));
        assertEquals(List.of(money), loanIds(second, "borrowing"));
        assertTrue(loanIds(outsider, "all").isEmpty());

        assertEquals("PENDING_TERMS", loans.get(second.id(), money).status());
        assertTrue(loans.confirmed(first.id(), money, 1));
        assertFalse(loans.confirmed(second.id(), money, 1));
        assertThrows(InvalidInput.class, () -> handover(first, money, 1, null));
        loans.revise(
                second.id(),
                money,
                1,
                "MONEY",
                "90",
                "PHP",
                "",
                START.toString(),
                DUE.toString(),
                true);
        assertThrows(InvalidInput.class, () -> loans.confirm(first.id(), money, 1, true));
        assertFalse(loans.confirmed(first.id(), money, 2));
        assertTrue(loans.confirmed(second.id(), money, 2));
        loans.confirm(first.id(), money, 2, true);
        assertEquals("AWAITING_HANDOVER", loans.get(first.id(), money).status());
        assertEquals(loans.get(first.id(), money), loans.get(second.id(), money));
        assertTrue(loans.confirmed(first.id(), money, 2));
        assertTrue(loans.confirmed(second.id(), money, 2));

        assertThrows(InvalidInput.class, () -> handover(second, money, 2, null));
        long submitted = handover(first, money, 2, null);
        assertEquals("AWAITING_HANDOVER", loans.get(second.id(), money).status());
        assertThrows(InvalidInput.class, () -> confirm(first, money, submitted, 2));
        confirm(second, money, submitted, 2);
        assertEquals("ACTIVE", loans.get(second.id(), money).status());
    }

    @Test
    void pendingHandoverMustBeResolvedBeforeEitherPartyRevisesTerms() {
        long loan = propose(first, second, "ITEM", DUE);
        loans.confirm(second.id(), loan, 1, true);
        long activity = handover(first, loan, 1, null);

        for (Account party : List.of(first, second)) {
            assertThrows(InvalidInput.class, () -> reviseItem(party, loan));
        }
        assertEquals("PENDING_REVIEW", loans.activities(second.id(), loan).getFirst().status());
        assertEquals(1, loans.get(second.id(), loan).terms().version());

        loans.withdraw(first.id(), loan, activity, 1);
        reviseItem(second, loan);
        assertEquals(2, loans.get(first.id(), loan).terms().version());
        assertEquals("WITHDRAWN", loans.activities(first.id(), loan).getFirst().status());
    }

    @Test
    void repaymentRecordsCannotOverbookTheOutstandingAmount() {
        long loan = activate(first, second, "MONEY", DUE);
        long partial = submit(second, loan, DUE, "60");
        assertThrows(InvalidInput.class, () -> submit(second, loan, DUE, "50"));
        assertEquals(new BigDecimal("100.00"), loans.get(first.id(), loan).balance());
        long remainder = submit(second, loan, DUE, "40");
        loans.withdraw(second.id(), loan, partial, 1);
        long replacement = submit(second, loan, DUE, "60");
        confirm(first, loan, remainder, 1);
        confirm(first, loan, replacement, 1);
        assertEquals("COMPLETED", loans.get(second.id(), loan).status());
    }

    @Test
    void inboxShowsTheOtherPartysActionsAndReadsOneOwnedNotice() {
        long loan = propose(first, second, "ITEM", DUE);
        Notice proposal = notices.list(second.id()).getFirst();
        assertEquals(loan, proposal.loanId());
        assertEquals(0, notices.unread(first.id()));

        loans.confirm(second.id(), loan, 1, true);
        assertTrue(notices.list(first.id()).getFirst().message().contains("handover"));
        handover(first, loan, 1, null);
        Notice handover = notices.list(second.id()).getFirst();
        assertEquals(2, notices.unread(second.id()));
        notices.read(first.id(), handover.id());
        assertEquals(2, notices.unread(second.id()));
        notices.read(second.id(), handover.id());
        notices.read(second.id(), handover.id());
        assertEquals(1, notices.unread(second.id()));
        assertFalse(
                notices.list(second.id()).stream()
                        .filter(n -> n.id() == proposal.id())
                        .findFirst()
                        .orElseThrow()
                        .read());
    }

    @Test
    void reopeningAfterThreeDayMarkStillCreatesOneAdvanceReminder() {
        LocalDate due = TODAY.plusDays(2);
        long loan = activate(first, second, "ITEM", due);
        notices.createReminders(TODAY);
        notices.createReminders(TODAY.plusDays(1));
        for (Account party : List.of(first, second)) {
            var advance =
                    notices.list(party.id()).stream()
                            .filter(n -> n.loanId() == loan && n.message().contains("due on"))
                            .toList();
            assertEquals(1, advance.size());
            assertTrue(advance.getFirst().message().contains(due.toString()));
        }
    }

    @Test
    void privateReceiptRequiresCounterpartReviewAndHistoryUsesActualDates(@TempDir Path dir)
            throws Exception {
        long money = propose(first, second, "MONEY", DUE);
        loans.confirm(second.id(), money, 1, true);
        Path invalid = Files.writeString(dir.resolve("invalid.png"), "not an image");
        assertThrows(InvalidInput.class, () -> handover(first, money, 1, invalid));
        assertTrue(loans.activities(first.id(), money).isEmpty());
        Path png = dir.resolve("handover.png");
        assertTrue(
                ImageIO.write(
                        new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", png.toFile()));
        long activity = handover(first, money, 1, png);
        long receipt = loans.activities(first.id(), money).getFirst().receiptId();
        assertEquals("image/png", receipts.get(second.id(), receipt).contentType());
        assertArrayEquals(
                receipts.get(first.id(), receipt).bytes(),
                receipts.get(second.id(), receipt).bytes());
        assertThrows(NotFound.class, () -> receipts.get(outsider.id(), receipt));
        confirm(second, money, activity, 1);

        long partial = submit(second, money, DUE.minusDays(1), "40");
        assertEquals(new BigDecimal("100.00"), loans.get(first.id(), money).balance());
        assertThrows(InvalidInput.class, () -> confirm(second, money, partial, 1));
        confirm(first, money, partial, 1);
        assertEquals(new BigDecimal("60.00"), loans.get(second.id(), money).balance());
        confirm(first, money, submit(second, money, DUE, "60"), 1);
        assertEquals(DUE, loans.get(second.id(), money).completedOn());
        assertEquals("COMPLETED", loans.get(second.id(), money).status());

        long item = activate(first, second, "ITEM", DUE);
        long returned = submit(second, item, TODAY, "");
        loans.review(first.id(), item, returned, 1, "DISPUTE", "Wrong item", false);
        assertEquals("ACTIVE", loans.get(second.id(), item).status());
        confirm(first, item, submit(second, item, TODAY, ""), 1);
        Loan late = loans.get(second.id(), item);
        assertEquals("COMPLETED", late.status());
        assertEquals(TODAY, late.completedOn());
        assertTrue(late.completedOn().isAfter(late.terms().dueDate()));
        long open = activate(first, second, "ITEM", DUE);
        assertEquals("ACTIVE", loans.get(second.id(), open).status());
        assertEquals("50%", DesktopUi.trust(loans.list(second.id(), "all", "", ""), second.id()));
        assertEquals(
                "Not yet rated",
                DesktopUi.trust(loans.list(first.id(), "all", "", ""), first.id()));
    }

    @Test
    void scheduledOverdueNotificationsReachOnlyBothParticipantsOnce() {
        long loan = activate(first, second, "ITEM", DUE);
        Runnable scheduled =
                context
                        .getBean(ScheduledAnnotationBeanPostProcessor.class)
                        .getScheduledTasks()
                        .stream()
                        .map(task -> task.getTask().getRunnable())
                        .filter(task -> task.toString().endsWith(".scheduledReminders"))
                        .findFirst()
                        .orElseThrow();
        TestClock clock = context.getBean(TestClock.class);
        scheduled.run();
        assertTrue(overdue(first, loan).isEmpty());
        try {
            clock.hour = 9;
            scheduled.run();
            scheduled.run();
        } finally {
            clock.hour = 4;
        }
        for (Account account : List.of(first, second)) {
            assertEquals(1, overdue(account, loan).size());
            assertFalse(overdue(account, loan).getFirst().read());
        }
        assertTrue(notices.list(outsider.id()).isEmpty());
        notices.readAll(second.id());
        assertTrue(overdue(second, loan).getFirst().read());
        assertFalse(overdue(first, loan).getFirst().read());
    }

    @Test
    void circleEntriesStaySeparateAndOnlyTheOrganiserMayRecordExpenses() {
        long circle =
                circles.create(
                        first.id(), "Trip fund", "PHP", "5000", "500", "WEEKLY", TODAY.toString());
        circles.addMember(first.id(), circle, second.email());
        circles.respondInvitation(
                second.id(), circles.invitations(second.id()).getFirst().id(), true);
        circles.addEntry(
                first.id(), circle, "CONTRIBUTION", "100", second.id(), TODAY.toString(), "");
        circles.addEntry(first.id(), circle, "EXPENSE", "20", null, TODAY.toString(), "");
        circles.addEntry(first.id(), circle, "WITHDRAWAL", "10", null, TODAY.toString(), "");
        assertEquals(new BigDecimal("70.00"), circles.get(second.id(), circle).balance());
        assertEquals(3, circles.entries(second.id(), circle).size());
        assertEquals(2, circles.members(second.id(), circle).size());
        assertThrows(NotFound.class, () -> circles.get(outsider.id(), circle));
        assertThrows(
                InvalidInput.class,
                () ->
                        circles.addEntry(
                                second.id(), circle, "EXPENSE", "1", null, TODAY.toString(), ""));
        assertTrue(loanIds(second, "all").isEmpty());
    }

    @Test
    void passwordsNeedTwelveCharactersWithLettersAndNumbers() {
        String email = "pw-" + UUID.randomUUID() + "@example.test";
        for (String weak : new String[] {"Short1", "onlyletterslong", "123456789012"}) {
            assertThrows(
                    InvalidInput.class, () -> accounts.register("Pat", email, weak, weak), weak);
        }
        String tooLong = "a1" + "x".repeat(80);
        assertThrows(InvalidInput.class, () -> accounts.register("Pat", email, tooLong, tooLong));
        assertThrows(
                InvalidInput.class,
                () -> accounts.register("Pat", email, "goodpassword12", "goodpassword13"));
        // Special characters are allowed, just not required.
        assertNotNull(accounts.register("Pat", email, "plainpassword12", "plainpassword12"));
    }

    @Test
    void circleGoalTracksProgressAndScheduleAndRemindsMembers() {
        assertThrows(
                InvalidInput.class,
                () ->
                        circles.create(
                                first.id(), "Bad", "PHP", "", "100", "WEEKLY", TODAY.toString()));
        assertThrows(
                InvalidInput.class,
                () ->
                        circles.create(
                                first.id(),
                                "Bad",
                                "PHP",
                                "100",
                                "500",
                                "WEEKLY",
                                TODAY.toString()));
        long circle =
                circles.create(
                        first.id(),
                        "Gift",
                        "PHP",
                        "1000",
                        "250",
                        "WEEKLY",
                        TODAY.minusDays(5).toString());
        circles.addMember(first.id(), circle, second.email());
        circles.respondInvitation(
                second.id(), circles.invitations(second.id()).getFirst().id(), true);
        circles.addEntry(
                first.id(), circle, "CONTRIBUTION", "250", second.id(), TODAY.toString(), "");
        var recorded = circles.get(first.id(), circle);
        assertEquals(25.0, recorded.progressPercentage());
        assertFalse(recorded.goalReached());
        assertEquals(TODAY.minusDays(5).plusWeeks(1), recorded.nextContributionDue(TODAY));

        notices.createReminders(TODAY);
        assertTrue(circleNotices(second).isEmpty(), "a member who paid is not reminded");
        assertEquals(1, circleNotices(first).size(), "the organiser has not paid yet");
        notices.createReminders(TODAY);
        assertEquals(1, circleNotices(first).size(), "reminders are not repeated");

        circles.close(first.id(), circle);
        assertThrows(
                InvalidInput.class,
                () ->
                        circles.addEntry(
                                first.id(), circle, "EXPENSE", "1", null, TODAY.toString(), ""));
        assertThrows(NotFound.class, () -> circles.close(second.id(), circle));
    }

    @Test
    void invitationsApprovalAndLeavingPreserveHistory() {
        long id =
                circles.create(
                        first.id(), "Team fund", "PHP", "1000", "100", "WEEKLY", TODAY.toString());
        circles.addMember(first.id(), id, second.email());
        assertTrue(circles.list(second.id()).isEmpty());
        long invite = circles.invitations(second.id()).getFirst().id();
        assertThrows(NotFound.class, () -> circles.respondInvitation(outsider.id(), invite, true));
        circles.respondInvitation(second.id(), invite, true);
        assertThrows(
                InvalidInput.class, () -> circles.respondInvitation(second.id(), invite, true));
        circles.addEntry(
                second.id(),
                id,
                "CONTRIBUTION",
                "100",
                second.id(),
                TODAY.toString(),
                "Paid today");
        var entry = circles.entries(first.id(), id).getFirst();
        assertEquals("PENDING_REVIEW", entry.status());
        assertEquals(0, circles.get(first.id(), id).balance().signum());
        assertThrows(InvalidInput.class, () -> circles.leave(second.id(), id));
        assertThrows(InvalidInput.class, () -> circles.close(first.id(), id));
        assertThrows(
                NotFound.class, () -> circles.reviewEntry(second.id(), id, entry.id(), true, ""));
        assertThrows(
                InvalidInput.class,
                () ->
                        circles.addEntry(
                                second.id(),
                                id,
                                "CONTRIBUTION",
                                "100",
                                first.id(),
                                TODAY.toString(),
                                ""));
        circles.reviewEntry(first.id(), id, entry.id(), true, "");
        var approved = circles.entries(first.id(), id).getFirst();
        assertEquals(entry.actualDate(), approved.actualDate());
        assertEquals(entry.createdAt(), approved.createdAt());
        assertEquals(new BigDecimal("100.00"), circles.get(first.id(), id).balance());
        assertThrows(
                InvalidInput.class,
                () -> circles.reviewEntry(first.id(), id, entry.id(), true, ""));
        circles.leave(second.id(), id);
        assertEquals(
                "LEFT",
                circles.members(first.id(), id).stream()
                        .filter(m -> m.id() == second.id())
                        .findFirst()
                        .orElseThrow()
                        .status());
        assertEquals(1, circles.entries(second.id(), id).size());
        assertThrows(
                InvalidInput.class,
                () ->
                        circles.addEntry(
                                second.id(),
                                id,
                                "CONTRIBUTION",
                                "1",
                                second.id(),
                                TODAY.toString(),
                                ""));
        assertThrows(InvalidInput.class, () -> circles.leave(first.id(), id));
        assertEquals(new BigDecimal("100.00"), circles.get(first.id(), id).balance());
    }

    @Test
    void declineRejectAndReinviteDoNotChangeBalance() {
        long id =
                circles.create(
                        first.id(), "Team fund", "PHP", "1000", "100", "WEEKLY", TODAY.toString());
        circles.addMember(first.id(), id, second.email());
        circles.respondInvitation(
                second.id(), circles.invitations(second.id()).getFirst().id(), false);
        assertTrue(circles.list(second.id()).isEmpty());
        circles.addMember(first.id(), id, second.email());
        circles.respondInvitation(
                second.id(), circles.invitations(second.id()).getFirst().id(), true);
        circles.addEntry(
                second.id(), id, "CONTRIBUTION", "100", second.id(), TODAY.toString(), "mistake");
        var entry = circles.entries(first.id(), id).getFirst();
        assertThrows(
                InvalidInput.class,
                () -> circles.reviewEntry(first.id(), id, entry.id(), false, ""));
        circles.reviewEntry(first.id(), id, entry.id(), false, "Duplicate record");
        assertEquals(0, circles.get(first.id(), id).balance().signum());
        assertEquals(
                "Duplicate record", circles.entries(second.id(), id).getFirst().reviewReason());
        circles.leave(second.id(), id);
        circles.addMember(first.id(), id, second.email());
        circles.respondInvitation(
                second.id(), circles.invitations(second.id()).getFirst().id(), true);
        assertEquals(2, circles.members(first.id(), id).size());
    }

    @Test
    void memberCanWithdrawPendingEntryAndLeaveWithoutWaiting() {
        long id =
                circles.create(
                        first.id(),
                        "Withdraw fund",
                        "PHP",
                        "1000",
                        "100",
                        "WEEKLY",
                        TODAY.toString());
        circles.addMember(first.id(), id, second.email());
        circles.respondInvitation(
                second.id(), circles.invitations(second.id()).getFirst().id(), true);
        circles.addEntry(
                second.id(),
                id,
                "CONTRIBUTION",
                "100",
                second.id(),
                TODAY.minusDays(1).toString(),
                "Submitted for review");
        var entry = circles.entries(first.id(), id).getFirst();
        assertThrows(InvalidInput.class, () -> circles.withdrawEntry(first.id(), id, entry.id()));
        circles.withdrawEntry(second.id(), id, entry.id());
        assertEquals("WITHDRAWN", circles.entries(first.id(), id).getFirst().status());
        assertThrows(
                InvalidInput.class,
                () -> circles.reviewEntry(first.id(), id, entry.id(), true, ""));
        circles.leave(second.id(), id);
        assertEquals(0, circles.get(first.id(), id).balance().signum());
    }

    private List<Notice> circleNotices(Account account) {
        return notices.list(account.id()).stream()
                .filter(
                        notice ->
                                notice.loanId() == null
                                        && notice.message().startsWith("Contribution of "))
                .toList();
    }

    private Account register(String name, String email) {
        return accounts.register(name, email, PASSWORD, PASSWORD);
    }

    private List<Long> loanIds(Account account, String role) {
        return loans.list(account.id(), role, "", "").stream().map(Loan::id).toList();
    }

    private long propose(Account lender, Account borrower, String type, LocalDate due) {
        boolean money = type.equals("MONEY");
        return loans.create(
                lender.id(),
                borrower.email(),
                "lending",
                type,
                money ? "100" : "",
                money ? "PHP" : "",
                money ? "" : "Umbrella",
                START.toString(),
                due.toString(),
                true);
    }

    private long activate(Account lender, Account borrower, String type, LocalDate due) {
        long loan = propose(lender, borrower, type, due);
        loans.confirm(borrower.id(), loan, 1, true);
        confirm(borrower, loan, handover(lender, loan, 1, null), 1);
        return loan;
    }

    private void reviseItem(Account actor, long loan) {
        loans.revise(
                actor.id(),
                loan,
                1,
                "ITEM",
                "",
                "",
                "Umbrella",
                START.toString(),
                DUE.toString(),
                true);
    }

    private long handover(Account lender, long loan, int version, Path receipt) {
        return loans.submit(lender.id(), loan, version, START.toString(), "", "", receipt, true);
    }

    private long submit(Account borrower, long loan, LocalDate date, String amount) {
        return loans.submit(borrower.id(), loan, 1, date.toString(), amount, "", null, true);
    }

    private void confirm(Account reviewer, long loan, long activity, int version) {
        loans.review(reviewer.id(), loan, activity, version, "CONFIRM", "", true);
    }

    private List<Notice> overdue(Account account, long loan) {
        return notices.list(account.id()).stream()
                .filter(
                        n ->
                                n.loanId() != null
                                        && n.loanId() == loan
                                        && n.message().contains("overdue"))
                .toList();
    }

    static class TestBeans {
        @Bean
        @Primary
        TestClock controllableManilaClock() {
            return new TestClock();
        }
    }

    static class TestClock extends Clock {
        volatile int hour = 4;

        @Override
        public ZoneId getZone() {
            return MANILA;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return TODAY.atTime(hour, 0).atZone(MANILA).toInstant();
        }
    }
}
