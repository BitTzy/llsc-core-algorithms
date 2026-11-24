package ph.edu.lendly;

import static ph.edu.lendly.DesktopUi.*;
import static ph.edu.lendly.Models.*;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.Color;
import java.awt.Component;
import java.awt.GridLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;

final class DesktopLoans extends JPanel {
    private static final String[] STATES = {
        "", "PENDING_TERMS", "AWAITING_HANDOVER", "ACTIVE", "COMPLETED", "DECLINED", "CANCELLED"
    };

    private final Account account;
    private final LoanService loans;
    private final ReceiptService receipts;
    private final Clock clock;
    private final String role;
    private final Runnable changed;
    private final JTextField search = new JTextField(16) {
        @Override
        protected void paintComponent(java.awt.Graphics graphics) {
            super.paintComponent(graphics);
            if (!getText().isEmpty() || isFocusOwner()) return;
            var g = graphics.create();
            g.setColor(MUTED);
            g.setFont(getFont());
            var metrics = g.getFontMetrics();
            g.drawString("Search by item or friend", getInsets().left,
                    (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent());
            g.dispose();
        }
    };
    private final JComboBox<String> state = new JComboBox<>();
    private final JComboBox<String> type = new JComboBox<>(new String[] {"All types", "Belongings", "Money"});
    private final JLabel summary = muted("");
    private final JTable list =
            table(new String[] {"Loan", "Item or amount", "Other account", "Expected return", "Status", "Record action"});
    private final JPanel detail = new JPanel(new BorderLayout());
    private final JSplitPane split;
    private JScrollPane loanForm;
    private List<Loan> visible = List.of();
    private LoanView shown;
    private JTabbedPane detailTabs;
    private boolean replacingRows;
    private String appliedQuery = "";
    private String appliedStatus = "";

    DesktopLoans(
            Account account,
            LoanService loans,
            ReceiptService receipts,
            Clock clock,
            String role,
            Runnable changed) {
        super(new BorderLayout(0, 10));
        this.account = account;
        this.loans = loans;
        this.receipts = receipts;
        this.clock = clock;
        this.role = role;
        this.changed = changed;

        for (String code : STATES) state.addItem(code.isEmpty() ? "All statuses" : status(code));
        search.addActionListener(e -> attempt(this, this::applyFilters));
        var searchDelay = new Timer(180, event -> {
            if (search.getText().length() <= 100) attempt(this, this::applyFilters);
        });
        searchDelay.setRepeats(false);
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent event) { searchDelay.restart(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent event) { searchDelay.restart(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent event) { searchDelay.restart(); }
        });
        boolean lending = role.equals("lending");
        var create = primary(lending ? "+ Propose a loan" : "Request a loan");
        create.addActionListener(e -> createLoan());
        var searchLabel = new JLabel("Search records");
        searchLabel.setLabelFor(search);
        state.getAccessibleContext().setAccessibleName("Loan status filter");
        type.getAccessibleContext().setAccessibleName("Loan type filter");
        state.addActionListener(e -> attempt(this, this::applyFilters));
        type.addActionListener(e -> attempt(this, this::applyFilters));
        var columns = list.getColumnModel();
        list.setRowHeight(57);
        columns.getColumn(0).setMaxWidth(54);
        columns.getColumn(2).setHeaderValue(lending ? "Borrower" : "Lender");
        columns.getColumn(1).setPreferredWidth(245);
        columns.getColumn(4).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean selected, boolean focused, int row, int column) {
                var label = (JLabel) super.getTableCellRendererComponent(table, value, selected,
                        false, row, column);
                label.setHorizontalAlignment(SwingConstants.CENTER);
                label.setForeground(String.valueOf(value).contains("overdue")
                        ? new Color(0x813742) : INK);
                label.setOpaque(false);
                return label;
            }

