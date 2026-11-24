package ph.edu.lendly;

import static ph.edu.lendly.Models.*;
import static ph.edu.lendly.Rules.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Service
public class LoanService {
    private final JdbcTemplate jdbc;
    private final Database db;
    private final AccountService accounts;
    private final NotificationService notices;
    private final ReceiptService receipts;
    private final Clock clock;
    private static final String SELECT_LOAN =
            """
            SELECT l.*, u.name lender_name, b.name borrower_name, t.*,
            COALESCE(t.amount,0)-COALESCE((SELECT SUM(a.amount) FROM loan_activity a
                WHERE a.loan_id=l.id AND a.kind='REPAYMENT' AND a.status='CONFIRMED'),0) balance
            FROM loan l JOIN app_user u ON u.id=l.lender_id JOIN app_user b ON b.id=l.borrower_id
            JOIN loan_terms t ON t.loan_id=l.id AND t.version=l.current_version
            """;

    public LoanService(
            JdbcTemplate jdbc,
            Database db,
            AccountService accounts,
            NotificationService notices,
            ReceiptService receipts,
            Clock clock) {
        this.jdbc = jdbc;
        this.db = db;
        this.accounts = accounts;
        this.notices = notices;
        this.receipts = receipts;
        this.clock = clock;
    }

    @Transactional
    public long create(
            long actor,
            String email,
            String role,
            String kind,
            String amount,
            String currency,
            String item,
            String start,
            String due,
            boolean acknowledgement) {
        require(acknowledgement, "Confirm the proposed terms and dates before submitting.");
        require(role.equals("borrowing") || role.equals("lending"), "Choose Lending or Borrowing.");
        Account counterpart;
        try {
            counterpart = accounts.byEmail(email);
        } catch (NotFound ex) {
            throw new InvalidInput(
                    "No registered friend has that email. Ask them to register first.");
        }
        require(actor != counterpart.id(), "You cannot create a loan with yourself.");
        Terms terms = validateTerms(1, actor, kind, amount, currency, item, start, due);
        long lender = role.equals("lending") ? actor : counterpart.id();
        long borrower = role.equals("borrowing") ? actor : counterpart.id();
        long id =
                db.insert(
                        "INSERT INTO"
                                + " loan(lender_id,borrower_id,initiator_id,status,current_version)"
                                + " VALUES(?,?,?,'PENDING_TERMS',1)",
                        lender,
                        borrower,
                        actor);
        insertTerms(id, terms);
        jdbc.update(
                "INSERT INTO loan_confirmation(loan_id,version,user_id) VALUES(?,1,?)", id, actor);
        notices.event(actor, id, "New loan proposal. Review the parties, dates and terms.");
        return id;
    }

    @Transactional
    public void revise(
            long actor,
            long id,
            int version,
            String kind,
            String amount,
            String currency,
            String item,
            String start,
            String due,
            boolean acknowledgement) {
        Loan loan = lock(actor, id, version);
        require(preActive(loan), "Active or closed agreements cannot be revised.");
        require(!pendingHandover(id),
                "Review the pending handover or have the lender withdraw it before revising terms.");
        require(acknowledgement, "Confirm the revised terms and dates.");
        Terms terms = validateTerms(version + 1, actor, kind, amount, currency, item, start, due);
        insertTerms(id, terms);
        jdbc.update(
                "UPDATE loan SET current_version=?,status='PENDING_TERMS' WHERE id=?",
                version + 1,
                id);
        jdbc.update(
                "INSERT INTO loan_confirmation(loan_id,version,user_id) VALUES(?,?,?)",
                id,
                version + 1,
                actor);
        notices.event(
                actor,
                id,
                "Terms revised to version "
                        + (version + 1)
                        + ". Both parties must agree to this version.");
    }

    @Transactional
    public void confirm(long actor, long id, int version, boolean acknowledgement) {
        Loan loan = lock(actor, id, version);
        require(
                loan.status().equals("PENDING_TERMS"),
                "This agreement is not waiting for terms confirmation.");
        require(acknowledgement, "Confirm that the parties, start date and due date are correct.");
        require(!confirmed(actor, id, version), "You have already confirmed this version.");
        jdbc.update(
                "INSERT INTO loan_confirmation(loan_id,version,user_id) VALUES(?,?,?)",
                id,
                version,
                actor);
        if (confirmed(loan.lenderId(), id, version) && confirmed(loan.borrowerId(), id, version))
            jdbc.update("UPDATE loan SET status='AWAITING_HANDOVER' WHERE id=?", id);
        notices.event(
                actor,
                id,
                "Both parties confirmed terms version " + version
                        + ". The lender can now record the handover.");
    }

