package ph.edu.lendly;

import ph.edu.lendly.Models.Account;
import ph.edu.lendly.Models.Loan;
import ph.edu.lendly.Models.Notice;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.LongConsumer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.filechooser.FileNameExtensionFilter;

final class DesktopNotices extends JPanel {
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("MMM d, uuuu · h:mm a").withZone(ZoneId.of("Asia/Manila"));
    private final Account account;
    private final NotificationService notices;
    private final JTable table =
            DesktopUi.table(new String[] {"Status", "Date and time", "Loan", "Message"});
    private final JLabel summary = new JLabel();
    private final JButton readAll = new JButton("Mark all as read");
    private final JButton read = DesktopUi.secondary("Mark as read");
    private final JButton open = DesktopUi.primary("Open loan");
    private final JTextArea message = new JTextArea(3, 32);
    private final JLabel reliability = DesktopUi.heading("Not yet rated");
    private final JPanel dueCards = DesktopUi.stack();
    private final JButton export = DesktopUi.secondary("Export calendar (.ics)");
    private final JLabel exportStatus = DesktopUi.muted("No active due dates to export.");
    private final LongConsumer openLoan;
    private List<Loan> calendarLoans = List.of();
    private List<Notice> shown;

    DesktopNotices(
            Account account, NotificationService notices, LongConsumer openLoan, Runnable changed) {
        super(new BorderLayout(0, 12));
        this.account = account;
        this.notices = notices;
        this.openLoan = openLoan;
        setBackground(DesktopUi.BG);
        setBorder(BorderFactory.createEmptyBorder(26, 32, 22, 32));
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        header.add(DesktopUi.headingBlock("A friendly follow-up",
                "Upcoming returns and activity in your ledger."), BorderLayout.CENTER);
        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setOpaque(false);
        top.add(header, BorderLayout.NORTH);
        add(top, BorderLayout.NORTH);

        table.getAccessibleContext().setAccessibleName("Account notifications");
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(64);
        table.getColumnModel().getColumn(1).setPreferredWidth(152);
        table.getColumnModel().getColumn(2).setPreferredWidth(60);
        table.getColumnModel().getColumn(3).setPreferredWidth(500);
        var body = new JPanel(new java.awt.GridLayout(1, 2, 16, 0));
        var reminders = DesktopUi.card(new BorderLayout(0, 10), DesktopUi.WHITE, 14);
        var reminderHeading = DesktopUi.stack();
        DesktopUi.line(reminderHeading, DesktopUi.sectionTitle("Reminders"));
        DesktopUi.line(reminderHeading, DesktopUi.muted("Loans due within three days and overdue loans."));
        reminders.add(reminderHeading, BorderLayout.NORTH);
        var dueContent = new JPanel(new BorderLayout());
        dueContent.setBackground(DesktopUi.WHITE);
        dueContent.add(dueCards, BorderLayout.NORTH);
        reminders.add(DesktopUi.scroll(dueContent));
        var calendar = new JPanel(new BorderLayout(0, 6));
        calendar.setOpaque(false);
        calendar.add(exportStatus, BorderLayout.NORTH);
        var exportButtons = DesktopUi.actions(export);
        exportButtons.setOpaque(false);
        calendar.add(exportButtons, BorderLayout.CENTER);
        export.setEnabled(false);
        export.setToolTipText("Save private due dates to import into your calendar.");
        export.addActionListener(event -> exportCalendar());
        reminders.add(calendar, BorderLayout.SOUTH);
        body.add(reminders);
        var trust = DesktopUi.card(new BorderLayout(0, 12), DesktopUi.WHITE, 14);
        trust.setPreferredSize(new Dimension(230, 0));
        trust.add(DesktopUi.sectionTitle("Return reliability"), BorderLayout.NORTH);
        var score = new JPanel(new BorderLayout(0, 12));
        score.setOpaque(false);
        score.add(reliability, BorderLayout.NORTH);
        var explanation = new JTextArea("Based on confirmed on-time returns and repayments among your completed loans as borrower.");
        explanation.setLineWrap(true);
        explanation.setWrapStyleWord(true);
        explanation.setEditable(false);
        explanation.setOpaque(false);
        explanation.setForeground(DesktopUi.MUTED);
        score.add(explanation, BorderLayout.CENTER);
        trust.add(score, BorderLayout.CENTER);
        var note = DesktopUi.card(new BorderLayout(0, 6), new Color(0xEFECFA), 12);
        note.add(new JLabel("A record of returns"), BorderLayout.NORTH);
        note.add(DesktopUi.muted("Based on entered history."));
        trust.add(note, BorderLayout.SOUTH);
        body.add(trust);
        message.setEditable(false);
        message.setLineWrap(true);
        message.setWrapStyleWord(true);
        message.getAccessibleContext().setAccessibleName("Selected notification message");
        var messageScroll = DesktopUi.scroll(message);
        messageScroll.setPreferredSize(new Dimension(240, 70));
        var notifications = new JPanel(new BorderLayout(0, 8));
        var notificationHeader = new JPanel(new BorderLayout());
        notificationHeader.add(summary, BorderLayout.WEST);
        notificationHeader.add(DesktopUi.actions(readAll, read, open), BorderLayout.EAST);
        notifications.add(notificationHeader, BorderLayout.NORTH);
        notifications.add(DesktopUi.scroll(table));
        notifications.add(messageScroll, BorderLayout.SOUTH);
        var views = new javax.swing.JTabbedPane();
        views.addTab("Reminders & trust", body);
        views.addTab("Notifications", notifications);
        add(views);

        table.getSelectionModel()
                .addListSelectionListener(
                        event -> {
                            if (!event.getValueIsAdjusting()) showSelection();
                        });
        readAll.addActionListener(
                event ->
                        DesktopUi.attempt(
                                this,
                                () -> {
                                    notices.readAll(account.id());
                                    changed.run();
                                }));
        read.addActionListener(event -> DesktopUi.attempt(this, () -> markRead(selectedNotice(), changed)));
        open.addActionListener(
                event ->
                        DesktopUi.attempt(
                                this,
                                () -> {
                                    Notice selected = selectedNotice();
                                    if (selected != null && selected.loanId() != null) {
                                        openLoan.accept(selected.loanId());
                                        markRead(selected, changed);
                                    }
                                }));
        refresh();
    }

