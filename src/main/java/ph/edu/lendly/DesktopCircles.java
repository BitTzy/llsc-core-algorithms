package ph.edu.lendly;

import ph.edu.lendly.Models.Account;
import ph.edu.lendly.Models.Circle;
import ph.edu.lendly.Models.Entry;
import ph.edu.lendly.Models.Member;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongFunction;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;

final class DesktopCircles extends JPanel {
    private final Account account;
    private final CircleService circles;
    private final Clock clock;
    private final Runnable changed;
    private final JTable circleTable;
    private final JTable memberTable;
    private final JTable entryTable;
    private final JLabel selection = new JLabel("Select a savings pool.");
    private final JLabel balance = DesktopUi.heading("Recorded balance");
    private final LongFunction<String> reliability;
    private final JLabel goal = DesktopUi.muted("");
    private final JLabel schedule = DesktopUi.muted("");
    private final GoalBar bar = new GoalBar();
    private final JButton closeCircle = new JButton("Close circle");
    private final JButton addMember = new JButton("Invite member");
    private final JButton invitationsButton = new JButton("Invitations");
    private final JButton withdrawButton = new JButton("Withdraw my pending entry");
    private final JButton leaveButton = new JButton("Leave circle");
    private final JButton approveButton = new JButton("Approve selected entry");
    private final JButton rejectButton = new JButton("Reject selected entry");
    private final JButton addEntry = DesktopUi.primary("Record entry");
    private final JComboBox<EntryKind> entryKind = new JComboBox<>(EntryKind.values());
    private final JComboBox<MemberChoice> entryMember = new JComboBox<>();
    private final JTextField entryAmount = new JTextField(8);
    private final JTextField entryCurrency = new JTextField(3);
    private final JTextField entryDate = new JTextField(12);
    private final JTextField entryNote = new JTextField(14);
    private Long entryCircleId;
    private List<Member> entryMembers = List.of();
    private List<Circle> visibleCircles;
    private String visibleMembers;
    private List<Entry> visibleEntries;
    private boolean updating;

