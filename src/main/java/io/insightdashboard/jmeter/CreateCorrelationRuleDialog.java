package io.insightdashboard.jmeter;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

/**
 * Authors a correlation rule from a "Find Dynamic Values" finding and saves
 * it directly to {@code ~/.jmeter-auto-correlator/templates.json} - the same
 * file Correlation Studio's own {@code TemplateStore} reads from and writes
 * to, in the exact JSON shape its {@code CorrelationTemplate.toMap()}
 * already defines (confirmed by comparing the two plugins' {@code
 * MiniJson.java}: byte-for-byte identical aside from the package
 * declaration, so anything written here is guaranteed readable there).
 *
 * Deliberately a plain, shared-file integration rather than any code-level
 * dependency on Correlation Studio - the two are separately-packaged,
 * independently-installable plugins, and reflectively invoking Correlation
 * Studio's own dialogs would be fragile to ship without a way to test it
 * against both JARs installed together. If Correlation Studio isn't even
 * installed, this still works harmlessly: it just writes a JSON file that
 * nothing reads yet.
 *
 * Only offered for a "Named field" finding (see DynamicValuesDialog) - one
 * with an actual field name to anchor a generalizable regex to. A UUID-
 * shaped or unnamed "possible token" finding has no such anchor, and a
 * regex built only from this one response's literal surrounding text would
 * be overfit to it rather than a reusable correlation rule.
 */
final class CreateCorrelationRuleDialog extends JDialog {

    private static final long serialVersionUID = 1L;
    private static final File STORE_FILE = new File(
            new File(System.getProperty("user.home"), ".jmeter-auto-correlator"), "templates.json");

    private final JComboBox<String> projectField = new JComboBox<>();
    private final JTextField nameField = new JTextField();
    private final JTextField producingLabelField = new JTextField();
    private final JTextField variableNameField = new JTextField();
    private final JTextField regexField = new JTextField();
    private final JTextField templateStrField = new JTextField("$1$");
    private final JTextField matchNumberField = new JTextField("1");
    private final JCheckBox fromHeadersCheckbox =
            new JCheckBox("Extract from response headers (unchecked = response body)");
    private final JCheckBox failLoudlyCheckbox = new JCheckBox("Fail loudly if not found on apply");
    private final JLabel statusLabel = new JLabel(" ");
    private boolean saved;

    CreateCorrelationRuleDialog(Frame owner, String producingLabel, String fieldName, String foundIn) {
        super(owner, "Create Correlation Rule", true);
        producingLabelField.setText(producingLabel);
        variableNameField.setText(fieldName);
        nameField.setText(producingLabel + " \u2014 " + fieldName);
        regexField.setText(buildSuggestedRegex(fieldName));
        fromHeadersCheckbox.setSelected("response header".equals(foundIn));
        projectField.setEditable(true);
        List<String> existingProjects = loadExistingProjects();
        for (String project : existingProjects) {
            projectField.addItem(project);
        }
        if (!existingProjects.isEmpty()) {
            projectField.setSelectedIndex(0); // most recently used, per loadExistingProjects()'s own ordering
        }
        buildUi();
        setSize(640, 420);
        setLocationRelativeTo(owner);
    }

    /** True if Save actually wrote a rule - lets the caller show a single, combined
     *  confirmation rather than this dialog and the caller both popping one up. */
    boolean wasSaved() {
        return saved;
    }

    /** A regex anchored to the field's own name, matching the same "name:value" /
     *  "name"="value" shape DynamicValuesDialog's own scanner looked for - generalizable
     *  across different response instances of the same field, unlike a regex built from
     *  one specific matched value would be. */
    private static String buildSuggestedRegex(String fieldName) {
        String escaped = Pattern.quote(fieldName);
        return "(?i)" + escaped + "[\"']?\\s*[:=]\\s*[\"']?([A-Za-z0-9\\-_.=]+)";
    }

