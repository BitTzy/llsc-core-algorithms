package ph.edu.lendly;

import static ph.edu.lendly.Models.*;
import static ph.edu.lendly.Rules.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Service
public class CircleService {
    private final JdbcTemplate jdbc;
    private final Database db;
    private final AccountService accounts;
    private final Clock clock;

    public CircleService(JdbcTemplate jdbc, Database db, AccountService accounts, Clock clock) {
        this.jdbc = jdbc;
        this.db = db;
        this.accounts = accounts;
        this.clock = clock;
    }

    @Transactional
    public long create(
            long actor,
            String name,
            String currency,
            String target,
            String contribution,
            String frequency,
            String startDate) {
        name = required(name, 80, "Circle name");
        currency = Rules.currency(currency);
        BigDecimal goal = money(target, "Savings target");
        BigDecimal each = money(contribution, "Contribution amount");
        require(
                each.compareTo(goal) <= 0,
                "The contribution cannot be larger than the savings target.");
        require(Circle.FREQUENCIES.contains(frequency), "Choose how often members contribute.");
        LocalDate start = Rules.date(startDate);
        long id =
                db.insert(
                        "INSERT INTO savings_circle(name,organiser_id,currency,target_amount,"
                                + "contribution_amount,frequency,start_date,status)"
                                + " VALUES(?,?,?,?,?,?,?,'ACTIVE')",
                        name,
                        actor,
                        currency,
                        goal,
                        each,
                        frequency,
                        start);
        jdbc.update(
                "INSERT INTO circle_member(circle_id,user_id,joined_on) VALUES(?,?,?)",
                id,
                actor,
                LocalDate.now(clock));
        return id;
    }

    @Transactional
    public void close(long actor, long id) {
        lockOrganiser(actor, id);
        require(
                jdbc.queryForObject(
                                "SELECT COUNT(*) FROM savings_entry WHERE circle_id=? AND"
                                    + " status='PENDING_REVIEW'",
                                Integer.class,
                                id)
                        == 0,
                "Review pending entries before closing.");
        jdbc.update(
                "UPDATE circle_invitation SET status='CANCELLED',responded_at=CURRENT_TIMESTAMP"
                    + " WHERE circle_id=? AND status='PENDING'",
                id);
        jdbc.update("UPDATE savings_circle SET status='CLOSED' WHERE id=?", id);
    }

    @Transactional
    public void addMember(long actor, long id, String email) {
        require(!lockOrganiser(actor, id).closed(), "This circle is closed.");
        Account member;
        try {
            member = accounts.byEmail(email);
        } catch (NotFound ex) {
            throw new InvalidInput("This friend needs to register before joining the circle.");
        }
        require(member.id() != actor, "You are already the organiser.");
        require(
                jdbc.queryForObject(
                                "SELECT COUNT(*) FROM circle_member WHERE circle_id=? AND user_id=?"
                                    + " AND status='ACTIVE'",
                                Integer.class,
                                id,
                                member.id())
                        == 0,
                "This person is already a member.");
        var existing =
                jdbc.queryForList(
                        "SELECT status FROM circle_invitation WHERE circle_id=? AND invitee_id=?",
                        String.class,
                        id,
                        member.id());
        require(
                existing.isEmpty() || !existing.getFirst().equals("PENDING"),
                "An invitation is already pending.");
        jdbc.update(
                "MERGE INTO circle_invitation(circle_id,invitee_id,status,created_at,responded_at)"
                    + " KEY(circle_id,invitee_id) VALUES(?,?,'PENDING',CURRENT_TIMESTAMP,NULL)",
                id,
                member.id());
        notice(
                member.id(),
                "Invitation to " + get(actor, id).name() + ". Open Savings pools > Invitations.");
    }

    public List<Invitation> invitations(long actor) {
        return jdbc.query(
                "SELECT i.id,i.circle_id,c.name,u.name organiser FROM circle_invitation i JOIN"
                    + " savings_circle c ON c.id=i.circle_id JOIN app_user u ON u.id=c.organiser_id"
                    + " WHERE i.invitee_id=? AND i.status='PENDING' AND c.status='ACTIVE' ORDER BY"
                    + " i.id DESC",
                (r, n) ->
                        new Invitation(r.getLong(1), r.getLong(2), r.getString(3), r.getString(4)),
                actor);
    }