    void showTrust(String value) {
        reliability.setText(value);
    }

    void showReminders(List<Loan> records, LocalDate today) {
        calendarLoans = CalendarReminder.upcoming(records, account.id(), today);
        int deadlines = calendarLoans.size();
        export.setEnabled(deadlines > 0);
        exportStatus.setText(deadlines == 0 ? "No active due dates to export."
                : deadlines + (deadlines == 1 ? " due date" : " due dates") + " available for calendar export.");
        dueCards.removeAll();
        var due = records.stream()
                .filter(loan -> "ACTIVE".equals(loan.status())
                        && !loan.terms().dueDate().isAfter(today.plusDays(3)))
                .sorted(Comparator.comparing((Loan loan) -> loan.terms().dueDate().isBefore(today))
                        .thenComparing(loan -> loan.terms().dueDate())).toList();
        if (due.isEmpty()) DesktopUi.line(dueCards, DesktopUi.muted("No deadlines in this reminder window."));
        for (var loan : due) {
            LocalDate date = loan.terms().dueDate();
            boolean overdue = date.isBefore(today);
            var reminder = DesktopUi.card(new BorderLayout(0, 9),
                    overdue ? new Color(0xFAECEC) : DesktopUi.BG, 12);
            var title = new JPanel(new BorderLayout(8, 0));
            title.setOpaque(false);
            var label = new JLabel(DesktopLoans.subject(loan.terms()));
            DesktopUi.plain(label);
            title.add(label);
            var status = DesktopUi.card(new BorderLayout(),
                    overdue ? new Color(0xF8DDDE) : new Color(0xFFF1D6), 5);
            status.add(new JLabel(overdue ? "Overdue" : date.equals(today) ? "Due today" : "Due soon"));
            title.add(status, BorderLayout.EAST);
            reminder.add(title, BorderLayout.NORTH);
            String counterpart = loan.lenderId() == account.id()
                    ? "Borrower " + loan.borrowerName() : "Lender " + loan.lenderName();
            reminder.add(DesktopUi.muted(counterpart + " · Due " + date));
            var view = DesktopUi.secondary("View loan");
            view.addActionListener(event -> DesktopUi.attempt(this, () -> openLoan.accept(loan.id())));
            var actions = DesktopUi.actions(view);
            actions.setOpaque(false);
            reminder.add(actions, BorderLayout.SOUTH);
            DesktopUi.line(dueCards, reminder);
        }
        dueCards.revalidate();
        dueCards.repaint();
    }