    private void buildUi() {
        setLayout(new BorderLayout(8, 8));
        JPanel content = (JPanel) getContentPane();
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel intro = new JLabel("<html><body style='width: 580px'>"
                + "Pre-filled from the selected finding - review and adjust before saving. "
                + "Saves to Correlation Studio's own template library "
                + "(<code>~/.jmeter-auto-correlator/templates.json</code>), where it'll appear "
                + "next time that dialog is opened or refreshed.</body></html>");

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;
        row = addFormRow(form, gbc, row, "Template (project):", projectField,
                "Groups this rule with others for the same application - defaults to whichever "
                        + "project was used last, pick a different existing one from the dropdown, "
                        + "or type a new name to start a fresh template.");
        row = addFormRow(form, gbc, row, "Rule name:", nameField, null);
        row = addFormRow(form, gbc, row, "Producing sampler:", producingLabelField,
                "The request whose response this value came from.");
        row = addFormRow(form, gbc, row, "Variable name:", variableNameField,
                "What JMeter will call the extracted value, e.g. ${variableName}.");
        row = addFormRow(form, gbc, row, "Regex:", regexField,
                "Must compile and contain a capturing group () - that's what gets extracted.");
        row = addFormRow(form, gbc, row, "Template string:", templateStrField, "Usually $1$ (the first group).");
        row = addFormRow(form, gbc, row, "Match number:", matchNumberField,
                "1 for the first match; -1 for a random match (JMeter's RegexExtractor convention).");

        gbc.gridx = 1;
        gbc.gridy = row++;
        form.add(fromHeadersCheckbox, gbc);
        gbc.gridy = row++;
        form.add(failLoudlyCheckbox, gbc);

        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.add(intro, BorderLayout.NORTH);
        top.add(form, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);

        JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statusLabel.setForeground(new Color(0xc6, 0x28, 0x28));
        statusRow.add(statusLabel);
        add(statusRow, BorderLayout.CENTER);

        JButton saveButton = new JButton("Save Rule");
        JButton cancelButton = new JButton("Cancel");
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        south.add(cancelButton);
        south.add(saveButton);
        add(south, BorderLayout.SOUTH);

        saveButton.addActionListener(e -> trySave());
        cancelButton.addActionListener(e -> dispose());
    }

    private int addFormRow(JPanel form, GridBagConstraints gbc, int row, String label,
                            JComponent field, String tooltip) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        form.add(new JLabel(label), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        if (tooltip != null) {
            field.setToolTipText(tooltip);
        }
        form.add(field, gbc);
        return row + 1;
    }

    private void showError(String message) {
        statusLabel.setText(message);
    }

    /** The combo box's current text, including anything just typed but not yet committed
     *  (by pressing Enter or tabbing out) - plain getSelectedItem() can lag behind that for
     *  an editable combo box, so this reads its editor's own JTextField directly instead,
     *  which always reflects what's actually currently typed. */
    private String currentProjectText() {
        Object editorComponent = projectField.getEditor().getEditorComponent();
        return editorComponent instanceof JTextField
                ? ((JTextField) editorComponent).getText().trim()
                : String.valueOf(projectField.getSelectedItem()).trim();
    }

