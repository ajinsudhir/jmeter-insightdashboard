package io.insightdashboard.jmeter;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * Scans one captured response (body + headers) for values that look like
 * they're worth correlating in a load-test script - known token-like field
 * names, UUID-shaped strings, and long, random-looking strings as a lower-
 * confidence fallback - without any code-level dependency on Correlation
 * Studio. The two plugins are separately installable, so a hard dependency,
 * or reflectively invoking Correlation Studio's own dialogs (which expect
 * HAR/JTL-recording-shaped data a live JMeter test execution doesn't
 * produce), would be brittle to ship without a way to test it against both
 * JARs together. "Copy Response Text" instead gives a one-click way to hand
 * this same response to Correlation Studio's own "Find Value" tool, if
 * that's also installed, via the clipboard rather than code.
 */
final class DynamicValuesDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    /** Field/header names whose value is commonly worth correlating - independent from
     *  (not shared code with) Correlation Studio's own, similarly-spirited dictionary. */
    private static final Set<String> KNOWN_DYNAMIC_NAMES = new HashSet<>(Arrays.asList(
            "token", "accesstoken", "refreshtoken", "idtoken", "authtoken", "oauthtoken",
            "apikey", "secret", "csrftoken", "csrf", "xsrftoken", "authenticitytoken",
            "sessionid", "jsessionid", "phpsessid", "session", "sessiontoken",
            "viewstate", "eventvalidation", "nonce", "state", "code", "guid", "uuid",
            "requestid", "correlationid", "transactionid", "executionid", "tabid",
            "clientid", "redirecturi", "sessioncode",
            // Keycloak-specific cookie names, confirmed directly from actual response
            // headers (a Set-Cookie: AUTH_SESSION_ID=...; Set-Cookie: KC_RESTART=...;
            // Set-Cookie: KC_AUTH_SESSION_HASH=... login flow) - a JWT or other token-
            // shaped value sitting in one of these previously fell through to the
            // unnamed, disabled-button fallback since none of them matched anything in
            // this dictionary.
            "authsessionid", "kcrestart", "kcauthsessionhash", "bearertoken",
            "authorizationcode", "grantcode"));

    private static final Pattern NAME_VALUE_PATTERN = Pattern.compile(
            "(?i)\\b([A-Za-z_][A-Za-z0-9_\\-]{2,40})[\"']?\\s*[:=]\\s*[\"']?([A-Za-z0-9\\-_.=]{4,200})[\"']?");
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    /** A fallback for a token-shaped value whose field name didn't match the dictionary
     *  above - long enough, and varied enough in character mix, that it's unlikely to be
     *  ordinary prose text rather than an actual generated value. Deliberately lower
     *  confidence than a name-matched finding, labeled as such in the results. */
    private static final Pattern LONG_TOKEN_PATTERN = Pattern.compile("\\b[A-Za-z0-9_\\-]{24,}\\b");

    private final String producingLabel;
    private final String responseBody;
    private final String responseHeaders;
    private final String requestHeaders;
    private final String requestData;
    private final FindingsTableModel tableModel = new FindingsTableModel();

    DynamicValuesDialog(Frame owner, String label, String responseBody, String responseHeaders,
                         String requestHeaders, String requestData) {
        super(owner, "Find Dynamic Values \u2014 " + label, true);
        this.producingLabel = label == null ? "" : label;
        this.responseBody = responseBody == null ? "" : responseBody;
        this.responseHeaders = responseHeaders == null ? "" : responseHeaders;
        this.requestHeaders = requestHeaders == null ? "" : requestHeaders;
        this.requestData = requestData == null ? "" : requestData;
        buildUi();
        setSize(760, 480);
        setLocationRelativeTo(owner);
        runScan();
    }

    private void buildUi() {
        setLayout(new BorderLayout(8, 8));
        JPanel content = (JPanel) getContentPane();
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel intro = new JLabel("<html><body style='width: 700px'>"
                + "Values found in this response's body and headers that commonly need "
                + "correlating in a load-test script \u2014 a quick heuristic scan, not a "
                + "guarantee; review each one before treating it as something that actually "
                + "needs extracting.</body></html>");
        add(intro, BorderLayout.NORTH);

        JTable table = new JTable(tableModel);
        table.setRowHeight(24);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setFont(table.getTableHeader().getFont().deriveFont(Font.BOLD));
        DefaultTableCellRenderer leftRenderer = new DefaultTableCellRenderer();
        leftRenderer.setHorizontalAlignment(SwingConstants.LEFT);
        table.setDefaultRenderer(Object.class, leftRenderer);
        JScrollPane tableScroll = new JScrollPane(table);
        add(tableScroll, BorderLayout.CENTER);

        JButton copyValueButton = new JButton("Copy Selected Value");
        JButton createRuleButton = new JButton("Create Correlation Rule\u2026");
        createRuleButton.setEnabled(false);
        createRuleButton.setToolTipText("<html>Only available for a \u201cNamed field\u201d finding \u2014 "
                + "that's the only case with an actual field name to anchor<br>a reusable regex to; a "
                + "UUID-shaped or unnamed value has no such anchor to generalize from.</html>");
        JButton copyResponseButton = new JButton("Copy Response Text\u2026");
        copyResponseButton.setToolTipText("<html>Copies the full request and response text to the "
                + "clipboard \u2014 paste it into Correlation Studio's own<br>\u201cFind Value\u201d tool "
                + "if that's also installed, to trace exactly where a specific value came from and "
                + "where it's reused.</html>");
        JButton closeButton = new JButton("Close");
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        south.add(copyValueButton);
        south.add(createRuleButton);
        south.add(copyResponseButton);
        south.add(closeButton);
        add(south, BorderLayout.SOUTH);

        table.getSelectionModel().addListSelectionListener(e -> {
            Finding selected = tableModel.findingAt(table.getSelectedRow());
            createRuleButton.setEnabled(selected != null && "Named field".equals(selected.confidence));
        });

        copyValueButton.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) {
                JOptionPane.showMessageDialog(this, "Select a row first.", "Nothing Selected",
                        JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            copyToClipboard(tableModel.valueAt(row));
        });
        createRuleButton.addActionListener(e -> {
            Finding selected = tableModel.findingAt(table.getSelectedRow());
            if (selected == null) {
                return;
            }
            CreateCorrelationRuleDialog ruleDialog = new CreateCorrelationRuleDialog(
                    (Frame) getOwner(), producingLabel, selected.name, selected.where);
            ruleDialog.setVisible(true);
            if (ruleDialog.wasSaved()) {
                JOptionPane.showMessageDialog(this, "Rule saved \u2014 it'll appear in Correlation "
                        + "Studio's template library next time that dialog is opened or refreshed.",
                        "Rule Saved", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        copyResponseButton.addActionListener(e -> {
            StringBuilder sb = new StringBuilder();
            if (!this.requestData.isEmpty()) {
                sb.append("--- Request ---\n").append(this.requestData).append("\n\n");
            }
            if (!this.requestHeaders.isEmpty()) {
                sb.append("--- Request Headers ---\n").append(this.requestHeaders).append("\n\n");
            }
            if (!this.responseHeaders.isEmpty()) {
                sb.append("--- Response Headers ---\n").append(this.responseHeaders).append("\n\n");
            }
            sb.append("--- Response Body ---\n").append(this.responseBody.isEmpty() ? "(empty)" : this.responseBody);
            copyToClipboard(sb.toString());
        });
        closeButton.addActionListener(e -> dispose());
    }

    private void copyToClipboard(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    private void runScan() {
        // LinkedHashMap keyed by value: the same literal value found more than once (e.g.
        // in both a header and the body) is reported once, not duplicated - first place it
        // was found wins, matching the spirit of "where did this actually come from" rather
        // than listing every place it happens to appear.
        Map<String, Finding> foundByValue = new LinkedHashMap<>();

        scanForNamedValues(responseBody, "response body", foundByValue);
        scanForNamedValues(responseHeaders, "response header", foundByValue);
        scanForUuids(responseBody, "response body", foundByValue);
        scanForUuids(responseHeaders, "response header", foundByValue);
        scanForLongTokens(responseBody, "response body", foundByValue);
        scanForLongTokens(responseHeaders, "response header", foundByValue);

        List<Finding> findings = new ArrayList<>(foundByValue.values());
        tableModel.setFindings(findings);
    }

    private void scanForNamedValues(String text, String whereLabel, Map<String, Finding> out) {
        if (text.isEmpty()) {
            return;
        }
        Matcher m = NAME_VALUE_PATTERN.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            String value = m.group(2);
            if (!KNOWN_DYNAMIC_NAMES.contains(name.toLowerCase().replaceAll("[-_.]", ""))) {
                continue;
            }
            out.putIfAbsent(value, new Finding(name, value, whereLabel, "Named field"));
        }
    }

    private void scanForUuids(String text, String whereLabel, Map<String, Finding> out) {
        if (text.isEmpty()) {
            return;
        }
        Matcher m = UUID_PATTERN.matcher(text);
        while (m.find()) {
            String value = m.group();
            out.putIfAbsent(value, new Finding("(unnamed)", value, whereLabel, "UUID-shaped"));
        }
    }

    private void scanForLongTokens(String text, String whereLabel, Map<String, Finding> out) {
        if (text.isEmpty()) {
            return;
        }
        Matcher m = LONG_TOKEN_PATTERN.matcher(text);
        while (m.find()) {
            String value = m.group();
            if (out.containsKey(value)) {
                continue; // already found with higher confidence via name or UUID shape
            }
            if (!looksLikeRandomToken(value)) {
                continue;
            }
            out.put(value, new Finding("(unnamed)", value, whereLabel, "Possible token"));
        }
    }

    /** A rough filter against the long-token fallback matching ordinary identifiers or
     *  words that just happen to be long (a class name, a long URL path segment) rather
     *  than an actually-generated value: requires a mix of letters and digits, since a
     *  generated token typically has both while plain words typically don't. */
    private static boolean looksLikeRandomToken(String value) {
        boolean hasDigit = false;
        boolean hasLetter = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isDigit(c)) {
                hasDigit = true;
            } else if (Character.isLetter(c)) {
                hasLetter = true;
            }
        }
        return hasDigit && hasLetter;
    }

    private static final class Finding {
        final String name;
        final String value;
        final String where;
        final String confidence;

        Finding(String name, String value, String where, String confidence) {
            this.name = name;
            this.value = value;
            this.where = where;
            this.confidence = confidence;
        }
    }

    private static final class FindingsTableModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private static final String[] COLUMNS = {"Field", "Value", "Found In", "Confidence"};
        private List<Finding> findings = new ArrayList<>();

        void setFindings(List<Finding> findings) {
            this.findings = findings;
            fireTableDataChanged();
        }

        String valueAt(int row) {
            return row >= 0 && row < findings.size() ? findings.get(row).value : "";
        }

        Finding findingAt(int row) {
            return row >= 0 && row < findings.size() ? findings.get(row) : null;
        }

        @Override public int getRowCount() { return findings.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int column) { return COLUMNS[column]; }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Finding f = findings.get(rowIndex);
            switch (columnIndex) {
                case 0: return f.name;
                case 1: return f.value;
                case 2: return f.where;
                case 3: return f.confidence;
                default: return "";
            }
        }
    }
}