            @Override
            protected void paintComponent(java.awt.Graphics graphics) {
                var g = (java.awt.Graphics2D) graphics.create();
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(getBackground());
                g.fillRect(0, 0, getWidth(), getHeight());
                String label = getText();
                g.setColor(label.contains("overdue") ? new Color(0xFAECEC)
                        : label.equals("Active") ? SAGE : BG);
                int width = Math.min(getWidth(), g.getFontMetrics(getFont()).stringWidth(label) + 18);
                g.fillRoundRect((getWidth() - width) / 2, (getHeight() - 23) / 2,
                        width, 23, 23, 23);
                g.dispose();
                super.paintComponent(graphics);
            }
        });
        var recordButton = secondary("");
        var actionCell = new JPanel(new java.awt.GridBagLayout());
        actionCell.add(recordButton);
        columns.getColumn(5).setCellRenderer((table, value, selected, focused, row, column) -> {
            recordButton.setText(String.valueOf(value));
            actionCell.setBackground(selected ? SAGE : WHITE);
            return actionCell;
        });
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                if (list.rowAtPoint(event.getPoint()) >= 0 && list.columnAtPoint(event.getPoint()) == 5) {
                    openSelectedAction();
                }
            }
        });
        list.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "openRecord");
        list.getActionMap().put("openRecord", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                openSelectedAction();
            }
        });
        list.getAccessibleContext()
                .setAccessibleName(
                        role.equals("lending") ? "Lending records" : "Borrowing records");
        list.getSelectionModel()
                .addListSelectionListener(
                        e -> {
                            if (!e.getValueIsAdjusting() && !replacingRows)
                                attempt(this, this::showSelected);
                        });

        setBorder(BorderFactory.createEmptyBorder(26, 32, 22, 32));
        var upper = new JPanel(new BorderLayout(0, 14));
        var header = new JPanel(new BorderLayout(12, 0));
        header.add(headingBlock(lending ? "Your lending ledger" : "Your borrowing ledger",
                lending ? "A clear record of the things and money you have lent."
                        : "Track what you borrowed and when it is due."), BorderLayout.CENTER);
        header.add(actions(create), BorderLayout.EAST);
        var filters = new JPanel(new BorderLayout(10, 0));
        var searchField = new JPanel(new BorderLayout(0, 5));
        searchField.add(searchLabel, BorderLayout.NORTH);
        searchField.add(search, BorderLayout.CENTER);
        filters.add(searchField, BorderLayout.CENTER);
        var selectors = new JPanel(new GridLayout(1, 2, 10, 0));
        selectors.setPreferredSize(new Dimension(330, 0));
        selectors.add(filter("Loan type", type));
        selectors.add(filter("Status", state));
        filters.add(selectors, BorderLayout.EAST);
        var top = new JPanel(new BorderLayout(0, 13));
        top.add(header, BorderLayout.NORTH);
        top.add(summary, BorderLayout.CENTER);
        top.add(filters, BorderLayout.SOUTH);
        upper.add(top, BorderLayout.NORTH);
        upper.add(scroll(list), BorderLayout.CENTER);
        var tip = card(new BorderLayout(0, 4), SAGE, 12);
        tip.add(new JLabel("Keep the record up to date"), BorderLayout.NORTH);
        tip.add(muted("After a return or repayment outside Lendly, update its record here."),
                BorderLayout.CENTER);
        upper.add(tip, BorderLayout.SOUTH);
        upper.setMinimumSize(new Dimension(0, 285));
        detail.setMinimumSize(new Dimension(0, 180));
        split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, upper, detail);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setResizeWeight(0.55);
        add(split, BorderLayout.CENTER);
        showEmpty();
    }

    private static JPanel filter(String name, JComponent control) {
        var panel = new JPanel(new BorderLayout(0, 5));
        panel.setOpaque(false);
        var label = new JLabel(name);
        label.setLabelFor(control);
        panel.add(label, BorderLayout.NORTH);
        panel.add(control, BorderLayout.CENTER);
        return panel;
    }

    void createLoan() {
        attempt(this, () -> termsDialog(null));
    }

    private void openSelectedAction() {
        var loan = selectedLoan();
        if (loan == null) return;
        attempt(this, () -> {
            showSelected();
            if (canRecord(loan) && !returnPending(shown)) submit(loan, false);
        });
    }

    private boolean canRecord(Loan loan) {
        return "ACTIVE".equals(loan.status()) && loan.borrowerId() == account.id();
    }

    void refresh() {
        var selected = selectedLoan();
        replaceRows(selected == null ? -1 : selected.id());
    }

    void open(long loanId) {
        closeLoanForm();
        search.setText("");
        state.setSelectedIndex(0);
        type.setSelectedIndex(0);
        appliedQuery = "";
        appliedStatus = "";
        replaceRows(loanId);
    }

    private void applyFilters() {
        appliedQuery = Rules.text(search.getText(), 100, "Search");
        appliedStatus = STATES[state.getSelectedIndex()];
        refresh();
    }

    private void replaceRows(long selection) {
        var records = loans.list(account.id(), role, "", "");
        long active = records.stream().filter(loan -> "ACTIVE".equals(loan.status())).count();
        long overdue = records.stream().filter(loan -> stateText(loan).contains("overdue")).count();
        summary.setText(active + " active   ·   " + overdue + " overdue   ·   "
                + DesktopWindow.activeBalance(records));
        var result = loans.list(account.id(), role, appliedQuery, appliedStatus).stream()
                .filter(loan -> switch (type.getSelectedIndex()) {
                    case 1 -> !"MONEY".equals(loan.terms().kind());
                    case 2 -> "MONEY".equals(loan.terms().kind());
                    default -> true;
                }).toList();
        List<Object[]> data =
                result.stream()
                        .map(
                                loan ->
                                        new Object[] {
                                            loan.id(),
                                            subject(loan.terms()),
                                            role.equals("lending")
                                                    ? loan.borrowerName()
                                                    : loan.lenderName(),
                                            DesktopUi.displayDate(loan.terms().dueDate()),
                                            stateText(loan),
                                            !canRecord(loan) ? "View record"
                                                    : "MONEY".equals(loan.terms().kind())
                                                            ? "Record repayment" : "Record return"
                                        })
                        .toList();
        replacingRows = true;
        try {
            visible = result;
            rows(list, data);
            list.clearSelection();
            for (int i = 0; i < result.size(); i++) {
                if (result.get(i).id() != selection) continue;
                int row = list.convertRowIndexToView(i);
                list.setRowSelectionInterval(row, row);
                break;
            }
        } finally {
            replacingRows = false;
        }
        showSelected();
    }

    private Loan selectedLoan() {
        int row = list.getSelectedRow();
        return row < 0 ? null : visible.get(list.convertRowIndexToModel(row));
    }

    private void showSelected() {
        Loan selected = selectedLoan();
        if (selected == null) {
            if (shown != null) showEmpty();
            return;
        }
        long actor = account.id();
        long id = selected.id();
        Loan loan = loans.get(actor, id);
        var confirmations = loans.confirmations(actor, id);
        var activities = loans.activities(actor, id);
        var versions = loans.versions(actor, id);
        String borrowerTrust =
                trust(loans.list(loan.borrowerId(), "borrowing", "", ""), loan.borrowerId());
        var snapshot =
                new LoanView(
                        loan, confirmations, activities, versions, borrowerTrust, stateText(loan));
        if (snapshot.equals(shown)) return;

        int selectedTab =
                shown != null && shown.loan().id() == id ? detailTabs.getSelectedIndex() : 0;
        shown = snapshot;
        detailTabs = new JTabbedPane();
        detailTabs.addTab("Agreement", agreement(snapshot));
        detailTabs.addTab("Activity (" + activities.size() + ")", activities(snapshot));
        detailTabs.addTab("Terms history", termsHistory(snapshot));
        detailTabs.setSelectedIndex(selectedTab);
        detail.removeAll();
        detail.add(detailTabs);
        if (!detail.isVisible()) {
            detail.setVisible(true);
            split.setDividerSize(8);
            split.setDividerLocation(Math.max(390, (int) (split.getHeight() * 0.62)));
        }
        detail.revalidate();
        detail.repaint();
    }

    private void showEmpty() {
        shown = null;
        detail.removeAll();
        detail.setVisible(false);
        split.setDividerSize(0);
        var hint =
                new JLabel(
                        "Select a loan above to view its agreement and activity.",
                        SwingConstants.CENTER);
        hint.setForeground(MUTED);
        detail.add(hint);
        detail.revalidate();
        detail.repaint();
    }

    private JComponent agreement(LoanView view) {
        Loan loan = view.loan();
        long actor = account.id();
        boolean lender = loan.lenderId() == actor;
        boolean pending = loan.status().equals("PENDING_TERMS");
        boolean awaiting = loan.status().equals("AWAITING_HANDOVER");
        boolean active = loan.status().equals("ACTIVE");
        var pendingRecords = view.activities().stream()
                .filter(activity -> awaitingReview(loan, activity))
                .toList();
        boolean handedOver = awaiting && !pendingRecords.isEmpty();
        boolean confirmed = isConfirmed(view, actor, loan.currentVersion());
        var body = new JPanel(new BorderLayout(0, 10));
        body.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        body.add(heading("Loan #" + loan.id() + "  |  " + view.displayState()), BorderLayout.NORTH);

        var fields = form();
        field(fields, "Subject", text(subject(loan.terms()), 2));
        field(
                fields,
                "Lender",
                value(
                        party(
                                loan.lenderName(),
                                lender,
                                isConfirmed(view, loan.lenderId(), loan.currentVersion()))));
        field(
                fields,
                "Borrower",
                value(
                        party(
                                loan.borrowerName(),
                                !lender,
                                isConfirmed(view, loan.borrowerId(), loan.currentVersion()))));
        field(
                fields,
                "Agreed handover and due",
                value(loan.terms().startDate() + " to " + loan.terms().dueDate()));
        field(fields, "Terms version", value(String.valueOf(loan.currentVersion())));
        field(fields, "Borrower trust", value(view.trust()));
        if (loan.terms().kind().equals("MONEY") && (active || loan.status().equals("COMPLETED"))) {
            field(
                    fields,
                    "Confirmed balance",
                    value(loan.terms().currency() + " " + loan.balance().toPlainString()));
        }
        if (loan.completedOn() != null)
            field(fields, "Completed on", value(loan.completedOn().toString()));
        field(fields, "Next step", text(nextStep(loan, confirmed, pendingRecords), 2));
        body.add(fields, BorderLayout.CENTER);

        var buttons = actions();
        if (pending && !confirmed) buttons.add(action("Confirm terms", () -> confirmTerms(loan)));
        if (pending || (awaiting && !handedOver)) {
            buttons.add(action("Revise terms", () -> termsDialog(loan)));
            if (pending && loan.terms().proposedBy() != actor) {
                buttons.add(action("Decline terms", () -> close(loan, true)));
            }
            buttons.add(action("Cancel proposal", () -> close(loan, false)));
        }
        if (!pendingRecords.isEmpty()) {
            Activity latest = pendingRecords.getFirst();
            if (pendingRecords.size() == 1 && latest.submittedBy() != actor) {
                String label = "Review " + latest.kind().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
                buttons.add(action(label, () -> review(loan, latest)));
            } else {
                buttons.add(action("View pending activity", this::selectActivityTab));
            }
        }
        if (awaiting && lender && !handedOver)
            buttons.add(action("Record handover", () -> submit(loan, true)));
        if (canRecord(loan) && !returnPending(view))
            buttons.add(
                    action(
                            loan.terms().kind().equals("MONEY")
                                    ? "Record repayment"
                                    : "Record item return",
                            () -> submit(loan, false)));
        var top = new JPanel(new BorderLayout());
        top.add(body, BorderLayout.NORTH);
        var panel = new JPanel(new BorderLayout());
        panel.add(scroll(top), BorderLayout.CENTER);
        if (buttons.getComponentCount() > 0) panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private String nextStep(Loan loan, boolean confirmed, List<Activity> pendingRecords) {
        boolean lender = loan.lenderId() == account.id();
        String other = counterpartName(loan);
        return switch (loan.status()) {
            case "PENDING_TERMS" -> confirmed
                    ? "Waiting for " + other + " to agree to this version."
                    : "Check the lender, borrower, amount or item and both dates.";
            case "AWAITING_HANDOVER" -> {
                if (!pendingRecords.isEmpty()) {
                    yield lender
                            ? "Waiting for " + other + " to review your handover."
                            : "Check the handover recorded by " + other + " to activate the loan.";
                }
                yield lender
                        ? "Record the handover after you give the money or item to " + other + "."
                        : "Waiting for " + other + " to record the handover.";
            }
            case "ACTIVE" -> {
                boolean money = loan.terms().kind().equals("MONEY");
                String exchange = money ? "repayment" : "item return";
                if (!pendingRecords.isEmpty()) {
                    yield lender
                            ? "Review the pending " + exchange + " from " + other + "."
                            : "Waiting for " + other + " to review your " + exchange + ".";
                }
                yield lender
                        ? "Waiting for " + other + " to record a " + exchange + "."
                        : "Record the " + exchange + " after it takes place outside Lendly.";
            }
            default -> "See the terms and activity history.";
        };
    }

    private static boolean returnPending(LoanView view) {
        return view.activities().stream().anyMatch(activity ->
                "ITEM_RETURN".equals(activity.kind())
                        && awaitingReview(view.loan(), activity));
    }

    private static boolean awaitingReview(Loan loan, Activity activity) {
        return activity.version() == loan.currentVersion()
                && "PENDING_REVIEW".equals(activity.status());
    }

    private JComponent activities(LoanView view) {
        var records = view.activities();
        var activityTable =
                table(
                        new String[] {
                            "Activity", "Recorded by", "Actual date", "Amount", "Status", "Receipt"
                        });
        rows(
                activityTable,
                records.stream()
                        .map(
                                a ->
                                        new Object[] {
                                            status(a.kind()),
                                            a.submitterName(),
                                            a.actualDate(),
                                            a.amount() == null ? "" : a.amount(),
                                            status(a.status()),
                                            a.receiptId() == null ? "" : "Yes"
                                        })
                        .toList());
        var panel = new JPanel(new BorderLayout(0, 4));
        panel.add(scroll(activityTable), BorderLayout.CENTER);
        var viewButton = new JButton("View record");
        var preview = new JButton("Open receipt");
        var review = new JButton("Review record");
        var withdraw = new JButton("Withdraw record");
        for (JButton button : List.of(viewButton, preview, review, withdraw))
            button.setEnabled(false);
        viewButton.addActionListener(
                e -> attempt(this, () -> activityDetails(view.loan(), activityAt(activityTable, records))));
        preview.addActionListener(
                e -> attempt(this, () -> previewReceipt(activityAt(activityTable, records))));
        review.addActionListener(
                e -> attempt(this, () -> review(view.loan(), activityAt(activityTable, records))));
        withdraw.addActionListener(
                e ->
                        attempt(
                                this,
                                () -> withdraw(view.loan(), activityAt(activityTable, records))));
        activityTable
                .getSelectionModel()
                .addListSelectionListener(
                        e -> {
                            boolean selected = activityTable.getSelectedRow() >= 0;
                            viewButton.setEnabled(selected);
                            Activity a = selected ? activityAt(activityTable, records) : null;
                            boolean pending = a != null && awaitingReview(view.loan(), a);
                            preview.setEnabled(a != null && a.receiptId() != null);
                            review.setEnabled(pending && a.submittedBy() != account.id());
                            withdraw.setEnabled(pending && a.submittedBy() == account.id());
                        });
        var buttons = actions(viewButton, preview, review, withdraw);
        if (records.isEmpty()) {
            buttons.add(value("No handover, repayment or return records yet."));
        } else {
            int selected = 0;
            for (int index = 0; index < records.size(); index++) {
                if (awaitingReview(view.loan(), records.get(index))) {
                    selected = index;
                    break;
                }
            }
            activityTable.setRowSelectionInterval(selected, selected);
        }
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent termsHistory(LoanView view) {
        var history =
                table(new String[] {"Version", "Subject", "Start", "Due", "Lender", "Borrower"});
        rows(
                history,
                view.versions().stream()
                        .map(
                                t ->
                                        new Object[] {
                                            t.version(),
                                            subject(t),
                                            t.startDate(),
                                            t.dueDate(),
                                            confirmationText(
                                                    view, view.loan().lenderId(), t.version()),
                                            confirmationText(
                                                    view, view.loan().borrowerId(), t.version())
                                        })
                        .toList());
        return scroll(history);
    }

    private void termsDialog(Loan loan) {
        Terms terms = loan == null ? null : loan.terms();
        boolean lending = role.equals("lending");
        boolean inline = lending && loan == null;
        if (inline && loanForm != null) return;
        var friend = new JTextField(22);
        var kind = new JComboBox<>(new String[] {"Money", "Item"});
        var amount =
                new JTextField(
                        terms == null || terms.amount() == null
                                ? ""
                                : terms.amount().toPlainString(),
                        14);
        var currency =
                new JTextField(
                        terms == null || terms.currency() == null ? "PHP" : terms.currency(), 8);
        var item =
                new JTextField(
                        terms == null || terms.itemDescription() == null
                                ? ""
                                : terms.itemDescription(),
                        22);
        var start = new JTextField((terms == null ? today() : terms.startDate()).toString(), 12);
        var due =
                new JTextField(
                        (terms == null ? today().plusDays(7) : terms.dueDate()).toString(), 12);
        var acknowledge = new JCheckBox("I agree to these terms and dates.");
        if (terms != null && terms.kind().equals("ITEM")) kind.setSelectedIndex(1);
        Runnable toggle =
                () -> {
                    boolean money = kind.getSelectedIndex() == 0;
                    amount.setEnabled(money);
                    currency.setEnabled(money);
                    item.setEnabled(!money);
                };
        toggle.run();
        kind.addActionListener(e -> toggle.run());
        var fields = form();
        if (loan == null)
            field(fields, lending ? "Borrower's account email" : "Lender's account email", friend);
        else {
            field(fields, "Lender", value(loan.lenderName()));
            field(fields, "Borrower", value(loan.borrowerName()));
        }
        field(fields, "Type", kind);
        field(fields, "Amount", amount);
        field(fields, "Currency", currency);
        field(fields, "Item description", item);
        field(fields, "Handover date (YYYY-MM-DD)", start);
        field(fields, "Due date (YYYY-MM-DD)", due);
        field(fields, "Acknowledgement", acknowledge);
        String title =
                loan != null ? "Revise loan terms" : lending ? "Propose a loan" : "Request a loan";
        java.util.function.BooleanSupplier save = () -> {
            boolean money = kind.getSelectedIndex() == 0;
            String type = money ? "MONEY" : "ITEM";
            String total = money ? amount.getText() : "";
            String code = money ? currency.getText() : "";
            String subject = money ? "" : item.getText();
            try {
                long id;
                if (loan == null) {
                    id =
                            loans.create(
                                    account.id(),
                                    friend.getText(),
                                    role,
                                    type,
                                    total,
                                    code,
                                    subject,
                                    start.getText().strip(),
                                    due.getText().strip(),
                                    acknowledge.isSelected());
                } else {
                    id = loan.id();
                    loans.revise(
                            account.id(),
                            id,
                            loan.currentVersion(),
                            type,
                            total,
                            code,
                            subject,
                            start.getText().strip(),
                            due.getText().strip(),
                            acknowledge.isSelected());
                }
                changed.run();
                open(id);
                return true;
            } catch (Rules.InvalidInput ex) {
                error(ex.getMessage());
                if (loan != null
                        && loans.get(account.id(), loan.id()).currentVersion()
                                != loan.currentVersion()) return true;
                return false;
            }
        };
        if (inline) {
            showLoanForm(kind, friend, item, amount, currency, start, due, acknowledge, save);
        } else {
            while (confirm(this, fields, title, loan == null ? "Send request" : "Save revised terms")
                    && !save.getAsBoolean()) {
                // Keep the entered values available after a validation error.
            }
        }
    }

    private void showLoanForm(JComboBox<String> kind, JTextField friend, JTextField item,
            JTextField amount, JTextField currency, JTextField start, JTextField due,
            JCheckBox acknowledge, java.util.function.BooleanSupplier save) {
        var form = stack();
        line(form, sectionTitle("What are you lending?"));
        var belonging = secondary("Belonging");
        var money = secondary("Money");
        var types = actions(belonging, money);
        types.setOpaque(false);
        line(form, types);
        stackedField(form, "Borrower's account email", friend);
        var subject = new JPanel(new java.awt.CardLayout());
        subject.setOpaque(false);
        var itemField = stack();
        stackedField(itemField, "Item description", item);
        var moneyFields = stack();
        stackedField(moneyFields, "Amount", amount);
        stackedField(moneyFields, "Currency", currency);
        subject.add(itemField, "item");
        subject.add(moneyFields, "money");
        line(form, subject);
        var dates = new JPanel(new GridLayout(1, 2, 12, 0));
        dates.setOpaque(false);
        dates.add(filter("Planned handover (YYYY-MM-DD)", start));
        dates.add(filter("Expected return (YYYY-MM-DD)", due));
        line(form, dates);
        var reminders = card(new BorderLayout(0, 5), SAGE, 12);
        reminders.add(new JLabel("In-app reminders are on"), BorderLayout.NORTH);
        reminders.add(muted("Shown before the due date and while a return is overdue."));
        line(form, reminders);
        acknowledge.setOpaque(false);
        line(form, acknowledge);
        var record = primary("Send proposal");
        var cancel = secondary("Cancel");
        record.addActionListener(event -> attempt(this, () -> save.getAsBoolean()));
        cancel.addActionListener(event -> closeLoanForm());
        var controls = actions(record, cancel);
        controls.setOpaque(false);
        line(form, controls);
        var surface = card(new BorderLayout(), WHITE, 20);
        surface.add(form);
        var guidance = card(new BorderLayout(0, 12), SAGE, 18);
        guidance.setPreferredSize(new Dimension(275, 185));
        guidance.add(sectionTitle("After you send"), BorderLayout.NORTH);
        var instructions = new JTextArea("The borrower reviews the terms. When both of you agree, "
                + "you can record the handover that takes place outside Lendly.");
        instructions.setEditable(false);
        instructions.setOpaque(false);
        instructions.setLineWrap(true);
        instructions.setWrapStyleWord(true);
        instructions.setForeground(MUTED);
        guidance.add(instructions);
        var side = new JPanel(new BorderLayout());
        side.add(guidance, BorderLayout.NORTH);
        var columns = new JPanel(new BorderLayout(18, 0));
        columns.add(surface);
        columns.add(side, BorderLayout.EAST);
        var header = headingBlock("Propose a loan", "Send the terms and dates for the borrower to review.");
        var page = new JPanel(new BorderLayout(0, 16));
        page.add(header, BorderLayout.NORTH);
        page.add(columns, BorderLayout.CENTER);
        var top = new JPanel(new BorderLayout());
        top.add(page, BorderLayout.NORTH);
        Runnable updateType = () -> {
            boolean cash = kind.getSelectedIndex() == 0;
            ((JLabel) header.getComponent(0)).setText(cash ? "Propose a money loan" : "Propose an item loan");
            ((JLabel) header.getComponent(1)).setText(cash
                    ? "Agree on an amount and dates for a loan made outside Lendly."
                    : "Agree on which item you will lend and when it should be returned.");
            ((java.awt.CardLayout) subject.getLayout()).show(subject, cash ? "money" : "item");
            money.setBackground(cash ? GREEN : WHITE);
            money.setForeground(cash ? WHITE : INK);
            belonging.setBackground(cash ? WHITE : GREEN);
            belonging.setForeground(cash ? INK : WHITE);
        };
        kind.addActionListener(event -> updateType.run());
        belonging.addActionListener(event -> kind.setSelectedIndex(1));
        money.addActionListener(event -> kind.setSelectedIndex(0));
        kind.setSelectedIndex(1);
        updateType.run();
        loanForm = scroll(top);
        loanForm.getVerticalScrollBar().setUnitIncrement(16);
        remove(split);
        add(loanForm);
        revalidate();
        repaint();
    }

    private void closeLoanForm() {
        if (loanForm == null) return;
        remove(loanForm);
        loanForm = null;
        add(split);
        revalidate();
        repaint();
    }

    private void confirmTerms(Loan loan) {
        var form = stack();
        line(form, scroll(dialogText(loanFacts(loan), 7)));
        String next = loans.confirmed(otherPartyId(loan), loan.id(), loan.currentVersion())
                ? "After you confirm, " + loan.lenderName() + " can record the handover when it happens."
                : "After you confirm, " + counterpartName(loan) + " still needs to agree to this version.";
        stackedField(form, "What happens next", dialogText(next, 3));
        var acknowledgement = new JCheckBox("I checked the parties, amount or item, and both dates.");
        acknowledgement.setOpaque(false);
        line(form, acknowledgement);
        while (confirmCard(this, form, "Confirm terms", "Confirm terms")) {
            if (!acknowledgement.isSelected()) {
                error("Tick the checkbox to confirm that you checked the agreement.");
                continue;
            }
            loans.confirm(account.id(), loan.id(), loan.currentVersion(), true);
            changed.run();
            return;
        }
    }

    private void close(Loan loan, boolean decline) {
        String action = decline ? "Decline terms" : "Cancel proposal";
        String description = decline
                ? "This will close loan #" + loan.id() + ". " + counterpartName(loan)
                        + " will see that you declined the terms."
                : "This will close loan #" + loan.id() + " for you and " + counterpartName(loan) + ".";
        if (confirm(this, description, action, action)) {
            loans.closeProposal(account.id(), loan.id(), loan.currentVersion(), decline);
            changed.run();
        }
    }

    private void submit(Loan loan, boolean handover) {
        var date = new JTextField((handover ? loan.terms().startDate() : today()).toString(), 12);
        var amount = new JTextField(14);
        var note = new JTextField(22);
        var ack = new JCheckBox("This exchange took place outside the app.");
        var path = new JTextField(22);
        path.setEditable(false);
        var choose = new JButton("Choose image…");
        var remove = new JButton("Remove");
        choose.addActionListener(
                e -> {
                    var files = new JFileChooser();
                    files.setFileFilter(
                            new FileNameExtensionFilter(
                                    "Receipt image (PNG or JPEG)", "png", "jpg", "jpeg"));
                    if (files.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
                        path.setText(files.getSelectedFile().getPath());
                });
        remove.addActionListener(e -> path.setText(""));
        boolean repayment = !handover && loan.terms().kind().equals("MONEY");
        var fields = stack();
        fields.setMinimumSize(new Dimension(510, 0));
        String title =
                handover
                        ? "Record handover"
                        : repayment ? "Record repayment" : "Record item return";
        var subject = dialogText(subject(loan.terms()), 2);
        subject.setFont(subject.getFont().deriveFont(java.awt.Font.BOLD, 19f));
        line(fields, scroll(subject));
        line(fields, dialogText("Lender: " + loan.lenderName() + "\nBorrower: " + loan.borrowerName()
                + "\nDue date: " + displayDate(loan.terms().dueDate()), 4));
        if (repayment) {
            var balance = card(new BorderLayout(0, 6), SAGE, 12);
            balance.add(new JLabel("Confirmed outstanding balance: " + amountText(loan, loan.balance())),
                    BorderLayout.NORTH);
            balance.add(dialogText("Pending repayments count towards the amount you can record.", 2));
            line(fields, balance);
            stackedField(fields, "Amount (" + loan.terms().currency() + ")", amount);
        }
        stackedField(fields, repayment ? "Payment date (YYYY-MM-DD)"
                : handover ? "Handover date (YYYY-MM-DD)" : "Actual return date (YYYY-MM-DD)", date);
        stackedField(fields, !repayment && !handover ? "Return notes (optional)" : "Note (optional)", note);
        var receipt = actions(path, choose, remove);
        receipt.setOpaque(false);
        stackedField(fields, "Receipt (optional)", receipt);
        ack.setOpaque(false);
        line(fields, ack);
        line(fields, dialogText("After you save, " + counterpartName(loan)
                + " can confirm or dispute the record.", 2));
        String saveLabel = handover ? "Save handover record"
                : repayment ? "Save repayment record" : "Save return record";
        while (confirmCard(this, fields, title, saveLabel)) {
            try {
                Path attachment = path.getText().isBlank() ? null : Path.of(path.getText());
                loans.submit(
                        account.id(),
                        loan.id(),
                        loan.currentVersion(),
                        date.getText().strip(),
                        amount.getText(),
                        note.getText(),
                        attachment,
                        ack.isSelected());
                changed.run();
                selectActivityTab();
                return;
            } catch (Rules.InvalidInput ex) {
                error(ex.getMessage());
                Loan current = loans.get(account.id(), loan.id());
                if (current.currentVersion() != loan.currentVersion()
                        || !current.status().equals(loan.status())) return;
            }
        }
    }

    private void review(Loan loan, Activity activity) {
        var decision = new JComboBox<>(new String[] {"Confirm", "Dispute"});
        var reason = new JTextField(24);
        var ack = new JCheckBox("I checked the date, details and available evidence.");
        ack.setOpaque(false);
        var form = stack();
        stackedField(form, "Record", scroll(dialogText(loanFacts(loan) + "\n\n"
                + activityText(loan, activity), 13)));
        JComponent evidence = activity.receiptId() == null
                ? dialogText("No receipt attached.", 1)
                : action("Open receipt", () -> previewReceipt(activity));
        stackedField(form, "Evidence", evidence);
        stackedField(form, "Decision", decision);
        stackedField(form, "Reason for dispute", reason);
        line(form, ack);
        var effect = dialogText("", 3);
        stackedField(form, "What happens next", effect);
        Runnable updateDecision = () -> {
            boolean dispute = decision.getSelectedIndex() == 1;
            reason.setEnabled(dispute);
            ack.setEnabled(!dispute);
            effect.setText(dispute
                    ? activity.submitterName() + " can read your reason and submit a corrected record."
                    : reviewEffect(loan, activity));
            effect.setCaretPosition(0);
        };
        decision.addActionListener(e -> updateDecision.run());
        updateDecision.run();
        while (confirm(this, form, "Review loan activity", "Save decision")) {
            try {
                boolean dispute = decision.getSelectedIndex() == 1;
                loans.review(
                        account.id(),
                        loan.id(),
                        activity.id(),
                        loan.currentVersion(),
                        dispute ? "DISPUTE" : "CONFIRM",
                        dispute ? reason.getText() : "",
                        ack.isSelected());
                changed.run();
                return;
            } catch (Rules.InvalidInput ex) {
                error(ex.getMessage());
                Loan current = loans.get(account.id(), loan.id());
                if (current.currentVersion() != loan.currentVersion()
                        || !current.status().equals(loan.status())) return;
            }
        }
    }

    private static String reviewEffect(Loan loan, Activity activity) {
        if (activity.kind().equals("HANDOVER")) {
            String received = loan.terms().kind().equals("MONEY") ? "the money" : "the item";
            return "Confirming records that you received " + received + " and marks the loan active.";
        }
        if (activity.kind().equals("ITEM_RETURN")) {
            return "Confirming records that you received the item back and closes the loan.";
        }
        var remaining = loan.balance().subtract(activity.amount());
        if (remaining.signum() < 0) {
            return "This amount exceeds the current balance. Check the record before disputing it.";
        }
        return remaining.signum() == 0
                ? "Confirming this repayment settles the remaining balance and closes the loan."
                : "The confirmed balance after this repayment will be " + amountText(loan, remaining) + ".";
    }

    private void withdraw(Loan loan, Activity activity) {
        if (confirm(this, "Withdraw this pending record?", "Withdraw record", "Withdraw record")) {
            loans.withdraw(account.id(), loan.id(), activity.id(), loan.currentVersion());
            changed.run();
        }
    }

    private void activityDetails(Loan loan, Activity activity) {
        information(this, "Loan activity", scroll(dialogText(activityText(loan, activity), 8)));
    }

    private void previewReceipt(Activity activity) {
        if (activity.receiptId() == null) return;
        Evidence evidence = receipts.get(account.id(), activity.receiptId());
        var image = new ImageIcon(evidence.bytes());
        if (image.getIconWidth() <= 0 || image.getIconHeight() <= 0) {
            error("The saved image could not be previewed.");
            return;
        }
        double scale =
                Math.min(
                        1.0, Math.min(650.0 / image.getIconWidth(), 430.0 / image.getIconHeight()));
        if (scale < 1.0) {
            int width = Math.max(1, (int) (image.getIconWidth() * scale));
            int height = Math.max(1, (int) (image.getIconHeight() * scale));
            image =
                    new ImageIcon(
                            image.getImage().getScaledInstance(width, height, Image.SCALE_SMOOTH));
        }
        var preview = new JLabel(image);
        preview.getAccessibleContext()
                .setAccessibleName("Receipt image for " + status(activity.kind()));
        if (preview(this, "Private receipt", preview, "Save a copy")) {
            saveReceipt(activity.receiptId(), evidence);
        }
    }

    private void saveReceipt(long id, Evidence evidence) {
        String suffix = evidence.contentType().equals("image/png") ? "png" : "jpg";
        var chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File("receipt-" + id + "." + suffix));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Files.write(
                    chooser.getSelectedFile().toPath(),
                    evidence.bytes(),
                    StandardOpenOption.CREATE_NEW);
        } catch (java.nio.file.FileAlreadyExistsException ex) {
            error("A file with that name already exists. Choose a different name.");
        } catch (IOException ex) {
            error("Could not save the receipt to that location.");
        }
    }

    private JButton action(String label, Runnable operation) {
        var button = new JButton(label);
        button.addActionListener(e -> attempt(this, operation));
        return button;
    }

    private String stateText(Loan loan) {
        return loan.status().equals("ACTIVE") && loan.terms().dueDate().isBefore(today())
                ? "Active · overdue"
                : status(loan.status());
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    static String subject(Terms terms) {
        return terms.kind().equals("ITEM")
                ? terms.itemDescription()
                : terms.currency() + " " + terms.amount().toPlainString();
    }

    private static boolean isConfirmed(LoanView view, long user, int version) {
        return view.confirmations().stream()
                .anyMatch(c -> c.userId() == user && c.version() == version);
    }

    private static String confirmationText(LoanView view, long user, int version) {
        return view.confirmations().stream()
                .filter(c -> c.userId() == user && c.version() == version)
                .findFirst()
                .map(c -> "Confirmed " + c.confirmedAt().toLocalDate())
                .orElse("Not confirmed");
    }

    private static String party(String name, boolean you, boolean confirmed) {
        return name + (you ? " (you)" : "") + "  |  " + (confirmed ? "Confirmed" : "Not confirmed");
    }

    private long otherPartyId(Loan loan) {
        return loan.lenderId() == account.id() ? loan.borrowerId() : loan.lenderId();
    }

    private String counterpartName(Loan loan) {
        return loan.lenderId() == account.id() ? loan.borrowerName() : loan.lenderName();
    }

    private String loanFacts(Loan loan) {
        boolean lender = loan.lenderId() == account.id();
        return "Loan #" + loan.id() + " · Terms version " + loan.currentVersion()
                + "\nSubject: " + subject(loan.terms())
                + "\nLender: " + loan.lenderName() + (lender ? " (you)" : "")
                + "\nBorrower: " + loan.borrowerName() + (lender ? "" : " (you)")
                + "\nAgreed handover: " + displayDate(loan.terms().startDate())
                + "\nDue date: " + displayDate(loan.terms().dueDate());
    }

    private static String amountText(Loan loan, java.math.BigDecimal amount) {
        return loan.terms().currency() + " " + amount.toPlainString();
    }

    private static Activity activityAt(JTable table, List<Activity> records) {
        return records.get(table.convertRowIndexToModel(table.getSelectedRow()));
    }

    private static String activityText(Loan loan, Activity a) {
        return status(a.kind())
                + " · "
                + status(a.status())
                + "\nRecorded by: "
                + a.submitterName()
                + "\nActual date: "
                + displayDate(a.actualDate())
                + (a.amount() == null ? "" : "\nAmount: " + amountText(loan, a.amount()))
                + (a.note().isBlank() ? "" : "\nNote: " + a.note())
                + (a.reviewReason().isBlank() ? "" : "\nReview reason: " + a.reviewReason());
    }

    private static JLabel value(String text) {
        var label = new JLabel();
        plain(label);
        label.setText(text);
        label.setToolTipText(text.stripLeading().startsWith("<") ? null : text);
        return label;
    }

    private static JTextArea text(String value, int lines) {
        var area = new JTextArea(value, lines, 34);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setCaretPosition(0);
        return area;
    }

    private static JTextArea dialogText(String value, int lines) {
        var area = text(value, lines);
        area.setColumns(44);
        area.setOpaque(false);
        return area;
    }

    private void selectActivityTab() {
        if (shown != null) detailTabs.setSelectedIndex(1);
    }

    private void error(String message) {
        message(this, "Check the details", message, true);
    }

    private record LoanView(
            Loan loan,
            List<Confirmation> confirmations,
            List<Activity> activities,
            List<Terms> versions,
            String trust,
            String displayState) {}
}
