package ph.edu.lendly;

import static ph.edu.lendly.Models.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class NotificationService {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public NotificationService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void event(long actor, long loanId, String message) {
        long recipient =
                jdbc.queryForObject(
                        "SELECT CASE WHEN lender_id=? THEN borrower_id ELSE lender_id END FROM loan"
                            + " WHERE id=?",
                        Long.class,
                        actor,
                        loanId);
        insert(recipient, loanId, message, "event:" + UUID.randomUUID() + ":" + recipient);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 15_000)
    @Transactional
    public void scheduledReminders() {
        if (LocalTime.now(clock).isBefore(LocalTime.of(9, 0))) return;
        createReminders(LocalDate.now(clock));
    }

    @Transactional
    public void createReminders(LocalDate date) {
        var ids =
                jdbc.queryForList(
                        "SELECT id FROM loan WHERE status='ACTIVE' ORDER BY id", Long.class);
        for (long id : ids) {
            jdbc.queryForList("SELECT id FROM loan WHERE id=? FOR UPDATE", Long.class, id);
            var rows =
                    jdbc.queryForList(
"""
SELECT l.lender_id,l.borrower_id,l.current_version,t.due_date FROM loan l
JOIN loan_terms t ON t.loan_id=l.id AND t.version=l.current_version
WHERE l.id=? AND l.status='ACTIVE'
""",
                            id);
            if (rows.isEmpty()) continue;
            var row = rows.getFirst();
            LocalDate due = ((java.sql.Date) row.get("DUE_DATE")).toLocalDate();
            LocalDate advanceDate = due.minusDays(3);
            String type =
                    !date.isBefore(advanceDate) && date.isBefore(due)
                            ? "DUE_SOON"
                            : date.equals(due) ? "DUE_TODAY" : date.isAfter(due) ? "OVERDUE" : null;
            if (type == null) continue;
            String message =
                    type.equals("DUE_SOON")
                            ? "Return or repayment is due on " + due + "."
                            : type.equals("DUE_TODAY")
                                    ? "Return or repayment is due today."
                                    : "This loan is overdue. Check the agreement and any pending"
                                            + " review.";
            for (String key : List.of("LENDER_ID", "BORROWER_ID")) {
                long recipient = ((Number) row.get(key)).longValue();
                insert(
                        recipient,
                        id,
                        message,
                        "reminder:"
                                + recipient
                                + ":"
                                + id
                                + ":"
                                + row.get("CURRENT_VERSION")
                                + ":"
                                + type
                                + ":"
                                + (type.equals("DUE_SOON") ? advanceDate : date));
            }
        }
        createContributionReminders(date);
    }

    /** One advance and one due-today notice per member for each scheduled contribution. */
    private void createContributionReminders(LocalDate date) {
        var circles =
                jdbc.queryForList(
                        "SELECT id,name,currency,contribution_amount,frequency,start_date FROM"
                                + " savings_circle WHERE status='ACTIVE' AND start_date IS NOT NULL"
                                + " AND frequency IS NOT NULL ORDER BY id");
        for (var circle : circles) {
            long id = ((Number) circle.get("ID")).longValue();
            String frequency = (String) circle.get("FREQUENCY");
            LocalDate start = ((java.sql.Date) circle.get("START_DATE")).toLocalDate();
            int index = Circle.nextIndex(start, frequency, date);
            LocalDate due = Circle.scheduledDate(start, frequency, index);
            LocalDate previous =
                    index == 0 ? null : Circle.scheduledDate(start, frequency, index - 1);
            if (date.isBefore(due.minusDays(3))) continue;
            boolean today = date.equals(due);
            String message =
                    "Contribution of "
                            + circle.get("CURRENCY")
                            + " "
                            + ((java.math.BigDecimal) circle.get("CONTRIBUTION_AMOUNT"))
                                    .toPlainString()
                            + " for \""
                            + circle.get("NAME")
                            + (today ? "\" is due today." : "\" is due on " + due + ".");
            var members =
                    jdbc.queryForList(
                            "SELECT user_id FROM circle_member WHERE circle_id=? AND"
                                + " status='ACTIVE'",
                            Long.class,
                            id);
            for (long member : members) {
                String paidSql =
                        "SELECT COUNT(*) FROM savings_entry WHERE circle_id=? AND member_id=? AND"
                            + " kind='CONTRIBUTION' AND status IN ('APPROVED','PENDING_REVIEW')"
                                + (previous == null ? "" : " AND actual_date > ?");
                Integer paid =
                        previous == null
                                ? jdbc.queryForObject(paidSql, Integer.class, id, member)
                                : jdbc.queryForObject(paidSql, Integer.class, id, member, previous);
                if (paid != null && paid > 0) continue;
                insert(
                        member,
                        null,
                        message,
                        "contribution:"
                                + id
                                + ":"
                                + member
                                + ":"
                                + due
                                + ":"
                                + (today ? "TODAY" : "SOON"));
            }
        }
    }

    private void insert(long recipient, Long id, String message, String key) {
        jdbc.update(
                "INSERT INTO notification(recipient_id,loan_id,message,dedup_key) SELECT ?,?,?,?"
                        + " WHERE NOT EXISTS(SELECT 1 FROM notification WHERE dedup_key=?)",
                recipient,
                id,
                message,
                key,
                key);
    }

    public List<Notice> list(long actor) {
        return jdbc.query(
                "SELECT * FROM notification WHERE recipient_id=? ORDER BY id DESC",
                (r, n) ->
                        new Notice(
                                r.getLong("id"),
                                r.getObject("loan_id", Long.class),
                                r.getString("message"),
                                r.getObject("created_at", OffsetDateTime.class),
                                r.getBoolean("is_read")),
                actor);
    }

    public int unread(long actor) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE recipient_id=? AND is_read=FALSE",
                Integer.class,
                actor);
    }

    public void read(long actor, long noticeId) {
        jdbc.update(
                "UPDATE notification SET is_read=TRUE WHERE recipient_id=? AND id=?",
                actor,
                noticeId);
    }

    public void readAll(long actor) {
        jdbc.update(
                "UPDATE notification SET is_read=TRUE WHERE recipient_id=? AND is_read=FALSE",
                actor);
    }
}