    @Transactional
    public void closeProposal(long actor, long id, int version, boolean decline) {
        Loan loan = lock(actor, id, version);
        if (decline) {
            require(
                    loan.status().equals("PENDING_TERMS") && actor != loan.terms().proposedBy(),
                    "Only the recipient can decline pending terms.");
        } else {
            require(
                    preActive(loan) && !pendingHandover(id),
                    "Cancellation is allowed before activation, with no handover awaiting review.");
        }
        jdbc.update("UPDATE loan SET status=? WHERE id=?", decline ? "DECLINED" : "CANCELLED", id);
        notices.event(actor, id, decline ? "Loan proposal declined." : "Loan proposal cancelled.");
    }

    @Transactional
    public long submit(
            long actor,
            long id,
            int version,
            String actualDate,
            String amount,
            String note,
            Path file,
            boolean acknowledgement) {
        Loan loan = lock(actor, id, version);
        require(acknowledgement, "Acknowledge that this records an exchange outside the app.");
        LocalDate date = Rules.date(actualDate);
        require(!date.isAfter(LocalDate.now(clock)), "The exchange date cannot be in the future.");
        String kind;
        BigDecimal payment = null;
        if (loan.status().equals("AWAITING_HANDOVER")) {
            require(actor == loan.lenderId(), "Only the lender can record the handover.");
            require(!pendingHandover(id), "A handover is already waiting for review.");
            require(
                    date.equals(loan.terms().startDate()),
                    "Handover must match the agreed start date. Revise the terms first if"
                            + " necessary.");
            kind = "HANDOVER";
        } else {
            require(
                    loan.status().equals("ACTIVE") && actor == loan.borrowerId(),
                    "Only the borrower can report repayment or return on an active loan.");
            require(
                    !date.isBefore(loan.terms().startDate()),
                    "Repayment or return cannot predate the handover.");
            kind = loan.terms().kind().equals("MONEY") ? "REPAYMENT" : "ITEM_RETURN";
            if (kind.equals("REPAYMENT")) {
                payment = money(amount);
                require(
                        payment.compareTo(loan.balance()) <= 0,
                        "Repayment exceeds the confirmed outstanding balance.");
                BigDecimal pending = jdbc.queryForObject(
                        """
                        SELECT COALESCE(SUM(amount),0) FROM loan_activity
                        WHERE loan_id=? AND version=? AND kind='REPAYMENT' AND status='PENDING_REVIEW'
                        """,
                        BigDecimal.class, id, version);
                require(payment.compareTo(loan.balance().subtract(pending)) <= 0,
                        "Pending repayments already cover part or all of this balance. "
                                + "Wait for the lender to review them, or withdraw an incorrect record.");
            } else {
                require(
                        jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM loan_activity WHERE loan_id=? AND"
                                                + " kind='ITEM_RETURN' AND status='PENDING_REVIEW'",
                                        Integer.class,
                                        id)
                                == 0,
                        "An item return is already awaiting review.");
            }
        }
        if (!kind.equals("REPAYMENT"))
            require(
                    text(amount, 20, "Amount").isEmpty(),
                    "Only a money repayment accepts an amount.");
        Evidence evidence = receipts.validate(file);
        long activity =
                db.insert(
                        "INSERT INTO"
                            + " loan_activity(loan_id,version,kind,submitted_by,actual_date,amount,note)"
                            + " VALUES(?,?,?,?,?,?,?)",
                        id,
                        version,
                        kind,
                        actor,
                        date,
                        payment,
                        text(note, 500, "Note"));
        receipts.attach(activity, evidence);
        notices.event(
                actor,
                id,
                kind.replace('_', ' ').toLowerCase()
                        + " submitted. The other party must confirm or dispute it.");
        return activity;
    }

    @Transactional
    public void review(
            long actor,
            long id,
            long activityId,
            int version,
            String decision,
            String reason,
            boolean acknowledgement) {
        Loan loan = lock(actor, id, version);
        Activity activity = activity(actor, id, activityId);
        require(
                activity.version() == version && activity.status().equals("PENDING_REVIEW"),
                "This submission is no longer awaiting review. Reload the agreement.");
        require(actor != activity.submittedBy(), "You cannot review your own submission.");
        boolean handover = activity.kind().equals("HANDOVER");
        require(
                handover
                        ? actor == loan.borrowerId() && loan.status().equals("AWAITING_HANDOVER")
                        : actor == loan.lenderId() && loan.status().equals("ACTIVE"),
                "Your role or the loan state does not permit this review.");
        require(
                decision.equals("CONFIRM") || decision.equals("DISPUTE"),
                "Choose Confirm or Dispute.");
        boolean confirm = decision.equals("CONFIRM");
        reason = text(reason, 500, "Review reason");
        require(
                confirm ? acknowledgement : !reason.isBlank(),
                confirm
                        ? "Acknowledge that you checked the exchange date, amount or item and"
                                + " evidence."
                        : "Explain why you are disputing this submission.");
        if (confirm && activity.kind().equals("REPAYMENT"))
            require(
                    activity.amount().compareTo(loan.balance()) <= 0,
                    "Another confirmed repayment changed the balance. This submission would overpay"
                            + " the loan.");
        jdbc.update(
                "UPDATE loan_activity SET"
                    + " status=?,reviewed_by=?,review_reason=?,reviewed_at=CURRENT_TIMESTAMP WHERE"
                    + " id=?",
                confirm ? "CONFIRMED" : "DISPUTED",
                actor,
                reason,
                activityId);
        if (confirm && handover) jdbc.update("UPDATE loan SET status='ACTIVE' WHERE id=?", id);
        if (confirm && !handover) {
            boolean complete =
                    activity.kind().equals("ITEM_RETURN")
                            || loan.balance().subtract(activity.amount()).signum() == 0;
            if (complete) {
                LocalDate completed =
                        jdbc.queryForObject(
                                "SELECT MAX(actual_date) FROM loan_activity WHERE loan_id=? AND"
                                    + " status='CONFIRMED' AND kind IN ('REPAYMENT','ITEM_RETURN')",
                                LocalDate.class,
                                id);
                jdbc.update(
                        "UPDATE loan SET status='COMPLETED',completed_on=? WHERE id=?",
                        completed,
                        id);
                jdbc.update(
                        "UPDATE loan_activity SET status='WITHDRAWN' WHERE loan_id=? AND"
                                + " status='PENDING_REVIEW'",
                        id);
            }
        }
        notices.event(
                actor,
                id,
                activity.kind().replace('_', ' ').toLowerCase()
                        + (confirm
                                ? " confirmed."
                                : " disputed. Review the reason and submit a correction if"
                                        + " needed."));
    }

    @Transactional
    public void withdraw(long actor, long id, long activityId, int version) {
        lock(actor, id, version);
        Activity activity = activity(actor, id, activityId);
        require(
                activity.submittedBy() == actor
                        && activity.version() == version
                        && activity.status().equals("PENDING_REVIEW"),
                "Only your pending submission can be withdrawn.");
        jdbc.update("UPDATE loan_activity SET status='WITHDRAWN' WHERE id=?", activityId);
        notices.event(actor, id, "A pending submission was withdrawn. Confirmed balances are unchanged.");
    }

    public Loan get(long actor, long id) {
        var rows =
                jdbc.query(
                        SELECT_LOAN + " WHERE l.id=? AND (l.lender_id=? OR l.borrower_id=?)",
                        this::mapLoan,
                        id,
                        actor,
                        actor);
        if (rows.isEmpty()) throw new NotFound();
        return rows.getFirst();
    }

    public List<Loan> list(long actor, String role, String query, String status) {
        String sql =
                SELECT_LOAN
                        + " WHERE "
                        + (role.equals("lending")
                                ? "l.lender_id=?"
                                : role.equals("borrowing")
                                        ? "l.borrower_id=?"
                                        : "(l.lender_id=? OR l.borrower_id=?)")
                        + " ORDER BY l.id DESC";
        List<Loan> rows =
                role.equals("lending") || role.equals("borrowing")
                        ? jdbc.query(sql, this::mapLoan, actor)
                        : jdbc.query(sql, this::mapLoan, actor, actor);
        String search = text(query, 100, "Search").toLowerCase(java.util.Locale.ROOT);
        return rows.stream()
                .filter(l -> status == null || status.isBlank() || l.status().equals(status))
                .filter(
                        l ->
                                (l.lenderName()
                                                + " "
                                                + l.borrowerName()
                                                + " "
                                                + l.id()
                                                + " "
                                                + l.terms().itemDescription())
                                        .toLowerCase(java.util.Locale.ROOT)
                                        .contains(search))
                .toList();
    }

    public List<Activity> activities(long actor, long id) {
        get(actor, id);
        return jdbc.query(
                """
                SELECT a.*,u.name submitter_name,r.id receipt_id FROM loan_activity a
                JOIN app_user u ON u.id=a.submitted_by LEFT JOIN receipt r ON r.activity_id=a.id
                WHERE a.loan_id=? ORDER BY a.id DESC
                """,
                (r, n) ->
                        new Activity(
                                r.getLong("id"),
                                id,
                                r.getInt("version"),
                                r.getString("kind"),
                                r.getLong("submitted_by"),
                                r.getString("submitter_name"),
                                r.getObject("actual_date", LocalDate.class),
                                r.getBigDecimal("amount"),
                                r.getString("note"),
                                r.getString("status"),
                                r.getString("review_reason"),
                                r.getObject("receipt_id", Long.class),
                                r.getObject("created_at", OffsetDateTime.class)),
                id);
    }

    public List<Confirmation> confirmations(long actor, long id) {
        get(actor, id);
        return jdbc.query(
                "SELECT c.*,u.name FROM loan_confirmation c JOIN app_user u ON u.id=c.user_id WHERE"
                        + " c.loan_id=? ORDER BY c.version,c.confirmed_at",
                (r, n) ->
                        new Confirmation(
                                r.getLong("user_id"),
                                r.getString("name"),
                                r.getInt("version"),
                                r.getObject("confirmed_at", OffsetDateTime.class)),
                id);
    }

    public List<Terms> versions(long actor, long id) {
        get(actor, id);
        return jdbc.query(
                "SELECT * FROM loan_terms WHERE loan_id=? ORDER BY version DESC",
                (r, n) -> mapTerms(r),
                id);
    }

    public boolean confirmed(long actor, long id, int version) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM loan_confirmation WHERE loan_id=? AND version=? AND"
                                + " user_id=?",
                        Integer.class,
                        id,
                        version,
                        actor)
                > 0;
    }

    public boolean pendingHandover(long id) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM loan_activity WHERE loan_id=? AND kind='HANDOVER' AND"
                                + " status='PENDING_REVIEW'",
                        Integer.class,
                        id)
                > 0;
    }

    private Activity activity(long actor, long id, long activityId) {
        return activities(actor, id).stream()
                .filter(a -> a.id() == activityId)
                .findFirst()
                .orElseThrow(NotFound::new);
    }

    private Loan lock(long actor, long id, int version) {
        var ids =
                jdbc.queryForList(
                        "SELECT id FROM loan WHERE id=? AND (lender_id=? OR borrower_id=?) FOR"
                                + " UPDATE",
                        Long.class,
                        id,
                        actor,
                        actor);
        if (ids.isEmpty()) throw new NotFound();
        Loan loan = get(actor, id);
        require(
                loan.currentVersion() == version,
                "The terms have changed. Reload and review the current version.");
        return loan;
    }

    private boolean preActive(Loan loan) {
        return loan.status().equals("PENDING_TERMS") || loan.status().equals("AWAITING_HANDOVER");
    }

    private Terms validateTerms(
            int version,
            long actor,
            String kind,
            String amount,
            String currency,
            String item,
            String start,
            String due) {
        require("MONEY".equals(kind) || "ITEM".equals(kind), "Choose Money or Item.");
        LocalDate startDate = Rules.date(start), dueDate = Rules.date(due);
        require(!startDate.isAfter(dueDate), "The due date must be on or after the start date.");
        if (kind.equals("MONEY")) {
            require(
                    text(item, 200, "Item description").isBlank(),
                    "Money loans cannot also contain an item.");
            return new Terms(
                    version,
                    kind,
                    money(amount),
                    Rules.currency(currency),
                    null,
                    startDate,
                    dueDate,
                    actor);
        }
        require(
                text(amount, 20, "Amount").isBlank() && text(currency, 3, "Currency").isBlank(),
                "Item loans cannot also contain money.");
        return new Terms(
                version,
                kind,
                null,
                null,
                required(item, 200, "Item description"),
                startDate,
                dueDate,
                actor);
    }

    private void insertTerms(long id, Terms t) {
        jdbc.update(
                "INSERT INTO"
                    + " loan_terms(loan_id,version,kind,amount,currency,item_description,start_date,due_date,proposed_by)"
                    + " VALUES(?,?,?,?,?,?,?,?,?)",
                id,
                t.version(),
                t.kind(),
                t.amount(),
                t.currency(),
                t.itemDescription(),
                t.startDate(),
                t.dueDate(),
                t.proposedBy());
    }

    private Terms mapTerms(ResultSet r) throws SQLException {
        return new Terms(
                r.getInt("version"),
                r.getString("kind"),
                r.getBigDecimal("amount"),
                r.getString("currency"),
                r.getString("item_description"),
                r.getObject("start_date", LocalDate.class),
                r.getObject("due_date", LocalDate.class),
                r.getLong("proposed_by"));
    }

    private Loan mapLoan(ResultSet r, int n) throws SQLException {
        return new Loan(
                r.getLong("id"),
                r.getLong("lender_id"),
                r.getLong("borrower_id"),
                r.getLong("initiator_id"),
                r.getString("status"),
                r.getInt("current_version"),
                r.getString("lender_name"),
                r.getString("borrower_name"),
                mapTerms(r),
                r.getBigDecimal("balance"),
                r.getObject("completed_on", LocalDate.class));
    }
}