    @Transactional
    public void respondInvitation(long actor, long invitationId, boolean accept) {
        var ids =
                jdbc.queryForList(
                        "SELECT circle_id FROM circle_invitation WHERE id=? AND invitee_id=?",
                        Long.class,
                        invitationId,
                        actor);
        if (ids.isEmpty()) throw new NotFound();
        long id = ids.getFirst();
        jdbc.queryForList("SELECT id FROM savings_circle WHERE id=? FOR UPDATE", Long.class, id);
        require(
                jdbc.queryForObject(
                                "SELECT COUNT(*) FROM savings_circle WHERE id=? AND"
                                    + " status='ACTIVE'",
                                Integer.class,
                                id)
                        == 1,
                "This circle is closed.");
        require(
                jdbc.update(
                                "UPDATE circle_invitation SET"
                                    + " status=?,responded_at=CURRENT_TIMESTAMP WHERE id=? AND"
                                    + " invitee_id=? AND status='PENDING'",
                                accept ? "ACCEPTED" : "DECLINED",
                                invitationId,
                                actor)
                        == 1,
                "This invitation has already been answered.");
        if (accept)
            jdbc.update(
                    "MERGE INTO circle_member(circle_id,user_id,joined_on,status,left_at)"
                        + " KEY(circle_id,user_id) VALUES(?,?,?,'ACTIVE',NULL)",
                    id,
                    actor,
                    LocalDate.now(clock));
        long leader =
                jdbc.queryForObject(
                        "SELECT organiser_id FROM savings_circle WHERE id=?", Long.class, id);
        notice(
                leader,
                "An invitation was "
                        + (accept ? "accepted" : "declined")
                        + ". Open Savings pools.");
    }

    @Transactional
    public void leave(long actor, long id) {
        lockMember(actor, id);
        Circle circle = get(actor, id);
        require(
                circle.organiserId() != actor,
                "The organiser must close the circle rather than leave it.");
        require(
                jdbc.queryForObject(
                                "SELECT COUNT(*) FROM savings_entry WHERE circle_id=? AND"
                                    + " member_id=? AND status='PENDING_REVIEW'",
                                Integer.class,
                                id,
                                actor)
                        == 0,
                "Resolve pending entries before leaving.");
        jdbc.update(
                "UPDATE circle_member SET status='LEFT',left_at=CURRENT_TIMESTAMP WHERE circle_id=?"
                    + " AND user_id=?",
                id,
                actor);
        notice(
                circle.organiserId(),
                accounts.byId(actor).name()
                        + " left "
                        + circle.name()
                        + ". Recorded history is retained; no refund is made.");
    }

    private void lockMember(long actor, long id) {
        jdbc.queryForList("SELECT id FROM savings_circle WHERE id=? FOR UPDATE", Long.class, id);
        require(
                jdbc.queryForObject(
                                "SELECT COUNT(*) FROM circle_member WHERE circle_id=? AND user_id=?"
                                    + " AND status='ACTIVE'",
                                Integer.class,
                                id,
                                actor)
                        == 1,
                "Only active members may do this.");
    }

    private void notice(long recipient, String message) {
        jdbc.update(
                "INSERT INTO notification(recipient_id,message,dedup_key) VALUES(?,?,?)",
                recipient,
                message,
                "circle:" + java.util.UUID.randomUUID());
    }

