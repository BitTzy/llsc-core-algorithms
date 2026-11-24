package ph.edu.lendly;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.imageio.ImageIO;
import javax.swing.*;

@EnabledIfSystemProperty(named = "lendly.gui.test", matches = "true")
class DesktopTabsTest {
    private static final String PASSWORD = "ClassroomDemo21!";

    @Test
    void independentTabsShareConfirmedLoanChanges(@TempDir Path scratch) throws Exception {
        Path calendarFile = scratch.resolve("demo-reminders.ics");
        try (var context =
                new SpringApplicationBuilder(DesktopApplication.class)
                        .headless(false)
                        .run(
                                "--spring.datasource.url=jdbc:h2:mem:desktop-tabs;DB_CLOSE_DELAY=-1")) {
            var accounts = context.getBean(AccountService.class);
            var loans = context.getBean(LoanService.class);
            var clock = context.getBean(Clock.class);
            var due = LocalDate.now(clock).plusDays(1);
            SwingUtilities.invokeAndWait(
                    () -> {
                        DesktopUi.install();
                        var window =
                                new DesktopWindow(
                                        accounts,
                                        loans,
                                        context.getBean(ReceiptService.class),
                                        context.getBean(CircleService.class),
                                        context.getBean(NotificationService.class),
                                        clock);
                        try {
                            var tabs = find(window.getContentPane(), JTabbedPane.class).getFirst();
                            var first = (Container) tabs.getComponentAt(0);
                            register(first, "Ana Santos", "ana@example.test");
                            signIn(first, "ana@example.test");

                            button(window.getContentPane(), "New account tab").doClick();
                            assertEquals(2, tabs.getTabCount());
                            var second = (Container) tabs.getComponentAt(1);
                            register(second, "Ben Morgan", "ben@example.test");
                            signIn(second, "ben@example.test");
                            assertTrue(tabs.getTitleAt(0).contains("Ana Santos"));
                            assertTrue(tabs.getTitleAt(1).contains("Ben Morgan"));

                            var firstTabs = find(first, JTabbedPane.class).getFirst();
                            var secondTabs = find(second, JTabbedPane.class).getFirst();
                            secondTabs.setSelectedIndex(2);
                            dialog(
                                    "Request a loan",
                                    d -> {
                                        input(d, "Lender's account email", "ana@example.test");
                                        input(d, "Amount", "250.00");
                                        input(d, "Due date (YYYY-MM-DD)", due.toString());
                                        find(d, JCheckBox.class).getFirst().setSelected(true);
                                    },
                                    () -> button(second, "Request a loan").doClick());
                            long loanId =
                                    loans.list(
                                                    accounts.byEmail("ana@example.test").id(),
                                                    "lending",
                                                    "",
                                                    "")
                                            .getFirst()
                                            .id();

                            tabs.setSelectedIndex(0);
                            firstTabs.setSelectedIndex(1);
                            var lending = (DesktopLoans) firstTabs.getComponentAt(1);
                            var borrowing = (DesktopLoans) secondTabs.getComponentAt(2);
                            var lentRows = find(lending, JTable.class).getFirst();
                            assertEquals(1, lentRows.getRowCount());
                            assertEquals("Pending terms", lentRows.getValueAt(0, 4));
                            lending.open(loanId);
                            borrowing.open(loanId);
                            assertTrue(
                                    find(borrowing, JButton.class).stream()
                                            .noneMatch(b -> "Confirm terms".equals(b.getText())));
                            var lenderInbox = (DesktopNotices) firstTabs.getComponentAt(4);
                            firstTabs.setSelectedComponent(lenderInbox);
                            find(lenderInbox, JTabbedPane.class).getFirst().setSelectedIndex(1);
                            var notifications = find(lenderInbox, JTable.class).getFirst();
                            assertEquals("Unread", notifications.getValueAt(0, 0));
                            notifications.setRowSelectionInterval(0, 0);
                            button(lenderInbox, "Open loan").doClick();
                            assertSame(lending, firstTabs.getSelectedComponent());
                            assertEquals("Read", notifications.getValueAt(0, 0));
                            dialog(
                                    "Confirm terms",
                                    d -> {
                                        var acknowledgement = find(d, JCheckBox.class).getFirst();
                                        assertFalse(acknowledgement.isSelected());
                                        String agreement = find(d, JTextArea.class).stream()
                                                .map(JTextArea::getText).reduce("", String::concat);
                                        assertTrue(agreement.contains("Ana Santos"));
                                        assertTrue(agreement.contains("Ben Morgan"));
                                        assertTrue(agreement.contains("PHP 250.00"));
                                        capture(d, "confirmation.png");
                                        acknowledgement.setSelected(true);
                                    },
                                    () -> button(lending, "Confirm terms").doClick());
                            assertEquals("Awaiting handover", lentRows.getValueAt(0, 4));
                            assertEquals(
                                    "Awaiting handover",
                                    find(borrowing, JTable.class).getFirst().getValueAt(0, 4));
                            assertTrue(button(lending, "Record handover").isEnabled());
                            capture(window, "desktop-agreement.png");

                            dialog(
                                    "Record handover",
                                    d -> find(d, JCheckBox.class).getFirst().setSelected(true),
                                    () -> button(lending, "Record handover").doClick());
                            assertTrue(find(lending, JButton.class).stream()
                                    .noneMatch(b -> b.isEnabled() && b.getText().startsWith("Revise")));
                            tabs.setSelectedIndex(1);
                            showActivity(borrowing);
                            review(borrowing);
                            assertEquals("Active", lentRows.getValueAt(0, 4));
                            assertEquals(
                                    "Active",
                                    find(borrowing, JTable.class).getFirst().getValueAt(0, 4));
                            var borrowerInbox = (DesktopNotices) secondTabs.getComponentAt(4);
                            secondTabs.setSelectedComponent(borrowerInbox);
                            assertTrue(find(borrowerInbox, JLabel.class).stream()
                                    .anyMatch(label -> "Due soon".equals(label.getText())));
                            capture(window, "reminders.png");
                            dialog(
                                    "Export loan reminders",
                                    d -> find(d, JFileChooser.class).getFirst()
                                            .setSelectedFile(calendarFile.toFile()),
                                    () -> button(borrowerInbox, "Export calendar (.ics)").doClick());
                            secondTabs.setSelectedComponent(borrowing);
                            find(borrowing, JTabbedPane.class).getFirst().setSelectedIndex(0);
                            dialog(
                                    "Record repayment",
                                    d -> {
                                        input(d, "Amount (PHP)", "250.00");
                                        find(d, JCheckBox.class).getFirst().setSelected(true);
                                    },
                                    () -> button(borrowing, "Record repayment").doClick());
                            tabs.setSelectedIndex(0);
                            showActivity(lending);
                            review(lending);
                            assertEquals("Completed", lentRows.getValueAt(0, 4));
                            assertEquals(
                                    "Completed",
                                    find(borrowing, JTable.class).getFirst().getValueAt(0, 4));
                            assertFalse(button(borrowerInbox, "Export calendar (.ics)").isEnabled());

                            tabs.setSelectedIndex(1);
                            secondTabs.setSelectedIndex(0);
                            assertTrue(
                                    find(second, JLabel.class).stream()
                                            .anyMatch(label -> "100%".equals(label.getText())));
                            capture(window, "desktop-dashboard.png");
                            button(second, "Sign out").doClick();
                            assertTrue(tabs.getTitleAt(1).contains("Sign in"));
                            assertTrue(tabs.getTitleAt(0).contains("Ana Santos"));
                            assertEquals(1, lentRows.getRowCount());
                        } finally {
                            window.dispose();
                        }
                    });
        }
        String calendar = Files.readString(calendarFile);
        assertTrue(calendar.contains("SUMMARY:Lendly loan due"));
        assertFalse(calendar.contains("Ana Santos"));
        assertFalse(calendar.contains("250.00"));
    }

