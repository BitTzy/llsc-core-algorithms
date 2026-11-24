package ph.edu.lendly;

import static ph.edu.lendly.DesktopUi.*;

import ph.edu.lendly.Models.Account;
import ph.edu.lendly.Models.Loan;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import javax.swing.*;

final class DesktopWindow extends JFrame {
    private final AccountService accounts;
    private final LoanService loans;
    private final ReceiptService receipts;
    private final CircleService circles;
    private final NotificationService notices;
    private final Clock clock;
    private final JTabbedPane accountTabs = new JTabbedPane();
    private final JPanel bar = new JPanel(new BorderLayout(12, 0));
    private final JButton closeTab = new JButton("Close account tab");
    private final Timer notificationRefresh = new Timer(5_000, event -> refreshNotifications());
    private int nextAccountTab = 1;

    DesktopWindow(
            AccountService accounts,
            LoanService loans,
            ReceiptService receipts,
            CircleService circles,
            NotificationService notices,
            Clock clock) {
        super("Lending Ledger and Saving Circle");
        this.accounts = accounts;
        this.loans = loans;
        this.receipts = receipts;
        this.circles = circles;
        this.notices = notices;
        this.clock = clock;

        var shell = new JPanel(new BorderLayout());
        bar.setBackground(WHITE);
        bar.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        var brand = heading("lendly");
        brand.setFont(brand.getFont().deriveFont(21f));
        var wordmark = new JPanel(new BorderLayout(8, 0));
        wordmark.setOpaque(false);
        var mark = card(new BorderLayout(), GREEN, 4);
        mark.setPreferredSize(new Dimension(30, 30));
        var initial = new JLabel("L", SwingConstants.CENTER);
        initial.setForeground(WHITE);
        initial.setFont(initial.getFont().deriveFont(Font.BOLD));
        mark.add(initial);
        wordmark.add(mark, BorderLayout.WEST);
        wordmark.add(brand, BorderLayout.CENTER);
        wordmark.setPreferredSize(new Dimension(174, 34));
        bar.add(wordmark, BorderLayout.WEST);
        bar.add(muted("Personal lending & savings"), BorderLayout.CENTER);
        var add = secondary("New account tab");
        add.addActionListener(event -> addAccountTab());
        closeTab.addActionListener(event -> closeAccountTab());
        var accountControls = actions(add, closeTab);
        accountControls.setOpaque(false);
        bar.add(accountControls, BorderLayout.EAST);
        shell.add(bar, BorderLayout.NORTH);
        accountTabs.getAccessibleContext().setAccessibleName("Signed-in account tabs");
        hideTabStrip(accountTabs);
        accountTabs.addChangeListener(event -> updateAccountChrome());
        shell.add(accountTabs, BorderLayout.CENTER);
        setContentPane(shell);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(920, 620));
        setSize(1220, 780);
        setLocationRelativeTo(null);
        addAccountTab();
        notificationRefresh.start();
    }

    @Override
    public void dispose() {
        notificationRefresh.stop();
        super.dispose();
    }

    private void addAccountTab() {
        var session = new AccountTab(nextAccountTab++);
        accountTabs.addTab(session.title(), session);
        accountTabs.setSelectedComponent(session);
        closeTab.setEnabled(accountTabs.getTabCount() > 1);
        updateAccountChrome();
    }

    private void closeAccountTab() {
        if (accountTabs.getTabCount() > 1) {
            accountTabs.removeTabAt(accountTabs.getSelectedIndex());
        }
        closeTab.setEnabled(accountTabs.getTabCount() > 1);
        updateAccountChrome();
    }

    private void updateAccountChrome() {
        if (!(accountTabs.getSelectedComponent() instanceof AccountTab session)) return;
        boolean show = session.account != null || accountTabs.getTabCount() > 1;
        bar.setVisible(show);
        accountTabs.putClientProperty("lendly.showTabStrip", accountTabs.getTabCount() > 1);
        accountTabs.revalidate();
        accountTabs.repaint();
    }

    void refreshAll() {
        for (int index = 0; index < accountTabs.getTabCount(); index++) {
            ((AccountTab) accountTabs.getComponentAt(index)).refresh();
        }
    }

    private void refreshNotifications() {
        for (int index = 0; index < accountTabs.getTabCount(); index++) {
            ((AccountTab) accountTabs.getComponentAt(index)).refreshNotices();
        }
    }

    private final class AccountTab extends JPanel {
        private final int number;
        private Account account;
        private LocalDate renderedDay;
        private JTabbedPane pages;
        private JPanel overview;
        private DesktopLoans lending;
        private DesktopLoans borrowing;
        private DesktopCircles saving;
        private DesktopNotices inbox;

        AccountTab(int number) {
            super(new BorderLayout(0, 12));
            this.number = number;
            showSignIn("");
        }

        String title() {
            return "Account " + number + (account == null ? " · Sign in" : " · " + account.name());
        }

        private void updateTab() {
            int index = accountTabs.indexOfComponent(this);
            if (index >= 0) accountTabs.setTitleAt(index, title());
            updateAccountChrome();
            revalidate();
            repaint();
        }

        private void showSignIn(String initialEmail) {
            account = null;
            pages = null;
            overview = null;
            lending = null;
            borrowing = null;
            saving = null;
            inbox = null;
            removeAll();
            var box = new JPanel(new BorderLayout(0, 24));
            box.setOpaque(false);
            box.add(headingBlock("Welcome back", "Sign in to your lending ledger"), BorderLayout.NORTH);
            var fields = new JPanel(new GridLayout(0, 1, 0, 8));
            fields.setOpaque(false);
            var email = new HintField(initialEmail, 25, "name@example.com");
            var password = new HintPasswordField(25, "Enter your password");
            field(fields, "Email address", email);
            field(fields, "Password", password);
            box.add(fields, BorderLayout.CENTER);
            var login = primary("Sign in");
            var register = secondary("Create account");
            login.addActionListener(
                    event ->
                            attempt(
                                    this,
                                    () -> {
                                        account =
                                                accounts.signIn(
                                                        email.getText(), password(password));
                                        showLedger();
                                    }));
            password.addActionListener(event -> login.doClick());
            register.addActionListener(event -> attempt(this, () -> register(email)));
            var buttons = new JPanel(new GridLayout(0, 1, 0, 10));
            buttons.setOpaque(false);
            buttons.add(login);
            buttons.add(register);
            buttons.add(muted("Money and items are exchanged outside Lendly."));
            box.add(buttons, BorderLayout.SOUTH);
            var credentials = new JPanel(new GridBagLayout());
            credentials.setBackground(WHITE);
            credentials.add(box);
            var introduction = new JPanel(new GridBagLayout());
            introduction.setBackground(INK);
            introduction.setPreferredSize(new Dimension(510, 0));
            var pitch = new JPanel(new BorderLayout(0, 20));
            pitch.setOpaque(false);
            var title = new JLabel("<html>Good things<br>come back.</html>");
            title.setFont(title.getFont().deriveFont(Font.BOLD, 45f));
            title.setForeground(WHITE);
            var lead = stack();
            var brand = new JLabel("lendly");
            brand.setForeground(WHITE);
            brand.setFont(brand.getFont().deriveFont(Font.BOLD, 20f));
            var signInBrand = new JPanel(new BorderLayout(9, 0));
            signInBrand.setOpaque(false);
            var logo = card(new BorderLayout(), GREEN, 6);
            logo.setBorder(BorderFactory.createEmptyBorder());
            logo.setPreferredSize(new Dimension(32, 32));
            var letter = new JLabel("L", SwingConstants.CENTER);
            letter.setForeground(WHITE);
            letter.setFont(letter.getFont().deriveFont(Font.BOLD, 16f));
            logo.add(letter);
            signInBrand.add(logo, BorderLayout.WEST);
            signInBrand.add(brand, BorderLayout.CENTER);
            line(lead, signInBrand);
            line(lead, (JComponent) Box.createVerticalStrut(24));
            var eyebrow = new JLabel("PERSONAL LENDING, MADE SIMPLE");
            eyebrow.setForeground(new java.awt.Color(0xBBD5C6));
            eyebrow.setFont(eyebrow.getFont().deriveFont(11f));
            line(lead, eyebrow);
            line(lead, title);
            pitch.add(lead, BorderLayout.NORTH);
            var tagline = new JLabel("<html>Keep track of what you lend.<br>Make shared goals easier.</html>");
            tagline.setFont(tagline.getFont().deriveFont(20f));
            tagline.setForeground(WHITE);
            pitch.add(tagline, BorderLayout.CENTER);
            var reminder = card(new BorderLayout(0, 14), WHITE, 18);
            reminder.add(muted("A little nudge, right on time"), BorderLayout.NORTH);
            var callout = card(new BorderLayout(14, 0), SAGE, 12);
            var dateTile = card(new BorderLayout(), GREEN, 10);
            var clockFace = new JComponent() {
                @Override
                protected void paintComponent(java.awt.Graphics graphics) {
                    var g = (java.awt.Graphics2D) graphics.create();
                    g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                            java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(WHITE);
                    g.setStroke(new java.awt.BasicStroke(2f, java.awt.BasicStroke.CAP_ROUND,
                            java.awt.BasicStroke.JOIN_ROUND));
                    int x = getWidth() / 2;
                    int y = getHeight() / 2;
                    g.drawOval(x - 11, y - 11, 22, 22);
                    g.drawLine(x, y - 6, x, y);
                    g.drawLine(x, y, x + 5, y + 3);
                    g.dispose();
                }
            };
            clockFace.setPreferredSize(new Dimension(26, 26));
            dateTile.add(clockFace);
            callout.add(dateTile, BorderLayout.WEST);
            var reminderText = stack();
            var subject = new JLabel("The book comes back today");
            subject.setFont(subject.getFont().deriveFont(Font.BOLD, 17f));
            line(reminderText, subject);
            line(reminderText, muted("Jamie Cruz · Return scheduled for today"));
            callout.add(reminderText);
            reminder.add(callout);
            reminder.add(muted("One shared record. Fewer forgotten returns."), BorderLayout.SOUTH);
            pitch.add(reminder, BorderLayout.SOUTH);
            var finePrint = new JLabel("<html>Money, books, chargers, umbrellas.<br>Keep the friendship. Remember the details.</html>");
            finePrint.setForeground(new java.awt.Color(0xBBD5C6));
            var signInStory = new JPanel(new BorderLayout(0, 18));
            signInStory.setOpaque(false);
            signInStory.add(pitch, BorderLayout.CENTER);
            signInStory.add(finePrint, BorderLayout.SOUTH);
            introduction.add(signInStory);
            add(introduction, BorderLayout.WEST);
            add(credentials, BorderLayout.CENTER);
            updateTab();
        }

        private void register(JTextField emailField) {
            var name = new HintField("", 24, "e.g. Ana Reyes");
            var email = new HintField("", 24, "name@example.com");
            var password = new HintPasswordField(24, "12+ characters, letters and numbers");
            var repeat = new HintPasswordField(24, "Type the same password again");
            var fields = stack();
            stackedField(fields, "Full name", name);
            stackedField(fields, "Email address", email);
            stackedField(fields, "Password", password);
            stackedField(fields, "Confirm password", repeat);
            var created = new Account[1];
            try {
                // Registration runs inside the window, so a rejected entry shows its message
                // there and the person can correct it without retyping everything.
                if (confirmValidated(
                        this,
                        fields,
                        "Create account",
                        "Create account",
                        () ->
                                created[0] =
                                        accounts.register(
                                                name.getText(),
                                                email.getText(),
                                                new String(password.getPassword()),
                                                new String(repeat.getPassword())))) {
                    emailField.setText(created[0].email());
                    message(this, "Account created", "Sign in with your new account to open your ledger.", false);
                }
            } finally {
                password.setText("");
                repeat.setText("");
            }
        }

        private void showLedger() {
            removeAll();
            var refresh = new JButton("Refresh");
            var signOut = new JButton("Sign out");
            refresh.addActionListener(event -> attempt(this, DesktopWindow.this::refreshAll));
            signOut.addActionListener(event -> showSignIn(""));
            pages = new JTabbedPane();
            hideTabStrip(pages);
            overview = new JPanel(new BorderLayout(0, 20));
            overview.setBorder(BorderFactory.createEmptyBorder(28, 32, 24, 32));
            lending =
                    new DesktopLoans(
                            account,
                            loans,
                            receipts,
                            clock,
                            "lending",
                            DesktopWindow.this::refreshAll);
            borrowing =
                    new DesktopLoans(
                            account,
                            loans,
                            receipts,
                            clock,
                            "borrowing",
                            DesktopWindow.this::refreshAll);
            saving =
                    new DesktopCircles(
                            account,
                            circles,
                            clock,
                            DesktopWindow.this::refreshAll,
                            member -> trust(loans.list(member, "borrowing", "", ""), member));
            inbox =
                    new DesktopNotices(
                            account, notices, this::openLoan, DesktopWindow.this::refreshAll);
            pages.addTab("Overview", scroll(overview));
            pages.addTab("Lending ledger", lending);
            pages.addTab("Borrowing", borrowing);
            pages.addTab("Savings pools", saving);
            pages.addTab("Reminders & trust", inbox);
            var sidebar = new JPanel(new BorderLayout(0, 12));
            sidebar.setBackground(WHITE);
            sidebar.setPreferredSize(new Dimension(194, 0));
            sidebar.setBorder(BorderFactory.createEmptyBorder(14, 12, 14, 12));
            var navigation = new JPanel(new GridLayout(0, 1, 0, 7));
            navigation.setOpaque(false);
            var links = new JButton[pages.getTabCount()];
            for (int i = 0; i < links.length; i++) {
                int index = i;
                var link = secondary(pages.getTitleAt(i));
                link.setBorderPainted(false);
                link.setBorder(BorderFactory.createEmptyBorder(11, 14, 11, 14));
                link.setHorizontalAlignment(SwingConstants.LEADING);
                link.addActionListener(event -> pages.setSelectedIndex(index));
                links[i] = link;
                navigation.add(link);
            }
            Runnable highlight = () -> {
                for (int i = 0; i < links.length; i++) {
                    links[i].setText(pages.getTitleAt(i));
                    links[i].setBackground(i == pages.getSelectedIndex() ? SAGE : WHITE);
                }
            };
            pages.addChangeListener(event -> highlight.run());
            pages.addPropertyChangeListener("indexForTitle", event -> highlight.run());
            highlight.run();
            sidebar.add(navigation, BorderLayout.NORTH);
            var identity = new JPanel(new GridLayout(0, 1, 0, 6));
            identity.setOpaque(false);
            var name = new JLabel(account.name());
            plain(name);
            identity.add(name);
            identity.add(muted("Personal workspace"));
            identity.add(refresh);
            identity.add(signOut);
            for (var button : List.of(refresh, signOut)) {
                button.setBorderPainted(false);
                button.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));
                button.setHorizontalAlignment(SwingConstants.LEADING);
            }
            var sidebarFooter = new JPanel(new BorderLayout(0, 14));
            sidebarFooter.setOpaque(false);
            var note = card(new BorderLayout(0, 5), SAGE, 10);
            note.add(new JLabel("A ledger, not a wallet"), BorderLayout.NORTH);
            var noteText = new JLabel("<html>Record exchanges made<br>outside the app.</html>");
            noteText.setForeground(MUTED);
            note.add(noteText, BorderLayout.CENTER);
            sidebarFooter.add(note, BorderLayout.NORTH);
            sidebarFooter.add(identity, BorderLayout.SOUTH);
            sidebar.add(sidebarFooter, BorderLayout.SOUTH);
            add(sidebar, BorderLayout.WEST);
            add(pages, BorderLayout.CENTER);
            refresh();
            updateTab();
        }

        private void openLoan(long id) {
            Loan loan = loans.get(account.id(), id);
            DesktopLoans panel = loan.lenderId() == account.id() ? lending : borrowing;
            pages.setSelectedComponent(panel);
            panel.open(id);
        }

        private void refresh() {
            if (account == null || pages == null) return;
            var records = loans.list(account.id(), "all", "", "");
            renderedDay = LocalDate.now(clock);
            refreshOverview(records, renderedDay);
            inbox.showTrust(trust(records, account.id()));
            inbox.showReminders(records, renderedDay);
            lending.refresh();
            borrowing.refresh();
            saving.refresh();
            refreshInbox();
        }

        private void refreshNotices() {
            if (account == null || pages == null) return;
            if (!LocalDate.now(clock).equals(renderedDay)) {
                refresh();
                return;
            }
            refreshInbox();
        }

        private void refreshInbox() {
            int unread = notices.unread(account.id());
            pages.setTitleAt(4, unread == 0 ? "Reminders & trust" : "Reminders & trust (" + unread + ")");
            inbox.refresh();
        }

        private void refreshOverview(List<Loan> records, LocalDate today) {
            long id = account.id();
            var lent = records.stream().filter(loan -> loan.lenderId() == id).toList();
            var borrowed = records.stream().filter(loan -> loan.borrowerId() == id).toList();
            long completed =
                    borrowed.stream().filter(loan -> "COMPLETED".equals(loan.status())).count();
            long overdue =
                    lent.stream()
                            .filter(
                                    loan ->
                                            "ACTIVE".equals(loan.status())
                                                    && loan.terms().dueDate().isBefore(today))
                            .count();
            overview.removeAll();
            var header = new JPanel(new BorderLayout(12, 0));
            var welcome = headingBlock("A little overview, " + account.name(),
                    "See what is out, what is due, and what your group has recorded.");
            var record = primary("+ Propose a loan");
            record.addActionListener(event -> {
                pages.setSelectedComponent(lending);
                lending.createLoan();
            });
            header.add(welcome, BorderLayout.CENTER);
            header.add(actions(record), BorderLayout.EAST);
            overview.add(header, BorderLayout.NORTH);
            var metrics = new JPanel(new GridLayout(1, 4, 12, 0));
            metrics.add(metric("Active loans", Long.toString(lent.stream()
                    .filter(loan -> "ACTIVE".equals(loan.status())).count()), "Records as lender"));
            metrics.add(metric("Outstanding money", activeBalance(lent.stream()
                    .filter(loan -> "MONEY".equals(loan.terms().kind())).toList()), "Recorded amount yet to be repaid"));
            metrics.add(metric("Overdue returns", Long.toString(overdue), "Past their expected return date"));
            var poolsForAccount = circles.list(id);
            var balances = new LinkedHashMap<String, BigDecimal>();
            for (var pool : poolsForAccount) balances.merge(pool.currency(), pool.balance(), BigDecimal::add);
            var combined = balances.entrySet().stream()
                    .map(entry -> entry.getKey() + " " + entry.getValue().toPlainString()).toList();
            metrics.add(metric("Savings pool balance", combined.isEmpty() ? "No pools"
                    : combined.size() == 1 ? combined.getFirst() : "Multiple currencies",
                    combined.size() > 1 ? String.join(" · ", combined) : "Recorded pool balances"));
            var content = new JPanel(new BorderLayout(0, 16));
            content.add(metrics, BorderLayout.NORTH);
            var recent = card(new BorderLayout(0, 12), WHITE, 16);
            var recentHeader = new JPanel(new BorderLayout());
            recentHeader.setOpaque(false);
            recentHeader.add(sectionTitle("Recent activity"), BorderLayout.WEST);
            var view = new JButton("View ledger →");
            view.addActionListener(event -> pages.setSelectedComponent(lending));
            recentHeader.add(view, BorderLayout.EAST);
            recent.add(recentHeader, BorderLayout.NORTH);
            var recordsTable =
                    table(new String[] {"Item or amount", "With", "Your role", "Expected return", "Status"});
            var latest =
                    records.stream()
                            .sorted(java.util.Comparator.comparingLong(Loan::id).reversed())
                            .limit(5)
                            .toList();
            rows(recordsTable, latest.stream().map(loan -> new Object[] {
                DesktopLoans.subject(loan.terms()),
                loan.lenderId() == id ? loan.borrowerName() : loan.lenderName(),
                loan.lenderId() == id ? "You lent" : "You borrowed",
                displayDate(loan.terms().dueDate()),
                status(loan.status())
            }).toList());
            recent.add(latest.isEmpty()
                    ? muted("Loans you lend or borrow will appear here.") : scroll(recordsTable));
            var upcoming = card(new BorderLayout(0, 8), SAGE, 14);
            upcoming.add(sectionTitle("Coming up next"), BorderLayout.NORTH);
            var dueList = new JPanel(new GridLayout(0, 1, 0, 5));
            dueList.setOpaque(false);
            var soon = records.stream().filter(loan -> "ACTIVE".equals(loan.status())
                            && !loan.terms().dueDate().isBefore(today)
                            && !loan.terms().dueDate().isAfter(today.plusDays(3)))
                    .sorted(java.util.Comparator.comparing(loan -> loan.terms().dueDate()))
                    .limit(3).toList();
            if (soon.isEmpty()) dueList.add(muted("No upcoming returns."));
            for (var loan : soon) {
                var due = new JButton(DesktopLoans.subject(loan.terms()) + " · " + displayDate(loan.terms().dueDate()));
                due.addActionListener(event -> attempt(this, () -> openLoan(loan.id())));
                dueList.add(due);
            }
            upcoming.add(dueList, BorderLayout.CENTER);
            var reminders = new JButton("View reminders");
            reminders.addActionListener(event -> pages.setSelectedComponent(inbox));
            upcoming.add(reminders, BorderLayout.SOUTH);
            var activity = new JPanel(new BorderLayout(16, 0));
            upcoming.setPreferredSize(new Dimension(290, 0));
            activity.add(recent, BorderLayout.CENTER);
            activity.add(upcoming, BorderLayout.EAST);
            content.add(activity, BorderLayout.CENTER);
            var pools = card(new BorderLayout(0, 8), WHITE, 14);
            pools.add(sectionTitle("Savings pools"), BorderLayout.NORTH);
            int poolCount = poolsForAccount.size();
            pools.add(muted(poolCount + (poolCount == 1 ? " shared pool" : " shared pools")), BorderLayout.CENTER);
            var viewPools = new JButton("View savings pools");
            viewPools.addActionListener(event -> pages.setSelectedComponent(saving));
            pools.add(viewPools, BorderLayout.SOUTH);
            var reliability = card(new BorderLayout(0, 8), new java.awt.Color(0xEFECFA), 14);
            reliability.add(sectionTitle("Return reliability, with context"), BorderLayout.NORTH);
            var score = new JPanel(new BorderLayout(12, 0));
            score.setOpaque(false);
            score.add(new JLabel(trust(borrowed, id)), BorderLayout.WEST);
            score.add(muted(completed + " completed as borrower"), BorderLayout.CENTER);
            reliability.add(score);
            var history = secondary("See the history behind the score →");
            history.addActionListener(event -> pages.setSelectedComponent(inbox));
            reliability.add(history, BorderLayout.SOUTH);
            var bottom = new JPanel(new GridLayout(1, 2, 16, 0));
            bottom.add(pools);
            bottom.add(reliability);
            content.add(bottom, BorderLayout.SOUTH);
            overview.add(content, BorderLayout.CENTER);
            overview.add(muted("Recorded activity only. Money and items are exchanged outside Lendly."), BorderLayout.SOUTH);
            overview.revalidate();
            overview.repaint();
        }
    }

    private static String password(JPasswordField input) {
        char[] characters = input.getPassword();
        try {
            return new String(characters);
        } finally {
            Arrays.fill(characters, '\0');
            input.setText("");
        }
    }

    private static JPanel metric(String title, String value, String note) {
        var panel = card(new BorderLayout(0, 5), WHITE, 12);
        panel.add(new JLabel(title), BorderLayout.NORTH);
        var amount = heading(value);
        amount.setFont(amount.getFont().deriveFont(20f));
        panel.add(amount, BorderLayout.CENTER);
        var explanation = new JTextArea(note, 2, 10);
        explanation.setLineWrap(true);
        explanation.setWrapStyleWord(true);
        explanation.setEditable(false);
        explanation.setOpaque(false);
        explanation.setForeground(MUTED);
        panel.add(explanation, BorderLayout.SOUTH);
        return panel;
    }

    static String activeBalance(List<Loan> records) {
        var totals = new LinkedHashMap<String, BigDecimal>();
        int items = 0;
        for (var loan : records) {
            if (!"ACTIVE".equals(loan.status())) continue;
            if ("MONEY".equals(loan.terms().kind())) {
                totals.merge(loan.terms().currency(), loan.balance(), BigDecimal::add);
            } else {
                items++;
            }
        }
        var labels = new java.util.ArrayList<String>();
        totals.forEach((currency, amount) -> labels.add(currency + " " + amount.toPlainString()));
        if (items > 0) labels.add(items + (items == 1 ? " item" : " items"));
        return labels.isEmpty() ? "No active loans" : String.join(" · ", labels);
    }
}
