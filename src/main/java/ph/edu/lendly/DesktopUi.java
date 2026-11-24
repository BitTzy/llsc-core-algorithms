package ph.edu.lendly;

import org.springframework.dao.DataAccessException;

import ph.edu.lendly.Models.Loan;

import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.GridBagLayout;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.List;
import java.util.Locale;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.border.AbstractBorder;
import javax.swing.plaf.basic.BasicTabbedPaneUI;

public final class DesktopUi {
    static final Color INK = new Color(0x15392D);
    static final Color GREEN = new Color(0x265F4A);
    static final Color BG = new Color(0xF3F6F0);
    static final Color SAGE = new Color(0xE3F0E5);
    static final Color MUTED = new Color(0x52675A);
    static final Color LINE = new Color(0xDCE5DB);
    static final Color WHITE = Color.WHITE;

    private DesktopUi() {}

    private static final java.time.format.DateTimeFormatter DISPLAY_DATE =
            java.time.format.DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);

    static String displayDate(java.time.LocalDate date) {
        return DISPLAY_DATE.format(date);
    }

    static final Color HINT = new Color(0x8A9A90);

    private static void paintHint(javax.swing.text.JTextComponent field, Graphics graphics, String hint) {
        if (hint == null || !field.getText().isEmpty()) return;
        var g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(HINT);
        g.setFont(field.getFont());
        var insets = field.getInsets();
        var metrics = g.getFontMetrics();
        int baseline = insets.top + (field.getHeight() - insets.top - insets.bottom - metrics.getHeight()) / 2
                + metrics.getAscent();
        g.drawString(hint, insets.left, baseline);
        g.dispose();
    }

    /** A text field that shows grey example text until the person starts typing. */
    static final class HintField extends JTextField {
        private final String hint;

        HintField(String text, int columns, String hint) {
            super(text, columns);
            this.hint = hint;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            paintHint(this, graphics, hint);
        }
    }

    /** A password field with the same grey example text. */
    static final class HintPasswordField extends javax.swing.JPasswordField {
        private final String hint;

        HintPasswordField(int columns, String hint) {
            super(columns);
            this.hint = hint;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            paintHint(this, graphics, hint);
        }
    }

    static void install() {
        var font = new javax.swing.plaf.FontUIResource("Segoe UI", Font.PLAIN, 13);
        for (var key : java.util.Collections.list(UIManager.getDefaults().keys())) {
            if (UIManager.get(key) instanceof javax.swing.plaf.FontUIResource) {
                UIManager.put(key, font);
            }
        }
        UIManager.put("Panel.background", BG);
        UIManager.put("Label.foreground", INK);
        UIManager.put("TabbedPane.selected", SAGE);
        UIManager.put("ButtonUI", ButtonStyle.class.getName());
        UIManager.put("ComboBoxUI", ComboStyle.class.getName());
        UIManager.put("TabbedPaneUI", TabStyle.class.getName());
        UIManager.put("ScrollBarUI", ScrollStyle.class.getName());
        UIManager.put("SplitPaneUI", SplitStyle.class.getName());
        UIManager.put("Button.background", WHITE);
        UIManager.put("Button.foreground", INK);
        UIManager.put("OptionPane.background", WHITE);
        UIManager.put("Table.foreground", INK);
        UIManager.put("Table.selectionBackground", SAGE);
        UIManager.put("Table.selectionForeground", INK);
        UIManager.put("Table.background", WHITE);
        UIManager.put("TextField.background", WHITE);
        UIManager.put("PasswordField.background", WHITE);
        UIManager.put("ComboBox.background", WHITE);
        UIManager.put("TextField.border", outline(8, 8, 12));
        UIManager.put("PasswordField.border", outline(8, 8, 12));
        UIManager.put("TextField.foreground", INK);
        UIManager.put("PasswordField.foreground", INK);
        UIManager.put("TextArea.foreground", INK);
        UIManager.put("Button.border", BorderFactory.createEmptyBorder(9, 15, 9, 15));
        UIManager.put("ComboBox.foreground", INK);
        UIManager.put("ScrollBar.width", 9);
        UIManager.put("CheckBox.icon", new Icon() {
            @Override public int getIconWidth() { return 16; }
            @Override public int getIconHeight() { return 16; }

            @Override
            public void paintIcon(Component component, Graphics graphics, int x, int y) {
                var box = (AbstractButton) component;
                var g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(box.isSelected() ? GREEN : WHITE);
                g.fillRoundRect(x, y, 15, 15, 5, 5);
                g.setColor(box.isFocusOwner() ? GREEN : LINE);
                g.drawRoundRect(x, y, 15, 15, 5, 5);
                if (box.isSelected()) {
                    g.setColor(WHITE);
                    g.setStroke(new java.awt.BasicStroke(1.6f));
                    g.drawLine(x + 4, y + 8, x + 7, y + 11);
                    g.drawLine(x + 7, y + 11, x + 12, y + 5);
                }
                g.dispose();
            }
        });
        UIManager.put("Button.margin", new java.awt.Insets(0, 0, 0, 0));
    }

    static javax.swing.border.Border outline(int radius, int vertical, int horizontal) {
        return BorderFactory.createCompoundBorder(new AbstractBorder() {
            @Override
            public void paintBorder(Component component, Graphics graphics, int x, int y,
                    int width, int height) {
                var g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(component.isFocusOwner() ? GREEN : LINE);
                g.drawRoundRect(x, y, width - 1, height - 1, radius, radius);
                g.dispose();
            }
        }, BorderFactory.createEmptyBorder(vertical, horizontal, vertical, horizontal));
    }

    static JPanel card(java.awt.LayoutManager layout, Color color, int padding) {
        var panel = new JPanel(layout) {
            @Override
            protected void paintComponent(Graphics graphics) {
                var g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(getBackground());
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
                g.dispose();
                super.paintComponent(graphics);
            }
        };
        panel.setOpaque(false);
        panel.setBackground(color);
        panel.setBorder(outline(16, padding, padding));
        return panel;
    }

    static JPanel headingBlock(String title, String description) {
        var panel = new JPanel(new BorderLayout(0, 5));
        panel.setOpaque(false);
        panel.add(heading(title), BorderLayout.NORTH);
        panel.add(muted(description), BorderLayout.SOUTH);
        return panel;
    }

    static JLabel muted(String text) {
        var label = new JLabel(text);
        plain(label);
        label.setForeground(MUTED);
        return label;
    }

    static void hideTabStrip(JTabbedPane tabs) {
        tabs.putClientProperty("lendly.showTabStrip", false);
    }

    static JPanel form() {
        return new JPanel(new GridLayout(0, 2, 10, 8));
    }

    static JPanel stack() {
        var panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        return panel;
    }

    static void line(JPanel panel, JComponent component) {
        var constraints = new java.awt.GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = java.awt.GridBagConstraints.RELATIVE;
        constraints.weightx = 1;
        constraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        constraints.insets = new java.awt.Insets(0, 0, 9, 0);
        panel.add(component, constraints);
    }

    static void stackedField(JPanel panel, String label, JComponent input) {
        var row = new JPanel(new BorderLayout(0, 5));
        row.setOpaque(false);
        var caption = new JLabel(label);
        caption.setLabelFor(input);
        input.getAccessibleContext().setAccessibleName(label);
        row.add(caption, BorderLayout.NORTH);
        row.add(input, BorderLayout.CENTER);
        line(panel, row);
    }

    static void field(JPanel panel, String label, JComponent input) {
        var caption = new JLabel(label);
        caption.setLabelFor(input);
        input.getAccessibleContext().setAccessibleName(label);
        panel.add(caption);
        panel.add(input);
    }

    static boolean confirm(Component parent, Object content, String title, String action) {
        return confirmCard(parent, content instanceof JComponent component
                ? component : promptText(String.valueOf(content)), title, action);
    }

    static boolean confirmCard(Component parent, JComponent content, String title, String action) {
        return prompt(parent, content, title, action, "Cancel", false, null);
    }

    /**
     * Like {@link #confirm}, but runs {@code onSave} when the button is pressed. If it rejects
     * the input, the message appears inside the open window so the person can correct it.
     */
    static boolean confirmValidated(
            Component parent, JComponent content, String title, String action, Runnable onSave) {
        return prompt(parent, content, title, action, "Cancel", false, onSave);
    }

    static void message(Component parent, String title, String body, boolean warning) {
        prompt(parent, promptText(body), title, "Done", null, warning, null);
    }

    static void information(Component parent, String title, JComponent body) {
        prompt(parent, body, title, "Done", null, false, null);
    }

    static boolean preview(Component parent, String title, JComponent content, String action) {
        return prompt(parent, content, title, action, "Close", false, null);
    }

    private static JTextArea promptText(String text) {
        var body = new JTextArea(text, 0, 40);
        body.setLineWrap(true);
        body.setWrapStyleWord(true);
        body.setEditable(false);
        body.setOpaque(false);
        body.setForeground(MUTED);
        body.setSize(body.getPreferredSize().width, Short.MAX_VALUE);
        return body;
    }

    private static boolean prompt(Component parent, JComponent content, String title, String action,
            String dismiss, boolean warning, Runnable onSave) {
        var save = primary(action);
        var surface = new JPanel(new BorderLayout(0, 18));
        surface.setBackground(WHITE);
        surface.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        var heading = heading(title);
        heading.setFont(heading.getFont().deriveFont(24f));
        heading.setForeground(warning ? new Color(0x813742) : INK);
        surface.add(heading, BorderLayout.NORTH);
        if (content instanceof JPanel panel && BG.equals(panel.getBackground())) panel.setOpaque(false);
        surface.add(content, BorderLayout.CENTER);
        var buttons = actions();
        ((FlowLayout) buttons.getLayout()).setAlignment(FlowLayout.TRAILING);
        var problem = new JLabel();
        problem.setForeground(new Color(0x813742));
        problem.setVisible(false);
        var footer = new JPanel(new BorderLayout(0, 10));
        footer.setOpaque(false);
        footer.add(problem, BorderLayout.NORTH);
        footer.add(buttons, BorderLayout.CENTER);
        surface.add(footer, BorderLayout.SOUTH);
        var option = new JOptionPane(surface, JOptionPane.PLAIN_MESSAGE,
                JOptionPane.OK_CANCEL_OPTION, null, new Object[0]);
        if (dismiss != null) {
            var cancel = secondary(dismiss);
            cancel.addActionListener(event -> option.setValue(JOptionPane.CANCEL_OPTION));
            buttons.add(cancel);
        }
        buttons.add(save);
        option.setBorder(BorderFactory.createEmptyBorder());
        var dialog = option.createDialog(parent, title);
        dialog.getRootPane().setDefaultButton(save);
        save.addActionListener(
                event -> {
                    if (onSave != null) {
                        String error = null;
                        try {
                            onSave.run();
                        } catch (Rules.InvalidInput | Rules.NotFound ex) {
                            error = ex.getMessage();
                        } catch (DataAccessException ex) {
                            error = "The ledger could not complete this action. Please try again.";
                        }
                        if (error != null) {
                            problem.setText(
                                    "<html><body style='width:330px'>"
                                            + error.replace("&", "&amp;").replace("<", "&lt;")
                                            + "</body></html>");
                            problem.setVisible(true);
                            dialog.pack();
                            return;
                        }
                    }
                    option.setValue(JOptionPane.OK_OPTION);
                });
        dialog.setResizable(false);
        dialog.setVisible(true);
        dialog.dispose();
        return Integer.valueOf(JOptionPane.OK_OPTION).equals(option.getValue());
    }

    static void attempt(Component parent, Runnable action) {
        try {
            action.run();
        } catch (Rules.InvalidInput | Rules.NotFound ex) {
            message(parent, "Check the details", ex.getMessage(), true);
        } catch (DataAccessException ex) {
            message(parent, "Couldn’t save your changes",
                    "The ledger could not complete this action. Please try again.", true);
        }
    }

    static JScrollPane scroll(JComponent content) {
        var scroll = new JScrollPane(content);
        scroll.getViewport().setBackground(content instanceof JTable ? WHITE : BG);
        scroll.setBorder(content instanceof JTable ? outline(16, 8, 12)
                : BorderFactory.createEmptyBorder());
        if (content instanceof JTable) scroll.setBackground(WHITE);
        if (content instanceof JTable table) scroll.setColumnHeaderView(table.getTableHeader());
        return scroll;
    }

    static JTable table(String[] headings) {
        var table =
                new JTable(
                        new DefaultTableModel(headings, 0) {
                            @Override
                            public boolean isCellEditable(int row, int column) {
                                return false;
                            }
                        });
        var renderer = new DefaultTableCellRenderer();
        plain(renderer);
        table.setDefaultRenderer(Object.class, renderer);
        renderer.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        table.setRowHeight(48);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        var header = table.getTableHeader();
        header.setBackground(WHITE);
        header.setForeground(MUTED);
        header.setPreferredSize(new Dimension(0, 36));
        var headerRenderer = new DefaultTableCellRenderer();
        headerRenderer.setBackground(WHITE);
        headerRenderer.setForeground(MUTED);
        headerRenderer.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        header.setDefaultRenderer(headerRenderer);
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        return table;
    }

    static void rows(JTable table, List<Object[]> rows) {
        var model = (DefaultTableModel) table.getModel();
        model.setRowCount(0);
        rows.forEach(model::addRow);
    }

    static String status(String code) {
        var text = code.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    static void plain(JLabel label) {
        label.putClientProperty("html.disable", Boolean.TRUE);
    }

    static JLabel heading(String title) {
        var label = new JLabel();
        plain(label);
        label.setText(title);
        label.setFont(new Font("Segoe UI Semibold", Font.PLAIN, 29));
        return label;
    }

    static JLabel sectionTitle(String title) {
        var label = heading(title);
        label.setFont(label.getFont().deriveFont(17f));
        return label;
    }

    static JButton primary(String label) {
        return styledButton(label, GREEN, WHITE);
    }

    static JButton secondary(String label) {
        return styledButton(label, WHITE, INK);
    }

    private static JButton styledButton(String label, Color background, Color foreground) {
        var button = new JButton(label);
        button.setContentAreaFilled(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setBackground(background);
        button.setForeground(foreground);
        return button;
    }

    static JPanel actions(Component... controls) {
        var panel = new JPanel(new FlowLayout(FlowLayout.LEADING, 8, 4));
        panel.setOpaque(false);
        for (var control : controls) panel.add(control);
        return panel;
    }

    public static final class ButtonStyle extends javax.swing.plaf.basic.BasicButtonUI {
        public static javax.swing.plaf.ComponentUI createUI(JComponent component) {
            return new ButtonStyle();
        }

        @Override
        public void update(Graphics graphics, JComponent component) {
            var button = (AbstractButton) component;
            var model = button.getModel();
            Color fill = button.isEnabled() ? button.getBackground() : BG;
            if (model.isPressed()) fill = fill.darker();
            else if (model.isRollover() && WHITE.equals(fill)) fill = BG;
            var g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(fill);
            g.fillRoundRect(0, 0, component.getWidth(), component.getHeight(), 12, 12);
            if (component.isFocusOwner() || WHITE.equals(fill) && button.isBorderPainted()) {
                g.setColor(component.isFocusOwner() ? GREEN : LINE);
                g.drawRoundRect(0, 0, component.getWidth() - 1, component.getHeight() - 1, 12, 12);
            }
            g.dispose();
            paint(graphics, component);
        }

        @Override
        protected void paintFocus(Graphics graphics, AbstractButton button,
                java.awt.Rectangle view, java.awt.Rectangle text, java.awt.Rectangle icon) {}
    }

    public static final class ComboStyle extends javax.swing.plaf.basic.BasicComboBoxUI {
        public static javax.swing.plaf.ComponentUI createUI(JComponent component) {
            return new ComboStyle();
        }

        @Override
        protected void installDefaults() {
            super.installDefaults();
            comboBox.setBorder(outline(10, 5, 9));
        }

        @Override
        protected JButton createArrowButton() {
            var arrow = new JButton() {
                @Override
                protected void paintComponent(Graphics graphics) {
                    var g = (Graphics2D) graphics.create();
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(MUTED);
                    int x = getWidth() / 2;
                    int y = getHeight() / 2;
                    g.drawLine(x - 4, y - 2, x, y + 2);
                    g.drawLine(x, y + 2, x + 4, y - 2);
                    g.dispose();
                }
            };
            arrow.setOpaque(false);
            arrow.setBorderPainted(false);
            arrow.setFocusable(false);
            arrow.setPreferredSize(new Dimension(18, 18));
            return arrow;
        }
    }

    public static final class TabStyle extends BasicTabbedPaneUI {
        public static javax.swing.plaf.ComponentUI createUI(JComponent component) {
            return new TabStyle();
        }

        private boolean stripVisible() {
            return !Boolean.FALSE.equals(tabPane.getClientProperty("lendly.showTabStrip"));
        }

        @Override
        protected int calculateTabAreaHeight(int placement, int runs, int height) {
            return stripVisible() ? super.calculateTabAreaHeight(placement, runs, height) + 6 : 0;
        }

        @Override
        protected void paintTabArea(Graphics graphics, int placement, int selected) {
            if (stripVisible()) super.paintTabArea(graphics, placement, selected);
        }

        @Override
        protected void paintTabBackground(Graphics graphics, int placement, int index,
                int x, int y, int width, int height, boolean selected) {
            if (!selected) return;
            var g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(SAGE);
            g.fillRoundRect(x + 2, y + 1, width - 4, height - 2, 12, 12);
            g.dispose();
        }

        @Override
        protected void paintTabBorder(Graphics graphics, int placement, int index,
                int x, int y, int width, int height, boolean selected) {}

        @Override
        protected void paintContentBorder(Graphics graphics, int placement, int selected) {}

        @Override
        protected java.awt.Insets getContentBorderInsets(int placement) {
            return new java.awt.Insets(0, 0, 0, 0);
        }

        @Override
        protected java.awt.Insets getTabInsets(int placement, int index) {
            return new java.awt.Insets(5, 12, 5, 12);
        }
    }

    public static final class ScrollStyle extends javax.swing.plaf.basic.BasicScrollBarUI {
        public static javax.swing.plaf.ComponentUI createUI(JComponent component) {
            return new ScrollStyle();
        }

        @Override protected JButton createDecreaseButton(int orientation) { return spacer(); }
        @Override protected JButton createIncreaseButton(int orientation) { return spacer(); }

        private JButton spacer() {
            var button = new JButton();
            var empty = new Dimension(0, 0);
            button.setPreferredSize(empty);
            button.setMinimumSize(empty);
            button.setMaximumSize(empty);
            return button;
        }

        @Override
        protected void paintTrack(Graphics graphics, JComponent component, java.awt.Rectangle bounds) {}

        @Override
        protected void paintThumb(Graphics graphics, JComponent component, java.awt.Rectangle bounds) {
            if (bounds.isEmpty() || !component.isEnabled()) return;
            var g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0xCBD9CF));
            g.fillRoundRect(bounds.x + 2, bounds.y + 2, bounds.width - 4, bounds.height - 4, 8, 8);
            g.dispose();
        }
    }

    public static final class SplitStyle extends javax.swing.plaf.basic.BasicSplitPaneUI {
        public static javax.swing.plaf.ComponentUI createUI(JComponent component) {
            return new SplitStyle();
        }

        @Override
        public javax.swing.plaf.basic.BasicSplitPaneDivider createDefaultDivider() {
            return new javax.swing.plaf.basic.BasicSplitPaneDivider(this) {
                @Override
                public void paint(Graphics graphics) {
                    graphics.setColor(LINE);
                    graphics.drawLine(getWidth() / 2 - 15, getHeight() / 2,
                            getWidth() / 2 + 15, getHeight() / 2);
                }
            };
        }
    }

    static String trust(List<Loan> records, long borrowerId) {
        long completed = 0;
        long onTime = 0;
        for (var record : records) {
            if (record.borrowerId() != borrowerId || !"COMPLETED".equals(record.status())) continue;
            completed++;
            if (!record.completedOn().isAfter(record.terms().dueDate())) onTime++;
        }
        return completed == 0 ? "Not yet rated" : Math.round(100.0 * onTime / completed) + "%";
    }
}