    DesktopCircles(
            Account account,
            CircleService circles,
            Clock clock,
            Runnable changed,
            LongFunction<String> reliability) {
        super(new BorderLayout(0, 12));
        this.reliability = reliability;
        this.account = account;
        this.circles = circles;
        this.clock = clock;
        this.changed = changed;
        circleTable =
                makeTable(
                        "Saving circles",
                        "Circle",
                        "Organiser",
                        "Recorded balance",
                        "Progress",
                        "Your role");
        memberTable =
                makeTable(
                        "Circle members",
                        "Name",
                        "Email",
                        "Joined",
                        "Membership",
                        "Loan return reliability");
        entryTable =
                makeTable(
                        "Circle entries",
                        "Date",
                        "Type",
                        "Amount",
                        "Member",
                        "Note",
                        "Status",
                        "Submitted",
                        "Review reason");

        setBackground(DesktopUi.BG);
        setBorder(BorderFactory.createEmptyBorder(26, 32, 22, 32));
        JPanel header = new JPanel(new BorderLayout(8, 4));
        header.setOpaque(false);
        header.add(
                DesktopUi.headingBlock(
                        "Savings pools", "Shared savings goals with a clear contribution history."),
                BorderLayout.CENTER);
        JButton create = DesktopUi.primary("New circle");
        var headerButtons = new JPanel();
        headerButtons.setOpaque(false);
        headerButtons.add(invitationsButton);
        headerButtons.add(create);
        header.add(headerButtons, BorderLayout.EAST);
        invitationsButton.addActionListener(
                event -> DesktopUi.attempt(this, this::showInvitations));
        add(header, BorderLayout.NORTH);
        create.addActionListener(event -> DesktopUi.attempt(this, this::createCircle));

        JPanel body = new JPanel(new BorderLayout(16, 0));
        body.setOpaque(false);
        var pools = DesktopUi.card(new BorderLayout(0, 10), DesktopUi.SAGE, 12);
        var summary = DesktopUi.stack();
        DesktopUi.line(summary, balance);
        DesktopUi.line(summary, goal);
        bar.setPreferredSize(new Dimension(10, 10));
        DesktopUi.line(summary, bar);
        DesktopUi.line(summary, schedule);
        pools.add(summary, BorderLayout.NORTH);
        pools.add(DesktopUi.scroll(circleTable), BorderLayout.CENTER);
        var history = new JPanel(new GridLayout(2, 1, 0, 12));
        history.setOpaque(false);
        history.add(pools);
        JPanel details = DesktopUi.card(new BorderLayout(0, 8), DesktopUi.WHITE, 12);
        DesktopUi.plain(selection);
        details.add(DesktopUi.sectionTitle("Contribution & expense history"), BorderLayout.NORTH);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Entries", DesktopUi.scroll(entryTable));
        tabs.addTab("Members", DesktopUi.scroll(memberTable));
        details.add(tabs, BorderLayout.CENTER);
        history.add(details);
        body.add(history, BorderLayout.CENTER);
        var entry = DesktopUi.card(new BorderLayout(0, 12), DesktopUi.WHITE, 16);
        entry.setPreferredSize(new java.awt.Dimension(310, 0));
        var tools = DesktopUi.stack();
        DesktopUi.line(tools, DesktopUi.sectionTitle("Record an entry"));
        var hint =
                new javax.swing.JTextArea(
                        "Document an exchange already completed outside the app.");
        hint.setEditable(false);
        hint.setOpaque(false);
        hint.setLineWrap(true);
        hint.setWrapStyleWord(true);
        hint.setForeground(DesktopUi.MUTED);
        hint.setRows(2);
        DesktopUi.line(tools, hint);
        entryCurrency.setEditable(false);
        entryDate.setText(LocalDate.now(clock).toString());
        DesktopUi.stackedField(tools, "Entry type", entryKind);
        DesktopUi.stackedField(tools, "Member", entryMember);
        var amounts = new JPanel(new GridLayout(1, 2, 8, 0));
        amounts.setOpaque(false);
        var amountField = DesktopUi.stack();
        DesktopUi.stackedField(amountField, "Amount", entryAmount);
        amounts.add(amountField);
        var currencyField = DesktopUi.stack();
        DesktopUi.stackedField(currencyField, "Currency", entryCurrency);
        amounts.add(currencyField);
        DesktopUi.line(tools, amounts);
        DesktopUi.stackedField(tools, "Date (YYYY-MM-DD)", entryDate);
        DesktopUi.stackedField(tools, "Description", entryNote);
        DesktopUi.line(tools, addEntry);
        DesktopUi.line(tools, addMember);
        DesktopUi.line(tools, approveButton);
        DesktopUi.line(tools, rejectButton);
        DesktopUi.line(tools, withdrawButton);
        DesktopUi.line(tools, leaveButton);
        DesktopUi.line(tools, closeCircle);
        DesktopUi.line(tools, selection);
        var entryBody = new JPanel(new BorderLayout());
        entryBody.setBackground(DesktopUi.WHITE);
        entryBody.add(tools, BorderLayout.NORTH);
        var entryScroll = DesktopUi.scroll(entryBody);
        entryScroll.setHorizontalScrollBarPolicy(
                javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        entry.add(entryScroll);
        body.add(entry, BorderLayout.EAST);
        add(body, BorderLayout.CENTER);

        circleTable
                .getSelectionModel()
                .addListSelectionListener(
                        event -> {
                            if (!updating && !event.getValueIsAdjusting()) refreshDetails();
                        });
        addMember.addActionListener(event -> DesktopUi.attempt(this, this::addMember));
        closeCircle.addActionListener(event -> DesktopUi.attempt(this, this::closeSelected));
        addEntry.addActionListener(event -> DesktopUi.attempt(this, this::addEntry));
        withdrawButton.addActionListener(event -> DesktopUi.attempt(this, this::withdrawSelected));
        leaveButton.addActionListener(event -> DesktopUi.attempt(this, this::leaveCircle));
        approveButton.addActionListener(
                event -> DesktopUi.attempt(this, () -> reviewSelected(true)));
        rejectButton.addActionListener(
                event -> DesktopUi.attempt(this, () -> reviewSelected(false)));
        refresh();
    }

    void refresh() {
        invitationsButton.setText("Invitations (" + circles.invitations(account.id()).size() + ")");
        Circle previous = selectedCircle();
        var current = circles.list(account.id());
        if (!current.equals(visibleCircles)) {
            updating = true;
            try {
                visibleCircles = current;
                DesktopUi.rows(
                        circleTable,
                        current.stream()
                                .map(
                                        circle ->
                                                new Object[] {
                                                    circle.name(),
                                                    circle.organiserName(),
                                                    money(circle, circle.balance().toPlainString()),
                                                    progressLabel(circle),
                                                    circle.organiserId() == account.id()
                                                            ? "Organiser"
                                                            : "Member"
                                                })
                                .toList());
                if (previous != null) select(previous.id());
            } finally {
                updating = false;
            }
        }
        refreshDetails();
    }

    private void refreshDetails() {
        Circle circle = selectedCircle();
        boolean organiser = circle != null && circle.organiserId() == account.id();
        boolean active =
                circle != null
                        && circles.members(account.id(), circle.id()).stream()
                                .anyMatch(
                                        m -> m.id() == account.id() && m.status().equals("ACTIVE"));
        boolean editable = active && !circle.closed();
        boolean open = organiser && editable;
        leaveButton.setVisible(active && !organiser);
        withdrawButton.setVisible(active && !organiser && !circle.closed());
        approveButton.setVisible(open);
        rejectButton.setVisible(open);
        addEntry.setText(organiser ? "Record entry" : "Submit contribution");
        addMember.setVisible(open);
        addEntry.setVisible(editable);
        closeCircle.setVisible(open);
        balance.setText(
                circle == null
                        ? "Recorded balance"
                        : money(circle, circle.balance().toPlainString()));
        showGoal(circle);
        selection.setText(
                circle == null
                        ? "Select a savings pool."
                        : circle.closed()
                                ? "This circle is closed."
                                : organiser
                                        ? "You organise this circle."
                                        : active
                                                ? "Contributions need leader approval."
                                                : "You left. History is read-only.");
        var members =
                circle == null ? List.<Member>of() : circles.members(account.id(), circle.id());
        updateEntryForm(circle, members, editable);
        entryKind.setEnabled(open);
        entryMember.setEnabled(open);
        if (!organiser) {
            entryKind.setSelectedItem(EntryKind.CONTRIBUTION);
            for (int i = 0; i < entryMember.getItemCount(); i++)
                if (java.util.Objects.equals(entryMember.getItemAt(i).id(), account.id()))
                    entryMember.setSelectedIndex(i);
        }
        var memberRows =
                members.stream()
                        .map(
                                member ->
                                        new Object[] {
                                            member.name(),
                                            member.email(),
                                            member.joinedOn() == null
                                                    ? ""
                                                    : DesktopUi.displayDate(member.joinedOn()),
                                            member.status(),
                                            reliability.apply(member.id())
                                        })
                        .toList();
        String memberSnapshot =
                memberRows.stream().map(Arrays::toString).collect(Collectors.joining("|"));
        if (!memberSnapshot.equals(visibleMembers)) {
            visibleMembers = memberSnapshot;
            DesktopUi.rows(memberTable, memberRows);
        }
        var entries =
                circle == null ? List.<Entry>of() : circles.entries(account.id(), circle.id());
        if (!entries.equals(visibleEntries)) {
            visibleEntries = entries;
            DesktopUi.rows(
                    entryTable,
                    entries.stream()
                            .map(
                                    entry ->
                                            new Object[] {
                                                entry.actualDate(),
                                                DesktopUi.status(entry.kind()),
                                                money(circle, entry.amount().toPlainString()),
                                                entry.memberName() == null
                                                        ? ""
                                                        : entry.memberName(),
                                                entry.note(),
                                                DesktopUi.status(entry.status()),
                                                entry.createdAt().toLocalDate(),
                                                entry.reviewReason()
                                            })
                            .toList());
        }
    }

    private void createCircle() {
        var form = DesktopUi.stack();
        var name = new DesktopUi.HintField("", 24, "e.g. Project materials fund");
        var currency = new DesktopUi.HintField("PHP", 8, "PHP");
        var target = new DesktopUi.HintField("", 8, "e.g. 5000.00");
        var each = new DesktopUi.HintField("", 8, "e.g. 500.00");
        var frequency = new JComboBox<>(new String[] {"Weekly", "Every two weeks", "Monthly"});
        var start = new DesktopUi.HintField(LocalDate.now(clock).toString(), 12, "YYYY-MM-DD");
        DesktopUi.stackedField(form, "Circle name", name);
        DesktopUi.line(form, pair("Currency code", currency, "Savings target", target));
        DesktopUi.line(form, pair("Contribution amount", each, "How often", frequency));
        DesktopUi.stackedField(form, "First contribution date (YYYY-MM-DD)", start);
        var created = new long[1];
        if (DesktopUi.confirmValidated(
                this,
                form,
                "Create saving circle",
                "Create circle",
                () ->
                        created[0] =
                                circles.create(
                                        account.id(),
                                        name.getText(),
                                        currency.getText(),
                                        target.getText(),
                                        each.getText(),
                                        Models.Circle.FREQUENCIES.get(frequency.getSelectedIndex()),
                                        start.getText()))) {
            changed.run();
            select(created[0]);
        }
    }

    private static JPanel pair(
            String leftLabel, JComponent left, String rightLabel, JComponent right) {
        var row = new JPanel(new GridLayout(1, 2, 10, 0));
        row.setOpaque(false);
        var first = DesktopUi.stack();
        DesktopUi.stackedField(first, leftLabel, left);
        var second = DesktopUi.stack();
        DesktopUi.stackedField(second, rightLabel, right);
        row.add(first);
        row.add(second);
        return row;
    }

    private void addMember() {
        Circle circle = selectedCircle();
        if (circle == null) return;
        var form = DesktopUi.stack();
        var email = new DesktopUi.HintField("", 24, "name@example.com");
        DesktopUi.stackedField(form, "Registered email", email);
        if (DesktopUi.confirmValidated(
                this,
                form,
                "Invite circle member",
                "Send invitation",
                () -> circles.addMember(account.id(), circle.id(), email.getText()))) {
            changed.run();
        }
    }

    private void showInvitations() {
        var pending = circles.invitations(account.id());
        if (pending.isEmpty()) {
            javax.swing.JOptionPane.showMessageDialog(this, "No pending circle invitations.");
            return;
        }
        String[] labels =
                pending.stream()
                        .map(i -> i.circleName() + " (#" + i.circleId() + ") - invited by " + i.organiserName())
                        .toArray(String[]::new);
        Object choice =
                javax.swing.JOptionPane.showInputDialog(
                        this,
                        "Choose an invitation",
                        "Circle invitations",
                        javax.swing.JOptionPane.QUESTION_MESSAGE,
                        null,
                        labels,
                        labels[0]);
        if (choice == null) return;
        int index = java.util.Arrays.asList(labels).indexOf(choice);
        int decision =
                javax.swing.JOptionPane.showOptionDialog(
                        this,
                        "Join "
                                + pending.get(index).circleName()
                                + "? Membership begins only if you accept.",
                        "Circle invitation",
                        javax.swing.JOptionPane.DEFAULT_OPTION,
                        javax.swing.JOptionPane.QUESTION_MESSAGE,
                        null,
                        new String[] {"Accept", "Decline", "Cancel"},
                        "Accept");
        if (decision != 0 && decision != 1) return;
        circles.respondInvitation(account.id(), pending.get(index).id(), decision == 0);
        changed.run();
    }

    private void withdrawSelected() {
        Circle circle = selectedCircle();
        int row = entryTable.getSelectedRow();
        if (circle == null || row < 0 || visibleEntries == null)
            throw new Rules.InvalidInput("Select your pending contribution first.");
        Entry entry = visibleEntries.get(entryTable.convertRowIndexToModel(row));
        if (DesktopUi.confirm(
                this,
                "Withdraw this unapproved submission? It stays in history and does not affect the"
                    + " balance.",
                "Withdraw contribution",
                "Withdraw")) {
            circles.withdrawEntry(account.id(), circle.id(), entry.id());
            changed.run();
        }
    }

    private void leaveCircle() {
        Circle circle = selectedCircle();
        if (circle == null) return;
        if (DesktopUi.confirm(
                this,
                "Leaving keeps your recorded history and does not issue a refund. Resolve pending"
                    + " entries first. Continue?",
                "Leave " + circle.name() + "?",
                "Leave circle")) {
            circles.leave(account.id(), circle.id());
            changed.run();
        }
    }

    private void reviewSelected(boolean approve) {
        Circle circle = selectedCircle();
        int row = entryTable.getSelectedRow();
        if (circle == null || row < 0 || visibleEntries == null)
            throw new Rules.InvalidInput("Select a pending contribution in Entries first.");
        Entry entry = visibleEntries.get(entryTable.convertRowIndexToModel(row));
        String reason = "";
        if (!approve) {
            reason =
                    javax.swing.JOptionPane.showInputDialog(
                            this, "Why are you rejecting this contribution?");
            if (reason == null) return;
        } else if (!DesktopUi.confirm(
                this,
                "Confirm this external contribution? Approval updates the recorded balance; the"
                    + " original dates stay unchanged.",
                "Approve contribution",
                "Approve")) return;
        circles.reviewEntry(account.id(), circle.id(), entry.id(), approve, reason);
        changed.run();
    }

    private void closeSelected() {
        Circle circle = selectedCircle();
        if (circle == null) return;
        if (DesktopUi.confirm(
                this,
                "Closing stops new members and entries. The history stays visible to everyone.",
                "Close " + circle.name() + "?",
                "Close circle")) {
            circles.close(account.id(), circle.id());
            changed.run();
        }
    }

    private void showGoal(Circle circle) {
        if (circle == null) {
            goal.setText(" ");
            schedule.setText(" ");
            bar.fraction(0);
            return;
        }
        if (circle.targetAmount() == null) {
            goal.setText("No savings target set for this circle.");
            schedule.setText(" ");
            bar.fraction(0);
            return;
        }
        goal.setText(
                money(circle, circle.balance().toPlainString())
                        + " of "
                        + money(circle, circle.targetAmount().toPlainString())
                        + " · "
                        + progressLabel(circle)
                        + (circle.goalReached() ? " · Goal reached" : ""));
        bar.fraction(Math.min(1.0, circle.progressPercentage() / 100.0));
        var next = circle.nextContributionDue(LocalDate.now(clock));
        schedule.setText(
                circle.closed()
                        ? circle.scheduleLabel()
                        : circle.scheduleLabel()
                                + (next == null
                                        ? ""
                                        : " · next on " + DesktopUi.displayDate(next)));
    }

    private static String progressLabel(Circle circle) {
        if (circle.targetAmount() == null) return "—";
        String text = String.format(java.util.Locale.ROOT, "%.1f", circle.progressPercentage());
        return (text.endsWith(".0") ? text.substring(0, text.length() - 2) : text)
                + "%"
                + (circle.closed() ? " (closed)" : "");
    }

    /** A slim rounded bar showing how much of the savings target has been recorded. */
    private static final class GoalBar extends JComponent {
        private double fraction;

        void fraction(double value) {
            fraction = value;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics.create();
            g.setRenderingHints(
                    new RenderingHints(
                            RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON));
            int width = getWidth();
            int height = getHeight();
            g.setColor(new Color(0xDCE5DB));
            g.fillRoundRect(0, 0, width, height, height, height);
            g.setColor(new Color(0x265F4A));
            g.fillRoundRect(0, 0, (int) Math.round(width * fraction), height, height, height);
            g.dispose();
        }
    }

    private void addEntry() {
        Circle circle = selectedCircle();
        if (circle == null) return;
        EntryKind selected = (EntryKind) entryKind.getSelectedItem();
        MemberChoice person = (MemberChoice) entryMember.getSelectedItem();
        circles.addEntry(
                account.id(),
                circle.id(),
                selected.name(),
                entryAmount.getText(),
                person.id(),
                entryDate.getText(),
                entryNote.getText());
        entryAmount.setText("");
        entryNote.setText("");
        changed.run();
    }

    private void updateEntryForm(Circle circle, List<Member> members, boolean organiser) {
        Long circleId = circle == null ? null : circle.id();
        entryCurrency.setText(circle == null ? "" : circle.currency());
        for (var input : List.of(entryKind, entryMember, entryAmount, entryDate, entryNote)) {
            input.setEnabled(organiser);
        }
        boolean switched = !java.util.Objects.equals(entryCircleId, circleId);
        if (switched) {
            entryAmount.setText("");
            entryNote.setText("");
            entryKind.setSelectedIndex(0);
            entryDate.setText(LocalDate.now(clock).toString());
        }
        if (!switched && members.equals(entryMembers)) return;
        var previous = (MemberChoice) entryMember.getSelectedItem();
        Long memberId = switched || previous == null ? Long.valueOf(account.id()) : previous.id();
        entryCircleId = circleId;
        entryMembers = members;
        entryMember.removeAllItems();
        entryMember.addItem(new MemberChoice(null, "No member"));
        for (var member : members) {
            var choice = new MemberChoice(member.id(), member.name());
            entryMember.addItem(choice);
            if (java.util.Objects.equals(memberId, choice.id()))
                entryMember.setSelectedItem(choice);
        }
    }

    private Circle selectedCircle() {
        int viewRow = circleTable.getSelectedRow();
        return viewRow < 0 || visibleCircles == null
                ? null
                : visibleCircles.get(circleTable.convertRowIndexToModel(viewRow));
    }

    private void select(long id) {
        for (int row = 0; row < visibleCircles.size(); row++) {
            if (visibleCircles.get(row).id() == id) {
                int viewRow = circleTable.convertRowIndexToView(row);
                if (viewRow >= 0) circleTable.setRowSelectionInterval(viewRow, viewRow);
                return;
            }
        }
    }

    private static JTable makeTable(String description, String... headers) {
        JTable table = DesktopUi.table(headers);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getAccessibleContext().setAccessibleName(description);
        return table;
    }

    private static String money(Circle circle, String amount) {
        return circle.currency() + " " + amount;
    }

    private record MemberChoice(Long id, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private enum EntryKind {
        CONTRIBUTION,
        EXPENSE,
        WITHDRAWAL;

        @Override
        public String toString() {
            return DesktopUi.status(name());
        }
    }
}
