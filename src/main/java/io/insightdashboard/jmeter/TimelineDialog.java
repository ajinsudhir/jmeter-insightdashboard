package io.insightdashboard.jmeter;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

/**
 * Renders one JMeter thread's most recent iteration as a sequential, Gantt-style
 * timeline: each named transaction (a Transaction Controller's own parent sample,
 * or a top-level request not under one) as a horizontal bar from its own start to
 * its own end, stacked in the order it actually ran. Makes "where did the time in
 * one pass through the script go" visible at a glance, which a flat, aggregate
 * per-request table can't show on its own.
 *
 * Deliberately per-thread rather than an aggregate across all threads: different
 * threads can be at different points in their own loop, or interleaved with each
 * other, so overlaying them would produce a chart that doesn't correspond to any
 * single, coherent pass through the script. One thread's own sequence is a
 * meaningful, readable timeline; many threads' overlapping ones aren't.
 */
final class TimelineDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    private final Map<String, List<CustomDashboardVisualizer.TimelineEntry>> iterationsByThread;
    private final JComboBox<String> threadCombo = new JComboBox<>();
    private final GanttPanel ganttPanel = new GanttPanel();
    private final JLabel summaryLabel = new JLabel(" ");

    TimelineDialog(Frame owner, Map<String, List<CustomDashboardVisualizer.TimelineEntry>> iterationsByThread) {
        super(owner, "Transaction Timeline", true);
        this.iterationsByThread = iterationsByThread;
        buildUi();
        setSize(960, 600);
        setLocationRelativeTo(owner);
    }

    private void buildUi() {
        setLayout(new BorderLayout(8, 8));
        JPanel content = (JPanel) getContentPane();
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        TreeSet<String> threadNames = new TreeSet<>(iterationsByThread.keySet());
        for (String name : threadNames) {
            threadCombo.addItem(name);
        }

        JPanel top = new JPanel();
        top.setLayout(new javax.swing.BoxLayout(top, javax.swing.BoxLayout.Y_AXIS));
        JPanel pickerRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        pickerRow.add(new JLabel("Thread:"));
        threadCombo.setToolTipText("Each thread runs its own, independent iteration of the script \u2014 "
                + "pick one to see its own sequence of transactions.");
        pickerRow.add(threadCombo);
        JButton refreshButton = new JButton("Refresh");
        refreshButton.setToolTipText("This dialog shows a snapshot from when it opened (or last "
                + "refreshed) \u2014 click to pull the thread's latest iteration again, e.g. after it's "
                + "moved further through a long-running test.");
        pickerRow.add(refreshButton);
        top.add(pickerRow);
        JPanel summaryRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        summaryRow.add(summaryLabel);
        top.add(summaryRow);
        add(top, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(ganttPanel);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        add(scroll, BorderLayout.CENTER);

        JButton closeButton = new JButton("Close");
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        south.add(closeButton);
        add(south, BorderLayout.SOUTH);

        threadCombo.addActionListener(e -> showSelectedThread());
        refreshButton.addActionListener(e -> showSelectedThread());
        closeButton.addActionListener(e -> dispose());

        if (threadCombo.getItemCount() > 0) {
            showSelectedThread();
        }
    }

    private void showSelectedThread() {
        String thread = (String) threadCombo.getSelectedItem();
        if (thread == null) {
            return;
        }
        List<CustomDashboardVisualizer.TimelineEntry> entries = iterationsByThread.get(thread);
        if (entries == null || entries.isEmpty()) {
            summaryLabel.setText("No transactions captured yet for this thread.");
            ganttPanel.setEntries(new ArrayList<>());
            return;
        }
        List<CustomDashboardVisualizer.TimelineEntry> snapshot = new ArrayList<>(entries);
        long firstStart = snapshot.get(0).startMillis;
        long lastEnd = snapshot.get(snapshot.size() - 1).endMillis;
        double totalSec = Math.max(0, lastEnd - firstStart) / 1000.0;
        summaryLabel.setText(String.format("%d transaction(s), %.1fs total, snapshot taken just now "
                + "\u2014 click Refresh to update", snapshot.size(), totalSec));
        ganttPanel.setEntries(snapshot);
    }

    /** The actual Gantt rendering - self-contained, scrollable (its preferred height grows
     *  with the number of transactions), with a tooltip per bar showing exact timing since
     *  a long transaction name can otherwise crowd out the numbers in the fixed-width
     *  label column. */
    private static final class GanttPanel extends JPanel {
        private static final long serialVersionUID = 1L;
        private static final int ROW_HEIGHT = 32;
        private static final int LABEL_WIDTH = 240;
        private static final int TOP_PAD = 30;
        private static final int RIGHT_PAD = 20;
        private static final Color OK_COLOR = new Color(0x42, 0x85, 0xf4);
        private static final Color FAIL_COLOR = new Color(0xc6, 0x28, 0x28);
        private static final Color GRID_COLOR = new Color(0x80, 0x80, 0x80, 60);

        private List<CustomDashboardVisualizer.TimelineEntry> entries = new ArrayList<>();
        private long firstStart;
        private long totalSpan = 1;

        GanttPanel() {
            setBackground(null);
            setToolTipText(""); // opts this component into ToolTipManager's dispatch at all;
                                 // it already re-queries getToolTipText(MouseEvent) below as
                                 // the mouse moves, with no extra listener needed here.
        }

        void setEntries(List<CustomDashboardVisualizer.TimelineEntry> entries) {
            this.entries = entries;
            if (!entries.isEmpty()) {
                firstStart = entries.get(0).startMillis;
                long lastEnd = entries.get(entries.size() - 1).endMillis;
                totalSpan = Math.max(1, lastEnd - firstStart);
            }
            setPreferredSize(new Dimension(800, TOP_PAD + entries.size() * ROW_HEIGHT + 20));
            revalidate();
            repaint();
        }

        private int chartWidth() {
            return Math.max(100, getWidth() - LABEL_WIDTH - RIGHT_PAD);
        }

        private int xForMillis(long millis) {
            double frac = (millis - firstStart) / (double) totalSpan;
            return LABEL_WIDTH + (int) Math.round(frac * chartWidth());
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            int row = (e.getY() - TOP_PAD) / ROW_HEIGHT;
            if (row < 0 || row >= entries.size()) {
                return null;
            }
            CustomDashboardVisualizer.TimelineEntry entry = entries.get(row);
            long durationMs = entry.endMillis - entry.startMillis;
            return "<html>" + entry.label + "<br/>Duration: " + durationMs + " ms<br/>Status: "
                    + (entry.successful ? "Success" : "Failed") + "</html>";
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            Color textColor = getForeground() != null ? getForeground() : Color.BLACK;
            FontMetrics fm = g2.getFontMetrics();

            // A light vertical gridline every ~10% of the total span, with its elapsed-time
            // label, so a bar's absolute position is actually readable, not just relative.
            int gridLines = 10;
            for (int i = 0; i <= gridLines; i++) {
                int x = LABEL_WIDTH + (int) Math.round(i / (double) gridLines * chartWidth());
                g2.setColor(GRID_COLOR);
                g2.drawLine(x, TOP_PAD - 4, x, TOP_PAD + entries.size() * ROW_HEIGHT);
                double sec = (i / (double) gridLines) * (totalSpan / 1000.0);
                String label = String.format("%.1fs", sec);
                g2.setColor(textColor);
                g2.drawString(label, x - fm.stringWidth(label) / 2, TOP_PAD - 10);
            }

            for (int i = 0; i < entries.size(); i++) {
                CustomDashboardVisualizer.TimelineEntry entry = entries.get(i);
                int y = TOP_PAD + i * ROW_HEIGHT;
                int barHeight = ROW_HEIGHT - 10;

                String label = entry.label;
                int maxLabelWidth = LABEL_WIDTH - 12;
                if (fm.stringWidth(label) > maxLabelWidth) {
                    while (label.length() > 4 && fm.stringWidth(label + "\u2026") > maxLabelWidth) {
                        label = label.substring(0, label.length() - 1);
                    }
                    label = label + "\u2026";
                }
                g2.setColor(textColor);
                g2.drawString(label, 4, y + barHeight - 6);

                int x1 = xForMillis(entry.startMillis);
                int x2 = xForMillis(entry.endMillis);
                int barWidth = Math.max(2, x2 - x1);
                g2.setColor(entry.successful ? OK_COLOR : FAIL_COLOR);
                g2.fillRoundRect(x1, y + 3, barWidth, barHeight, 6, 6);

                long durationMs = entry.endMillis - entry.startMillis;
                String durationLabel = durationMs >= 1000
                        ? String.format("%.1fs", durationMs / 1000.0) : durationMs + "ms";
                int textWidth = fm.stringWidth(durationLabel);
                if (textWidth + 8 < barWidth) {
                    g2.setColor(Color.WHITE);
                    g2.drawString(durationLabel, x1 + 4, y + barHeight - 6);
                } else {
                    g2.setColor(textColor);
                    g2.drawString(durationLabel, x2 + 4, y + barHeight - 6);
                }
            }

            g2.dispose();
        }
    }
}
