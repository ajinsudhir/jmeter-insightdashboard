package io.insightdashboard.jmeter;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

/**
 * Imports a "wide"-format CSV of externally-collected system resource
 * metrics - a header row, then {@code timestamp,metric1,metric2,...} - so
 * they can be plotted alongside this dashboard's own response-time/
 * throughput/error-rate charts, turning "response time spiked at 14:32"
 * into "...and so did server CPU."
 *
 * Deliberately CSV-based rather than a live HTTP-metrics puller: a generic
 * HTTP endpoint could have any shape, auth scheme, and polling contract,
 * and building a one-size-fits-all live puller without a real endpoint to
 * test it against would be guessing at an integration rather than building
 * one. A CSV is also exactly what the common existing workflow for this
 * already produces - JMeter's own PerfMon plugin, or any simple monitoring
 * script, writing metrics to a file during the run.
 *
 * Exposes {@link #result()} after {@code setVisible(true)} returns: a map
 * from metric name to {@code epoch-second -> value}, in the same absolute-
 * epoch-second key space {@code CustomDashboardVisualizer}'s own per-second
 * buckets already use, so the two align on the same timeline without any
 * further conversion. Null if the dialog was cancelled.
 */
final class ResourceMetricsImportDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    private final JLabel fileLabel = new JLabel("No file selected");
    private final JPanel columnCheckboxPanel = new JPanel();
    private final List<JCheckBox> columnCheckboxes = new ArrayList<>();
    private String[] header;
    private List<String[]> rows;
    private Map<String, Map<Long, Double>> result;

    ResourceMetricsImportDialog(Frame owner) {
        super(owner, "Import Resource Metrics CSV", true);
        buildUi();
        setSize(520, 420);
        setLocationRelativeTo(owner);
    }

    /** The imported metrics (metric name -> epoch-second -> value), or null if the
     *  dialog was cancelled, no file was ever successfully loaded, or no metric
     *  columns were checked when Import was clicked. */
    Map<String, Map<Long, Double>> result() {
        return result;
    }

    private void buildUi() {
        setLayout(new BorderLayout(8, 8));
        JPanel content = (JPanel) getContentPane();
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel intro = new JLabel("<html><body style='width: 460px'>"
                + "Pick a CSV with a header row, formatted as <code>timestamp,metric1,metric2,\u2026</code> "
                + "\u2014 e.g. a PerfMon export pivoted to this shape, or a simple monitoring script's "
                + "output. The first column is assumed to be the timestamp (epoch seconds, epoch "
                + "milliseconds, or a common date-time format are all auto-detected); check which of "
                + "the remaining columns to plot.</body></html>");

        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.add(intro, BorderLayout.NORTH);
        JPanel fileRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton browseButton = new JButton("Browse\u2026");
        fileRow.add(browseButton);
        fileRow.add(fileLabel);
        top.add(fileRow, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        columnCheckboxPanel.setLayout(new BoxLayout(columnCheckboxPanel, BoxLayout.Y_AXIS));
        JScrollPane columnScroll = new JScrollPane(columnCheckboxPanel);
        columnScroll.setBorder(BorderFactory.createTitledBorder("Columns to plot"));
        add(columnScroll, BorderLayout.CENTER);

        JButton importButton = new JButton("Import");
        JButton cancelButton = new JButton("Cancel");
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        south.add(cancelButton);
        south.add(importButton);
        add(south, BorderLayout.SOUTH);

        browseButton.addActionListener(e -> pickFile());
        importButton.addActionListener(e -> tryImport());
        cancelButton.addActionListener(e -> dispose());
    }

    private void pickFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("CSV files", "csv"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        try {
            loadCsv(file);
            fileLabel.setText(file.getName() + " (" + rows.size() + " row(s))");
            populateColumnCheckboxes();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not read this file: " + ex.getMessage(),
                    "Import Failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void loadCsv(File file) throws IOException {
        List<String[]> parsedRows = new ArrayList<>();
        String[] parsedHeader = null;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] fields = line.split(",", -1);
                if (first) {
                    parsedHeader = fields;
                    first = false;
                } else {
                    parsedRows.add(fields);
                }
            }
        }
        if (parsedHeader == null || parsedHeader.length < 2) {
            throw new IOException("Expected a header row with at least a timestamp column and one "
                    + "metric column.");
        }
        this.header = parsedHeader;
        this.rows = parsedRows;
    }

    private void populateColumnCheckboxes() {
        columnCheckboxPanel.removeAll();
        columnCheckboxes.clear();
        columnCheckboxPanel.add(new JLabel("  Timestamp column: " + header[0].trim()));
        columnCheckboxPanel.add(Box.createVerticalStrut(6));
        for (int i = 1; i < header.length; i++) {
            JCheckBox cb = new JCheckBox(header[i].trim(), true);
            columnCheckboxes.add(cb);
            columnCheckboxPanel.add(cb);
        }
        columnCheckboxPanel.revalidate();
        columnCheckboxPanel.repaint();
    }

    private void tryImport() {
        if (rows == null || header == null) {
            JOptionPane.showMessageDialog(this, "Pick a CSV file first.", "No File Selected",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        List<Integer> selectedColumns = new ArrayList<>();
        for (int i = 0; i < columnCheckboxes.size(); i++) {
            if (columnCheckboxes.get(i).isSelected()) {
                selectedColumns.add(i + 1); // +1 since column 0 is the timestamp
            }
        }
        if (selectedColumns.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Check at least one column to plot.",
                    "No Columns Selected", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        Map<String, Map<Long, Double>> parsed = new LinkedHashMap<>();
        for (int col : selectedColumns) {
            parsed.put(header[col].trim(), new LinkedHashMap<>());
        }
        int skippedRows = 0;
        for (String[] row : rows) {
            if (row.length <= selectedColumns.get(selectedColumns.size() - 1)) {
                skippedRows++;
                continue;
            }
            long epochSecond;
            try {
                epochSecond = parseTimestampToEpochSeconds(row[0]);
            } catch (Exception badTimestamp) {
                skippedRows++;
                continue;
            }
            for (int col : selectedColumns) {
                try {
                    double value = Double.parseDouble(row[col].trim());
                    parsed.get(header[col].trim()).put(epochSecond, value);
                } catch (NumberFormatException notANumber) {
                    // Skip just this one cell - a header-only or malformed value in an
                    // otherwise-usable row shouldn't throw away the rest of that row's columns.
                }
            }
        }

        boolean anyDataAtAll = parsed.values().stream().anyMatch(m -> !m.isEmpty());
        if (!anyDataAtAll) {
            JOptionPane.showMessageDialog(this, "Could not parse any usable rows from this file \u2014 "
                    + "check that the timestamp column (the first one) is in a recognizable format "
                    + "(epoch seconds, epoch milliseconds, or a common date-time string).",
                    "Nothing Imported", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (skippedRows > 0) {
            JOptionPane.showMessageDialog(this, skippedRows + " row(s) could not be parsed and were "
                    + "skipped; the rest imported successfully.", "Partial Import",
                    JOptionPane.INFORMATION_MESSAGE);
        }

        result = parsed;
        dispose();
    }

    /** Auto-detects epoch seconds, epoch milliseconds, or one of a handful of common
     *  date-time formats, in that order - the first one that parses without throwing wins.
     *  Returns absolute epoch SECONDS regardless of which form the input was in, matching
     *  CustomDashboardVisualizer's own per-second bucket key space. */
    private static long parseTimestampToEpochSeconds(String raw) {
        String trimmed = raw.trim();
        if (trimmed.matches("\\d+")) {
            long value = Long.parseLong(trimmed);
            // A realistic epoch-seconds "now" is ~10 digits (~2 billion); epoch
            // milliseconds is ~13 digits (~2 trillion) - comfortably separated by this
            // threshold regardless of exactly which moment in time the data is from.
            return value > 100_000_000_000L ? value / 1000L : value;
        }
        String[] patterns = {
            "yyyy-MM-dd HH:mm:ss.SSS",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss",
            "MM/dd/yyyy HH:mm:ss",
            "dd/MM/yyyy HH:mm:ss",
            "MM/dd/yyyy HH:mm",
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(pattern);
                sdf.setLenient(false);
                Date parsedDate = sdf.parse(trimmed);
                return parsedDate.getTime() / 1000L;
            } catch (Exception doesNotMatchThisPattern) {
                // try the next pattern
            }
        }
        throw new IllegalArgumentException("Unrecognized timestamp format: " + raw);
    }
}