    private static void showActivity(Container loan) {
        find(loan, JTabbedPane.class).getFirst().setSelectedIndex(1);
    }

    private static void review(Container loan) {
        dialog(
                "Review loan activity",
                d -> find(d, JCheckBox.class).getFirst().setSelected(true),
                () -> button(loan, "Review record").doClick());
    }

    private static void register(Container pane, String name, String email) {
        dialog(
                "Create account",
                d -> {
                    input(d, "Full name", name);
                    input(d, "Email address", email);
                    input(d, "Password", PASSWORD);
                    input(d, "Confirm password", PASSWORD);
                },
                () -> button(pane, "Create account").doClick());
    }

    private static void signIn(Container pane, String email) {
        input(pane, "Email address", email);
        input(pane, "Password", PASSWORD);
        button(pane, "Sign in").doClick();
    }

    private static void input(Container pane, String name, String value) {
        find(pane, JTextField.class).stream()
                .filter(field -> name.equals(field.getAccessibleContext().getAccessibleName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Field missing: " + name))
                .setText(value);
    }

    private static void dialog(String title, Consumer<JDialog> fill, Runnable action) {
        var handled = new ArrayList<String>();
        var failures = new ArrayList<Throwable>();
        long deadline = System.nanoTime() + 6_000_000_000L;
        var timer =
                new Timer(
                        25,
                        event -> {
                            for (Window window : Window.getWindows()) {
                                if (!(window instanceof JDialog d) || !d.isShowing()) continue;
                                var choosers = find(d, JFileChooser.class);
                                if (!choosers.isEmpty()) {
                                    var chooser = choosers.getFirst();
                                    try {
                                        assertEquals(title, d.getTitle());
                                        fill.accept(d);
                                        handled.add(title);
                                        chooser.approveSelection();
                                    } catch (Throwable error) {
                                        failures.add(error);
                                        chooser.cancelSelection();
                                    }
                                    continue;
                                }
                                var option = find(d, JOptionPane.class).getFirst();
                                try {
                                    if (d.getTitle().equals(title) && handled.isEmpty()) {
                                        fill.accept(d);
                                        handled.add(title);
                                        option.setValue(JOptionPane.OK_OPTION);
                                    } else if (title.equals("Create account")
                                            && "Account created".equals(d.getTitle())) {
                                        option.setValue(JOptionPane.OK_OPTION);
                                    } else if (title.equals("Export loan reminders")
                                            && "Calendar file saved".equals(d.getTitle())) {
                                        option.setValue(JOptionPane.OK_OPTION);
                                    } else if (System.nanoTime() > deadline) {
                                        failures.add(
                                                new AssertionError(
                                                        "Unexpected dialog: "
                                                                + d.getTitle()
                                                                + " ("
                                                                + option.getMessage()
                                                                + ")"));
                                        option.setValue(JOptionPane.CANCEL_OPTION);
                                    }
                                } catch (Throwable error) {
                                    failures.add(error);
                                    option.setValue(JOptionPane.CANCEL_OPTION);
                                }
                            }
                        });
        timer.start();
        try {
            action.run();
        } finally {
            timer.stop();
        }
        assertTrue(failures.isEmpty(), failures.toString());
        assertEquals(List.of(title), handled);
    }

    private static JButton button(Container pane, String label) {
        return find(pane, JButton.class).stream()
                .filter(button -> label.equals(button.getText()))
                .findFirst()
                .orElseThrow();
    }

    private static <T> List<T> find(Component root, Class<T> type) {
        List<T> result = new ArrayList<>();
        if (type.isInstance(root)) result.add(type.cast(root));
        if (root instanceof Container container)
            for (Component child : container.getComponents()) result.addAll(find(child, type));
        return result;
    }

    private static void capture(RootPaneContainer window, String file) {
        String directory = System.getProperty("lendly.gui.evidence", "");
        if (directory.isBlank()) return;
        try {
            var pane = window.getContentPane();
            if (window instanceof JFrame frame) pane.setSize(frame.getSize());
            layout(pane);
            var image =
                    new BufferedImage(
                            pane.getWidth(), pane.getHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            try {
                pane.printAll(graphics);
            } finally {
                graphics.dispose();
            }
            Files.createDirectories(Path.of(directory));
            ImageIO.write(image, "png", Path.of(directory, file).toFile());
        } catch (java.io.IOException error) {
            throw new AssertionError("Could not save desktop evidence", error);
        }
    }

    private static void layout(Container pane) {
        pane.doLayout();
        for (Component child : pane.getComponents())
            if (child instanceof Container container) layout(container);
    }
}