    @Transactional
    public void addEntry(
            long actor,
            long id,
            String kind,
            String amount,
            Long memberId,
            String date,
            String note) {
        lockMember(actor, id);
        Circle circle = get(actor, id);
        require(!circle.closed(), "This circle is closed.");
        require(
                List.of("CONTRIBUTION", "EXPENSE", "WITHDRAWAL").contains(kind),
                "Choose a valid entry type.");
        boolean organiser = circle.organiserId() == actor;
        require(
                organiser || (kind.equals("CONTRIBUTION") && memberId != null && memberId == actor),
                "Members may submit only their own contributions.");
        BigDecimal value = money(amount);
        LocalDate actual = Rules.date(date);
        require(
                !actual.isAfter(LocalDate.now(clock)),
                "An external exchange cannot be recorded with a future date.");
        require(
                !kind.equals("CONTRIBUTION") || memberId != null,
                "Attribute a contribution to a circle member.");
        if (memberId != null)
            require(
                    jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM circle_member WHERE circle_id=? AND"
                                            + " user_id=? AND status='ACTIVE'",
                                    Integer.class,
                                    id,
                                    memberId)
                            > 0,
                    "The person must be a member of this circle.");
        if (!kind.equals("CONTRIBUTION"))
            require(
                    value.compareTo(circle.balance()) <= 0,
                    "This entry exceeds the circle's recorded balance.");
        jdbc.update(
                "INSERT INTO"
                    + " savings_entry(circle_id,kind,amount,member_id,actual_date,note,status,submitted_by,reviewed_by,reviewed_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                id,
                kind,
                value,
                memberId,
                actual,
                text(note, 500, "Note"),
                organiser ? "APPROVED" : "PENDING_REVIEW",
                actor,
                organiser ? actor : null,
                organiser ? OffsetDateTime.now(clock) : null);
        if (!organiser)
            notice(circle.organiserId(), "Contribution awaiting review in " + circle.name() + ".");
    }

    public List<Circle> list(long actor) {
        return jdbc.query(
"""
SELECT c.*,u.name organiser_name,COALESCE((SELECT SUM(CASE WHEN e.kind='CONTRIBUTION' THEN e.amount ELSE -e.amount END)
FROM savings_entry e WHERE e.circle_id=c.id AND e.status='APPROVED'),0) balance FROM savings_circle c
JOIN app_user u ON u.id=c.organiser_id JOIN circle_member m ON m.circle_id=c.id
WHERE m.user_id=? ORDER BY c.id DESC
""",
                (r, n) ->
                        new Circle(
                                r.getLong("id"),
                                r.getString("name"),
                                r.getLong("organiser_id"),
                                r.getString("organiser_name"),
                                r.getString("currency"),
                                r.getBigDecimal("balance"),
                                r.getBigDecimal("target_amount"),
                                r.getBigDecimal("contribution_amount"),
                                r.getString("frequency"),
                                r.getObject("start_date", LocalDate.class),
                                r.getString("status")),
                actor);
    }

    public Circle get(long actor, long id) {
        return list(actor).stream()
                .filter(c -> c.id() == id)
                .findFirst()
                .orElseThrow(NotFound::new);
    }

    public List<Member> members(long actor, long id) {
        get(actor, id);
        return jdbc.query(
                "SELECT u.id,u.name,u.email,m.joined_on,m.status FROM circle_member m JOIN app_user"
                    + " u ON u.id=m.user_id WHERE m.circle_id=? ORDER BY u.name",
                (r, n) ->
                        new Member(
                                r.getLong(1),
                                r.getString(2),
                                r.getString(3),
                                r.getObject(4, LocalDate.class),
                                r.getString(5)),
                id);
    }

    public List<Entry> entries(long actor, long id) {
        get(actor, id);
        return jdbc.query(
                "SELECT e.*,u.name member_name FROM savings_entry e LEFT JOIN app_user u ON"
                        + " u.id=e.member_id WHERE e.circle_id=? ORDER BY e.id DESC",
                (r, n) ->
                        new Entry(
                                r.getLong("id"),
                                r.getString("kind"),
                                r.getBigDecimal("amount"),
                                r.getString("member_name"),
                                r.getObject("actual_date", LocalDate.class),
                                r.getString("note"),
                                r.getObject("created_at", OffsetDateTime.class),
                                r.getString("status"),
                                r.getObject("submitted_by", Long.class),
                                r.getString("review_reason")),
                id);
    }

    @Transactional
    public void withdrawEntry(long actor, long circleId, long entryId) {
        lockMember(actor, circleId);
        require(
                jdbc.update(
                                "UPDATE savings_entry SET status='WITHDRAWN' WHERE id=? AND"
                                    + " circle_id=? AND submitted_by=? AND status='PENDING_REVIEW'",
                                entryId,
                                circleId,
                                actor)
                        == 1,
                "Only your own pending contribution can be withdrawn.");
        notice(
                get(actor, circleId).organiserId(),
                "A pending contribution was withdrawn in " + get(actor, circleId).name() + ".");
    }

    @Transactional
    public void reviewEntry(
            long actor, long circleId, long entryId, boolean approve, String reason) {
        Circle circle = lockOrganiser(actor, circleId);
        require(!circle.closed(), "This circle is closed.");
        String explanation = text(reason, 500, "Review reason");
        require(approve || !explanation.isBlank(), "Explain why this contribution was rejected.");
        var rows =
                jdbc.queryForList(
                        "SELECT submitted_by FROM savings_entry WHERE id=? AND circle_id=? AND"
                            + " status='PENDING_REVIEW'",
                        Long.class,
                        entryId,
                        circleId);
        require(!rows.isEmpty(), "This entry is no longer pending review.");
        require(rows.getFirst() != actor, "You cannot review your own submission.");
        jdbc.update(
                "UPDATE savings_entry SET status=?,reviewed_by=?,reviewed_at=?,review_reason=?"
                    + " WHERE id=?",
                approve ? "APPROVED" : "REJECTED",
                actor,
                OffsetDateTime.now(clock),
                explanation,
                entryId);
        notice(
                rows.getFirst(),
                "Your contribution to "
                        + circle.name()
                        + " was "
                        + (approve ? "approved" : "rejected: " + explanation)
                        + ". Original submission and exchange dates are retained.");
    }

    private Circle lockOrganiser(long actor, long id) {
        var ids =
                jdbc.queryForList(
                        "SELECT id FROM savings_circle WHERE id=? AND organiser_id=? FOR UPDATE",
                        Long.class,
                        id,
                        actor);
        if (ids.isEmpty()) throw new NotFound();
        return get(actor, id);
    }
}