    private void trySave() {
        String project = currentProjectText();
        String name = nameField.getText().trim();
        String producingLabel = producingLabelField.getText().trim();
        String variableName = variableNameField.getText().trim();
        String regex = regexField.getText().trim();

        if (project.isEmpty()) {
            showError("Give this a template (project) name.");
            return;
        }
        if (name.isEmpty()) {
            showError("Give this rule a name.");
            return;
        }
        if (variableName.isEmpty()) {
            showError("Give this rule a variable name.");
            return;
        }
        if (regex.isEmpty()) {
            showError("A regex is required.");
            return;
        }
        Pattern compiled;
        try {
            compiled = Pattern.compile(regex);
        } catch (PatternSyntaxException badRegex) {
            showError("Regex doesn't compile: " + badRegex.getMessage());
            return;
        }
        if (compiled.matcher("").groupCount() == 0) {
            showError("This regex has no capturing group () \u2014 nothing would actually be "
                    + "extracted. Wrap the part you want captured in parentheses.");
            return;
        }
        String matchNumber = matchNumberField.getText().trim();
        if (!matchNumber.isEmpty()) {
            try {
                int n = Integer.parseInt(matchNumber);
                if (n == 0) {
                    showError("Match number can't be 0 \u2014 use 1 for the first match, or -1 for a "
                            + "random match.");
                    return;
                }
            } catch (NumberFormatException notANumber) {
                showError("Match number must be an integer.");
                return;
            }
        }

        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("name", name);
        rule.put("project", project);
        rule.put("producingLabel", producingLabel);
        rule.put("variableName", variableName);
        rule.put("regex", regex);
        rule.put("templateStr", templateStrField.getText().trim().isEmpty() ? "$1$" : templateStrField.getText().trim());
        rule.put("matchNumber", matchNumber.isEmpty() ? "1" : matchNumber);
        rule.put("fromHeaders", fromHeadersCheckbox.isSelected());
        rule.put("failLoudly", failLoudlyCheckbox.isSelected());
        rule.put("consumingLabels", new ArrayList<Object>());

        try {
            appendToStore(rule);
        } catch (Exception writeFailed) {
            showError("Could not save: " + writeFailed.getMessage());
            return;
        }
        saved = true;
        dispose();
    }

    /** Reads the existing template store (if any) and returns its distinct "project"
     *  names, most-recently-used first - so the combo box above naturally defaults to
     *  whichever project was last worked in (its first item, selected explicitly in the
     *  constructor) while every other existing project is still one click away, or a
     *  brand new name can be typed since the combo box is editable. Walks the stored list
     *  newest-to-oldest, since appendToStore() always appends to the end, so the first
     *  distinct project encountered this way is the most recently used one. Tolerates a
     *  missing or corrupt store file the same way appendToStore() does: an empty list
     *  rather than a failure. */
    @SuppressWarnings("unchecked")
    private static List<String> loadExistingProjects() {
        List<String> projects = new ArrayList<>();
        if (!STORE_FILE.exists()) {
            return projects;
        }
        try {
            String text = new String(Files.readAllBytes(STORE_FILE.toPath()), StandardCharsets.UTF_8);
            Object parsed = MiniJson.parse(text);
            if (!(parsed instanceof List)) {
                return projects;
            }
            List<Object> templates = (List<Object>) parsed;
            for (int i = templates.size() - 1; i >= 0; i--) {
                Object entry = templates.get(i);
                if (entry instanceof Map) {
                    Object project = ((Map<String, Object>) entry).get("project");
                    if (project instanceof String && !((String) project).isEmpty()
                            && !projects.contains(project)) {
                        projects.add((String) project);
                    }
                }
            }
        } catch (Exception corruptOrUnreadable) {
            // Same tolerance as appendToStore() - an empty list rather than a failure.
        }
        return projects;
    }

    /** Reads the existing template list (if any), appends this rule, writes it back -
     *  tolerates a missing or corrupt store file by starting a fresh list rather than
     *  failing outright, the same resilience Correlation Studio's own TemplateStore has. */
    @SuppressWarnings("unchecked")
    private static void appendToStore(Map<String, Object> newRule) throws IOException {
        List<Object> templates = new ArrayList<>();
        if (STORE_FILE.exists()) {
            try {
                String text = new String(Files.readAllBytes(STORE_FILE.toPath()), StandardCharsets.UTF_8);
                Object parsed = MiniJson.parse(text);
                if (parsed instanceof List) {
                    templates.addAll((List<Object>) parsed);
                }
            } catch (Exception corruptOrUnreadable) {
                // Start fresh rather than losing the new rule over an unreadable old file.
            }
        }
        templates.add(newRule);
        File parent = STORE_FILE.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        Files.write(STORE_FILE.toPath(), MiniJson.write(templates).getBytes(StandardCharsets.UTF_8));
    }
}
