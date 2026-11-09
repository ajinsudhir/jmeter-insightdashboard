package io.insightdashboard.jmeter;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TreeSet;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * Compares two {@link CustomDashboardVisualizer.RunSnapshot}s (or one saved
 * snapshot against the dashboard's own current live/just-ended data) side by
 * side, per transaction, flagging regressions and improvements and rolling
 * them up into a single pass/fail verdict - the single most commonly
 * requested feature in load-testing tools, and something this dashboard had
 * no way to do at all before this: every run was shown in isolation, with
 * nothing to compare it against.
 *
 * Deliberately a standalone dialog taking already-captured RunSnapshot data,
 * not a tab baked into the main dashboard - comparison is an occasional,
 * on-demand action, not something that needs to live in the always-visible
 * UI the way the live charts and table do.
 */
final class RunComparisonDialog extends JDialog {

    private static final long serialVersionUID = 1L;
    private static final double DEFAULT_REGRESSION_THRESHOLD_PCT = 20.0;

    private final List<CustomDashboardVisualizer.RunSnapshot> options;
    private final JComboBox<String> baselineCombo = new JComboBox<>();
    private final JComboBox<String> compareCombo = new JComboBox<>();
    private final JTextField regressionThresholdField = new JTextField(String.valueOf((int) DEFAULT_REGRESSION_THRESHOLD_PCT), 4);
    private final JLabel verdictLabel = new JLabel(" ");
    private final ComparisonTableModel tableModel = new ComparisonTableModel();
    private final JTable table = new JTable(tableModel);
    private List<ComparisonRow> currentRows = new ArrayList<>();
    private String currentVerdictText = "";

    /**
     * @param liveSnapshot the dashboard's own current live (or just-ended) data, already
     *                captured as a RunSnapshot by the caller - offered as a selectable
     *                "Current (live)" option alongside the saved ones, so comparing against
     *                it doesn't require saving it as a named snapshot first.
     */
    RunComparisonDialog(Frame owner, List<CustomDashboardVisualizer.RunSnapshot> savedRuns,
                         CustomDashboardVisualizer.RunSnapshot liveSnapshot) {
        super(owner, "Compare Runs", true);
        this.options = new ArrayList<>();
        if (liveSnapshot != null) {
            options.add(liveSnapshot);
        }
        options.addAll(savedRuns);
        buildUi();
        setSize(900, 600);
        setLocationRelativeTo(owner);
    }

    private String labelFor(CustomDashboardVisualizer.RunSnapshot s) {
        if (s.savedAtMillis == 0 && "Current (live)".equals(s.name)) {
            return s.name;
        }
        return s.name + " \u2014 " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(s.savedAtMillis));
    }

    private void buildUi() {
        setLayout(new BorderLayout(8, 8));
        JPanel content = (JPanel) getContentPane();
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        for (CustomDashboardVisualizer.RunSnapshot s : options) {
            baselineCombo.addItem(labelFor(s));
            compareCombo.addItem(labelFor(s));
        }
        // Sensible defaults: baseline is the most recently saved run (index 1, since
        // savedRuns is sorted newest-first and index 0 is "Current (live)" when present),
        // compare defaults to the live data - "how does right now compare to what I saved
        // most recently," not to some possibly months-old first-ever saved run.
        if (options.size() > 1) {
            baselineCombo.setSelectedIndex(1);
        }
        compareCombo.setSelectedIndex(0);

        JPanel top = new JPanel();
        top.setLayout(new javax.swing.BoxLayout(top, javax.swing.BoxLayout.Y_AXIS));
        JPanel pickerRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        pickerRow.add(new JLabel("Baseline:"));
        pickerRow.add(baselineCombo);
        pickerRow.add(new JLabel("Compare against:"));
        pickerRow.add(compareCombo);
        pickerRow.add(new JLabel("Regression threshold %:"));
        regressionThresholdField.setToolTipText("<html>A transaction's P95 or average response time increasing by "
                + "more than this percentage<br>versus the baseline counts as a regression for the pass/fail "
                + "verdict below.</html>");
        pickerRow.add(regressionThresholdField);
        JButton recomputeButton = new JButton("Compare");
        pickerRow.add(recomputeButton);
        top.add(pickerRow);

        verdictLabel.setFont(verdictLabel.getFont().deriveFont(Font.BOLD, 14f));
        JPanel verdictRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        verdictRow.add(verdictLabel);
        top.add(verdictRow);

        add(top, BorderLayout.NORTH);

        table.setRowHeight(24);
        table.getTableHeader().setFont(table.getTableHeader().getFont().deriveFont(Font.BOLD));
        DefaultTableCellRenderer numericRenderer = new DefaultTableCellRenderer();
        numericRenderer.setHorizontalAlignment(SwingConstants.RIGHT);
        table.setDefaultRenderer(Object.class, numericRenderer);
        table.setDefaultRenderer(String.class, new StatusAwareRenderer(tableModel));
        JScrollPane tableScroll = new JScrollPane(table);
        add(tableScroll, BorderLayout.CENTER);

        JButton deleteButton = new JButton("Delete Baseline Run\u2026");
        deleteButton.setToolTipText("Deletes whichever saved run is currently selected as the "
                + "Baseline above (not available for \u201cCurrent (live)\u201d, since that isn't a saved file).");
        JButton exportButton = new JButton("Export Comparison\u2026");
        JButton closeButton = new JButton("Close");
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        south.add(deleteButton);
        south.add(exportButton);
        south.add(closeButton);
        add(south, BorderLayout.SOUTH);

        recomputeButton.addActionListener(e -> recompute());
        closeButton.addActionListener(e -> dispose());
        exportButton.addActionListener(e -> exportComparison());
        deleteButton.addActionListener(e -> deleteSelectedBaseline());

        recompute();
    }

    private double parseThreshold() {
        try {
            double v = Double.parseDouble(regressionThresholdField.getText().trim());
            return v > 0 ? v : DEFAULT_REGRESSION_THRESHOLD_PCT;
        } catch (NumberFormatException badInput) {
            return DEFAULT_REGRESSION_THRESHOLD_PCT;
        }
    }

    private void recompute() {
        int baselineIdx = baselineCombo.getSelectedIndex();
        int compareIdx = compareCombo.getSelectedIndex();
        if (baselineIdx < 0 || compareIdx < 0) {
            return;
        }
        CustomDashboardVisualizer.RunSnapshot baseline = options.get(baselineIdx);
        CustomDashboardVisualizer.RunSnapshot compare = options.get(compareIdx);
        double thresholdPct = parseThreshold();

        TreeSet<String> allLabels = new TreeSet<>();
        allLabels.addAll(baseline.perLabel.keySet());
        allLabels.addAll(compare.perLabel.keySet());

        List<ComparisonRow> rows = new ArrayList<>();
        int regressions = 0;
        int newErrors = 0;
        int improved = 0;
        int newTransactions = 0;
        int removedTransactions = 0;

        for (String label : allLabels) {
            CustomDashboardVisualizer.RunSnapshotRow b = baseline.perLabel.get(label);
            CustomDashboardVisualizer.RunSnapshotRow c = compare.perLabel.get(label);
            ComparisonRow row = new ComparisonRow();
            row.label = label;
            if (b == null) {
                row.status = "NEW";
                row.cAvg = c.avg;
                row.cP95 = c.p95;
                row.cErrorPct = c.errorPct;
                newTransactions++;
            } else if (c == null) {
                row.status = "REMOVED";
                row.bAvg = b.avg;
                row.bP95 = b.p95;
                row.bErrorPct = b.errorPct;
                removedTransactions++;
            } else {
                row.bAvg = b.avg;
                row.cAvg = c.avg;
                row.bP95 = b.p95;
                row.cP95 = c.p95;
                row.bErrorPct = b.errorPct;
                row.cErrorPct = c.errorPct;
                row.p95DeltaPct = b.p95 > 0 ? 100.0 * (c.p95 - b.p95) / b.p95 : (c.p95 > 0 ? 100.0 : 0.0);
                boolean errorsWorsened = c.errorPct > b.errorPct + 0.01; // small epsilon, not float-exact-equal
                boolean p95Regressed = row.p95DeltaPct > thresholdPct;
                boolean p95Improved = row.p95DeltaPct < -thresholdPct;
                if (p95Regressed || errorsWorsened) {
                    row.status = "REGRESSION";
                    regressions++;
                    if (errorsWorsened) {
                        newErrors++;
                    }
                } else if (p95Improved) {
                    row.status = "IMPROVED";
                    improved++;
                } else {
                    row.status = "OK";
                }
            }
            rows.add(row);
        }

        currentRows = rows;
        tableModel.setRows(rows);

        boolean pass = regressions == 0;
        StringBuilder verdict = new StringBuilder();
        verdict.append(pass ? "PASS" : "FAIL").append(" \u2014 ");
        verdict.append(regressions).append(regressions == 1 ? " regression" : " regressions");
        if (newErrors > 0) {
            verdict.append(", ").append(newErrors).append(newErrors == 1 ? " new error" : " new errors");
        }
        verdict.append(", ").append(improved).append(" improved");
        if (newTransactions > 0) {
            verdict.append(", ").append(newTransactions).append(" new transaction")
                    .append(newTransactions == 1 ? "" : "s");
        }
        if (removedTransactions > 0) {
            verdict.append(", ").append(removedTransactions).append(" removed transaction")
                    .append(removedTransactions == 1 ? "" : "s");
        }
        currentVerdictText = verdict.toString();
        verdictLabel.setForeground(pass ? new Color(0x2e, 0x7d, 0x32) : new Color(0xc6, 0x28, 0x28));
        verdictLabel.setText(currentVerdictText);
    }

    private void deleteSelectedBaseline() {
        int idx = baselineCombo.getSelectedIndex();
        if (idx < 0) {
            return;
        }
        CustomDashboardVisualizer.RunSnapshot selected = options.get(idx);
        if (selected.savedAtMillis == 0 && "Current (live)".equals(selected.name)) {
            JOptionPane.showMessageDialog(this, "\u201cCurrent (live)\u201d isn't a saved run \u2014 "
                    + "nothing to delete.", "Can't Delete", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
                "Delete saved run \"" + labelFor(selected) + "\"? This can't be undone.",
                "Delete Run", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }
        CustomDashboardVisualizer.deleteRunSnapshot(selected);
        options.remove(idx);
        baselineCombo.removeItemAt(idx);
        compareCombo.removeItemAt(idx);
        if (baselineCombo.getItemCount() > 0) {
            baselineCombo.setSelectedIndex(Math.min(idx, baselineCombo.getItemCount() - 1));
        }
        recompute();
    }

    private void exportComparison() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("run-comparison.txt"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File target = chooser.getSelectedFile();
        StringBuilder sb = new StringBuilder();
        sb.append("Run Comparison\n");
        sb.append("Baseline: ").append(baselineCombo.getSelectedItem()).append('\n');
        sb.append("Compare:  ").append(compareCombo.getSelectedItem()).append('\n');
        sb.append("Verdict:  ").append(currentVerdictText).append("\n\n");
        sb.append(String.format("%-40s %-12s %10s %10s %8s %10s %10s%n",
                "Transaction", "Status", "Base Avg", "Comp Avg", "Base P95", "Comp P95", "P95 \u0394%"));
        for (ComparisonRow r : currentRows) {
            sb.append(String.format("%-40s %-12s %10.0f %10.0f %8d %10d %9.1f%%%n",
                    r.label, r.status, r.bAvg, r.cAvg, r.bP95, r.cP95, r.p95DeltaPct));
        }
        try {
            Files.write(target.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            JOptionPane.showMessageDialog(this, "Saved to " + target.getAbsolutePath());
        } catch (IOException writeFailed) {
            JOptionPane.showMessageDialog(this, "Could not save: " + writeFailed.getMessage(),
                    "Export Failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static final class ComparisonRow {
        String label = "";
        String status = "";
        double bAvg;
        double cAvg;
        long bP95;
        long cP95;
        double bErrorPct;
        double cErrorPct;
        double p95DeltaPct;
    }

    private static final class ComparisonTableModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private static final String[] COLUMNS = {
            "Transaction", "Status", "Baseline Avg (ms)", "Compare Avg (ms)",
            "Baseline P95 (ms)", "Compare P95 (ms)", "P95 \u0394%",
            "Baseline Err %", "Compare Err %"
        };
        private List<ComparisonRow> rows = new ArrayList<>();

        void setRows(List<ComparisonRow> newRows) {
            rows = newRows;
            fireTableDataChanged();
        }

        ComparisonRow rowAt(int viewRow) {
            return viewRow >= 0 && viewRow < rows.size() ? rows.get(viewRow) : null;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int column) { return COLUMNS[column]; }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 || columnIndex == 1 ? String.class : Object.class;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            ComparisonRow r = rows.get(rowIndex);
            switch (columnIndex) {
                case 0: return r.label;
                case 1: return r.status;
                case 2: return "NEW".equals(r.status) ? "" : String.format("%.0f", r.bAvg);
                case 3: return "REMOVED".equals(r.status) ? "" : String.format("%.0f", r.cAvg);
                case 4: return "NEW".equals(r.status) ? "" : String.valueOf(r.bP95);
                case 5: return "REMOVED".equals(r.status) ? "" : String.valueOf(r.cP95);
                case 6: return "NEW".equals(r.status) || "REMOVED".equals(r.status)
                        ? "" : String.format("%.1f%%", r.p95DeltaPct);
                case 7: return "NEW".equals(r.status) ? "" : String.format("%.2f%%", r.bErrorPct);
                case 8: return "REMOVED".equals(r.status) ? "" : String.format("%.2f%%", r.cErrorPct);
                default: return "";
            }
        }
    }

    /** Tints a whole row by its Status column - red for a regression, green for an
     *  improvement, a muted neutral for new/removed transactions, default otherwise. */
    private static final class StatusAwareRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;
        private final ComparisonTableModel model;

        StatusAwareRenderer(ComparisonTableModel model) {
            this.model = model;
            setHorizontalAlignment(SwingConstants.LEFT);
        }

        @Override
        public java.awt.Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                                  boolean hasFocus, int row, int column) {
            java.awt.Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            ComparisonRow r = model.rowAt(table.convertRowIndexToModel(row));
            if (r != null && !isSelected) {
                switch (r.status) {
                    case "REGRESSION":
                        c.setForeground(new Color(0xc6, 0x28, 0x28));
                        break;
                    case "IMPROVED":
                        c.setForeground(new Color(0x2e, 0x7d, 0x32));
                        break;
                    case "NEW":
                    case "REMOVED":
                        c.setForeground(Color.GRAY);
                        break;
                    default:
                        c.setForeground(null);
                }
            }
            return c;
        }
    }
}