    private void exportCalendar() {
        var chooser = new JFileChooser();
        chooser.setDialogTitle("Export loan reminders");
        chooser.setFileFilter(new FileNameExtensionFilter("Calendar file (*.ics)", "ics"));
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setSelectedFile(new File("lendly-reminders.ics"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        List<Loan> reminders = calendarLoans;
        if (reminders.isEmpty()) {
            DesktopUi.message(this, "No due dates to export", "Check active loans with a current or future due date.", false);
            return;
        }
        var file = chooser.getSelectedFile().toPath();
        if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ics")) {
            file = file.resolveSibling(file.getFileName() + ".ics");
        }
        Instant now = Instant.now();
        try {
            try {
                CalendarReminder.save(file, reminders, now, false);
            } catch (FileAlreadyExistsException exists) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw exists;
                if (!DesktopUi.confirm(this, "A file named " + file.getFileName() + " already exists.",
                        "Replace calendar file?", "Replace file")) return;
                CalendarReminder.save(file, reminders, now, true);
            }
            DesktopUi.message(this, "Calendar file saved",
                    "Import " + file.getFileName() + " into your calendar app and check its alert settings. "
                            + "If a loan changes, update the imported event in your calendar.", false);
        } catch (IOException | SecurityException exception) {
            DesktopUi.message(this, "Couldn’t save calendar file",
                    "Choose a writable folder and a valid calendar filename, then try again.", true);
        }
    }

    void refresh() {
        Notice previous = selectedNotice();
        var current = notices.list(account.id());
        if (current.equals(shown)) return;
        shown = current;
        DesktopUi.rows(
                table,
                current.stream()
                        .map(
                                notice ->
                                        new Object[] {
                                            notice.read() ? "Read" : "Unread",
                                                    TIME.format(notice.createdAt()),
                                            notice.loanId() == null ? "" : "#" + notice.loanId(),
                                                    notice.message()
                                        })
                        .toList());
        if (previous != null) {
            for (int row = 0; row < current.size(); row++) {
                if (current.get(row).id() == previous.id()) {
                    int viewRow = table.convertRowIndexToView(row);
                    if (viewRow >= 0) table.setRowSelectionInterval(viewRow, viewRow);
                    break;
                }
            }
        }
        long unread = current.stream().filter(notice -> !notice.read()).count();
        readAll.setEnabled(unread > 0);
        summary.setText(
                current.isEmpty()
                        ? "No notifications yet."
                        : unread + " unread · " + current.size() + " total · Manila time");
        showSelection();
    }

    private Notice selectedNotice() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= table.getRowCount() || shown == null) return null;
        int modelRow = table.convertRowIndexToModel(row);
        return modelRow >= shown.size() ? null : shown.get(modelRow);
    }

    private void showSelection() {
        Notice notice = selectedNotice();
        open.setEnabled(notice != null && notice.loanId() != null);
        read.setEnabled(notice != null && !notice.read());
        message.setText(notice == null ? "Select an update to read it here." : notice.message());
        message.setCaretPosition(0);
    }

    private void markRead(Notice notice, Runnable changed) {
        if (notice == null || notice.read()) return;
        notices.read(account.id(), notice.id());
        changed.run();
    }
}
