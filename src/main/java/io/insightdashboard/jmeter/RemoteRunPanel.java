package io.insightdashboard.jmeter;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;

import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.save.CSVSaveService;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.visualizers.Visualizer;

/**
 * Triggers a non-GUI JMeter run on a remote host over SSH - the same
 * {@code JVM_ARGS="..." .../bin/jmeter -n -t ... -l ... -j ...} someone
 * would otherwise type by hand in a terminal - and optionally live-monitors
 * its progress by periodically pulling the growing result file back and
 * feeding it into this same dashboard's existing charts/table.
 *
 * <p>Live monitoring deliberately re-downloads the whole remote file each
 * poll rather than an incremental byte range (see
 * {@link RemoteRunController#downloadFile} for why - the CSV header only
 * exists once, at the start of the file) and de-duplicates by skipping
 * however many rows were already processed in earlier polls - see
 * {@link SkippingVisualizer} below.
 */
final class RemoteRunPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /** A JPanel that always matches its enclosing JScrollPane's viewport
     *  width, scrolling only vertically - without this, a BoxLayout-managed
     *  panel placed inside a JScrollPane just reports its own natural
     *  preferred width, which would leave the side-by-side sections
     *  narrower than the available space instead of actually using it.
     *  Anonymous class syntax can't both extend JPanel and implement
     *  Scrollable in one expression, which is why this is a proper, named
     *  class rather than an inline one. */
    private static final class ScrollableTopPanel extends JPanel implements javax.swing.Scrollable {
        private static final long serialVersionUID = 1L;

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle visibleRect, int orientation, int direction) {
            return 64;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /**
     * One saved connection + remote-test configuration, everything except
     * the password/passphrase - those are deliberately never persisted,
     * same principle as the rest of this plugin: a password is held only
     * in memory for the life of a connection, never written to disk.
     * Re-entering it each session is the accepted tradeoff for that.
     */
    private static final class HostProfile {
        String name = "";
        String host = "";
        int port = 22;
        String username = "";
        String authMethod = "PASSWORD"; // matches RemoteRunController.AuthMethod.name()
        String keyPath = "";
        String jvmArgs = "-Xms1024m -Xmx8G";
        String jmeterBinDir = "";
        String jmxPath = "";
        String outputCsvPath = "";
        String logPath = "";
        String extraArgs = "";

        @Override
        public String toString() {
            return name; // so the JComboBox's default renderer shows the name directly
        }
    }

    private static final File PROFILES_FILE = new File(
            new File(System.getProperty("user.home"), ".jmeter-insightdashboard"), "remote-run-profiles.json");

    private final java.util.List<HostProfile> profiles = new java.util.ArrayList<>();
    private final javax.swing.JComboBox<HostProfile> profileCombo = new javax.swing.JComboBox<>();

    /** Best-effort: a corrupt or unreadable file starts with an empty list
     *  rather than failing to open the dialog at all. */
    private void loadProfiles() {
        try {
            if (!PROFILES_FILE.exists()) {
                return;
            }
            String json = new String(java.nio.file.Files.readAllBytes(PROFILES_FILE.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            Object parsed = MiniJson.parse(json);
            if (!(parsed instanceof java.util.Map)) {
                return;
            }
            Object profilesObj = ((java.util.Map<?, ?>) parsed).get("profiles");
            if (!(profilesObj instanceof java.util.List)) {
                return;
            }
            for (Object o : (java.util.List<?>) profilesObj) {
                if (!(o instanceof java.util.Map)) {
                    continue;
                }
                java.util.Map<?, ?> m = (java.util.Map<?, ?>) o;
                HostProfile p = new HostProfile();
                p.name = stringOrDefault(m.get("name"), "");
                p.host = stringOrDefault(m.get("host"), "");
                p.port = m.get("port") instanceof Number ? ((Number) m.get("port")).intValue() : 22;
                p.username = stringOrDefault(m.get("username"), "");
                p.authMethod = stringOrDefault(m.get("authMethod"), "PASSWORD");
                p.keyPath = stringOrDefault(m.get("keyPath"), "");
                p.jvmArgs = stringOrDefault(m.get("jvmArgs"), "-Xms1024m -Xmx8G");
                p.jmeterBinDir = stringOrDefault(m.get("jmeterBinDir"), "");
                p.jmxPath = stringOrDefault(m.get("jmxPath"), "");
                p.outputCsvPath = stringOrDefault(m.get("outputCsvPath"), "");
                p.logPath = stringOrDefault(m.get("logPath"), "");
                p.extraArgs = stringOrDefault(m.get("extraArgs"), "");
                profiles.add(p);
            }
        } catch (Exception ignored) {
            profiles.clear();
        }
    }

    private static String stringOrDefault(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    /** Best-effort: a failed save shouldn't block anything else in this
     *  panel from working. */
    private void saveProfiles() {
        try {
            File dir = PROFILES_FILE.getParentFile();
            if (!dir.exists() && !dir.mkdirs()) {
                throw new java.io.IOException("Could not create " + dir);
            }
            java.util.List<Object> profilesJson = new java.util.ArrayList<>();
            for (HostProfile p : profiles) {
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("name", p.name);
                m.put("host", p.host);
                m.put("port", p.port);
                m.put("username", p.username);
                m.put("authMethod", p.authMethod);
                m.put("keyPath", p.keyPath);
                m.put("jvmArgs", p.jvmArgs);
                m.put("jmeterBinDir", p.jmeterBinDir);
                m.put("jmxPath", p.jmxPath);
                m.put("outputCsvPath", p.outputCsvPath);
                m.put("logPath", p.logPath);
                m.put("extraArgs", p.extraArgs);
                profilesJson.add(m);
            }
            java.util.Map<String, Object> root = new java.util.LinkedHashMap<>();
            root.put("profiles", profilesJson);
            java.nio.file.Files.write(PROFILES_FILE.toPath(),
                    MiniJson.write(root).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ex) {
            javax.swing.JOptionPane.showMessageDialog(this, "Could not save profile: " + ex.getMessage(),
                    "Save Profile", javax.swing.JOptionPane.ERROR_MESSAGE);
        }
    }

    private HostProfile captureCurrentAsProfile(String name) {
        HostProfile p = new HostProfile();
        p.name = name;
        p.host = hostField.getText().trim();
        p.port = parsePortOrDefault();
        p.username = usernameField.getText().trim();
        p.authMethod = selectedAuthMethod().name();
        p.keyPath = keyPathField.getText().trim();
        p.jvmArgs = jvmArgsField.getText();
        p.jmeterBinDir = jmeterBinDirField.getText().trim();
        p.jmxPath = jmxPathField.getText().trim();
        p.outputCsvPath = outputCsvField.getText().trim();
        p.logPath = logFileField.getText().trim();
        p.extraArgs = extraArgsField.getText();
        return p;
    }

    /** Password/passphrase fields are deliberately left untouched here (not
     *  cleared, not filled) - loading a profile never implies a credential
     *  was saved, so there's nothing for it to restore there. */
    private void applyProfile(HostProfile p) {
        hostField.setText(p.host);
        portField.setText(String.valueOf(p.port));
        usernameField.setText(p.username);
        if ("KEY".equals(p.authMethod)) {
            keyAuthRadio.setSelected(true);
        } else {
            passwordAuthRadio.setSelected(true);
        }
        updateAuthFieldState();
        keyPathField.setText(p.keyPath);
        jvmArgsField.setText(p.jvmArgs);
        jmeterBinDirField.setText(p.jmeterBinDir);
        jmxPathField.setText(p.jmxPath);
        outputCsvField.setText(p.outputCsvPath);
        logFileField.setText(p.logPath);
        extraArgsField.setText(p.extraArgs);
    }

    private void refreshProfileCombo() {
        HostProfile selected = (HostProfile) profileCombo.getSelectedItem();
        profileCombo.removeAllItems();
        for (HostProfile p : profiles) {
            profileCombo.addItem(p);
        }
        if (selected != null && profiles.contains(selected)) {
            profileCombo.setSelectedItem(selected);
        }
    }

    private void saveCurrentProfile() {
        String name = javax.swing.JOptionPane.showInputDialog(this,
                "Profile name:", profileCombo.getSelectedItem() == null ? ""
                        : ((HostProfile) profileCombo.getSelectedItem()).name);
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        final String trimmedName = name.trim();
        profiles.removeIf(p -> p.name.equals(trimmedName)); // overwrite an existing profile with the same name
        profiles.add(captureCurrentAsProfile(trimmedName));
        saveProfiles();
        refreshProfileCombo();
        for (int i = 0; i < profileCombo.getItemCount(); i++) {
            if (profileCombo.getItemAt(i).name.equals(trimmedName)) {
                profileCombo.setSelectedIndex(i);
                break;
            }
        }
    }

    private void deleteCurrentProfile() {
        HostProfile selected = (HostProfile) profileCombo.getSelectedItem();
        if (selected == null) {
            return;
        }
        profiles.remove(selected);
        saveProfiles();
        refreshProfileCombo();
    }

    private final Visualizer dashboardTarget;
    private final RemoteRunController controller = new RemoteRunController();

    // Connection fields
    private final JTextField hostField = new JTextField(18);
    private final JTextField portField = new JTextField("22", 4);
    private final JTextField usernameField = new JTextField(14);
    private final JRadioButton passwordAuthRadio = new JRadioButton("Password", true);
    private final JRadioButton keyAuthRadio = new JRadioButton("SSH Key");
    private final JPasswordField passwordField = new JPasswordField(16);
    private final JTextField keyPathField = new JTextField(
            System.getProperty("user.home") + "/.ssh/id_rsa", 20);
    private final JPasswordField keyPassphraseField = new JPasswordField(14);
    private final JLabel connectionStatusLabel = new JLabel("Not connected");

    // Remote test configuration
    private final JTextField jvmArgsField = new JTextField("-Xms1024m -Xmx8G", 20);
    private final JTextField jmeterBinDirField = new JTextField(30);
    private final JTextField jmxPathField = new JTextField(30);
    private final JTextField outputCsvField = new JTextField(30);
    private final JTextField logFileField = new JTextField(30);
    private final JTextField extraArgsField = new JTextField(20);
    private final JCheckBox uploadJmxFirstCheckbox =
            new JCheckBox("Upload the currently-open .jmx here before running", true);
    private final javax.swing.JRadioButton csvFormatRadio =
            new javax.swing.JRadioButton("CSV (live updates while running, flat list)", true);
    private final javax.swing.JRadioButton xmlFormatRadio =
            new javax.swing.JRadioButton("XML (full transaction tree, shown once the test finishes)");

    // Run controls
    private final JButton startButton = new JButton("Start Remote Test");
    private final JButton stopGracefulButton = new JButton("Stop (Graceful)");
    private final JButton stopAbruptButton = new JButton("Stop (Abrupt)");
    private final JLabel runStatusLabel = new JLabel("Idle");
    private final JCheckBox liveMonitoringCheckbox = new JCheckBox("Live monitoring (feeds this dashboard)", true);
    private final JSpinner pollIntervalSpinner = new JSpinner(new SpinnerNumberModel(3, 1, 60, 1));
    private final JTextArea outputArea = new JTextArea();

    private Timer liveMonitorTimer;
    private File localTempCsv;
    private volatile boolean pollInFlight;
    /** CSVSaveService.processSamples() calls isErrorLogging()/
     *  isSuccessOnlyLogging() directly on this with no null-check in its
     *  own source (confirmed, not assumed) - null would throw NPE on the
     *  very first poll. A plain, freshly-constructed ResultCollector has
     *  both flags false, meaning no error/success-only filtering, which is
     *  exactly what's wanted here: every sample forwarded, none dropped. */
    private final ResultCollector resultCollectorForCsvReplay = new ResultCollector();

    RemoteRunPanel(Visualizer dashboardTarget) {
        this.dashboardTarget = dashboardTarget;
        setLayout(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel top = new ScrollableTopPanel();
        top.setLayout(new javax.swing.BoxLayout(top, javax.swing.BoxLayout.Y_AXIS));
        JPanel sideBySide = new JPanel(new GridLayout(1, 2, 8, 0));
        sideBySide.add(makeCollapsibleSection("Remote host", buildConnectionSection()));
        sideBySide.add(makeCollapsibleSection("Remote test", buildTestConfigSection()));
        top.add(sideBySide);
        top.add(makeCollapsibleSection("Run", buildControlsSection()));

        // Wrapped in its own scroll pane (rather than BorderLayout.NORTH,
        // which just takes its preferred height and clips whatever doesn't
        // fit) so the config sections can scroll independently of the
        // output log below - needed since collapsing/expanding sections
        // changes this area's height, and the window itself may simply not
        // be tall enough for everything expanded at once.
        JScrollPane topScroll = new JScrollPane(top);
        topScroll.setBorder(BorderFactory.createEmptyBorder());
        topScroll.getVerticalScrollBar().setUnitIncrement(16);

        outputArea.setEditable(false);
        outputArea.setLineWrap(true);
        outputArea.setWrapStyleWord(true);
        outputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane outputScroll = new JScrollPane(outputArea);
        outputScroll.setBorder(BorderFactory.createTitledBorder("Remote output"));

        javax.swing.JSplitPane mainSplit = new javax.swing.JSplitPane(
                javax.swing.JSplitPane.VERTICAL_SPLIT, topScroll, outputScroll);
        mainSplit.setResizeWeight(0.75); // most of the space defaults to config, some to the output log
        add(mainSplit, BorderLayout.CENTER);

        ButtonGroup authGroup = new ButtonGroup();
        authGroup.add(passwordAuthRadio);
        authGroup.add(keyAuthRadio);
        passwordAuthRadio.addActionListener(e -> updateAuthFieldState());
        keyAuthRadio.addActionListener(e -> updateAuthFieldState());
        updateAuthFieldState();

        stopGracefulButton.setEnabled(false);
        stopAbruptButton.setEnabled(false);

        loadProfiles();
        refreshProfileCombo();
        profileCombo.setSelectedItem(null); // don't auto-apply a profile just because one exists
    }

    /** Wraps a content panel with a clickable header that toggles its
     *  visibility - replaces a plain titled border with something
     *  interactive, so a section can be collapsed when its fields aren't
     *  currently needed. */
    private JPanel makeCollapsibleSection(String title, JPanel content) {
        JPanel wrapper = new JPanel(new BorderLayout());
        JButton header = new JButton("\u25bc " + title);
        header.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        header.setMargin(new Insets(2, 4, 2, 4));
        header.setFocusPainted(false);
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        header.addActionListener(e -> {
            boolean nowVisible = !content.isVisible();
            content.setVisible(nowVisible);
            header.setText((nowVisible ? "\u25bc " : "\u25b6 ") + title);
            revalidate();
            repaint();
        });
        wrapper.add(header, BorderLayout.NORTH);
        wrapper.add(content, BorderLayout.CENTER);
        return wrapper;
    }

    private void updateAuthFieldState() {
        boolean usePassword = passwordAuthRadio.isSelected();
        passwordField.setEnabled(usePassword);
        keyPathField.setEnabled(!usePassword);
        keyPassphraseField.setEnabled(!usePassword);
    }

    private JPanel buildConnectionSection() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 4, 2, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        gbc.gridy = row;
        gbc.gridx = 0;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        panel.add(new JLabel("Profile:"), gbc);
        gbc.gridx = 1;
        gbc.gridwidth = 2;
        gbc.weightx = 1;
        profileCombo.setPrototypeDisplayValue(new HostProfile());
        profileCombo.addActionListener(e -> {
            HostProfile selected = (HostProfile) profileCombo.getSelectedItem();
            if (selected != null) {
                applyProfile(selected);
            }
        });
        panel.add(profileCombo, gbc);
        gbc.gridx = 3;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.EAST;
        JPanel profileButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton saveProfileButton = compactButton("Save\u2026");
        saveProfileButton.addActionListener(e -> saveCurrentProfile());
        JButton deleteProfileButton = compactButton("Delete");
        deleteProfileButton.addActionListener(e -> deleteCurrentProfile());
        profileButtons.add(saveProfileButton);
        profileButtons.add(deleteProfileButton);
        panel.add(profileButtons, gbc);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        row++;

        addRow(panel, gbc, row++, "Host:", hostField, "Port:", portField);
        addRow(panel, gbc, row++, "Username:", usernameField, null, null);

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 4;
        JPanel authChoiceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        authChoiceRow.add(passwordAuthRadio);
        authChoiceRow.add(passwordField);
        panel.add(authChoiceRow, gbc);
        row++;

        gbc.gridy = row;
        JPanel keyChoiceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        keyChoiceRow.add(keyAuthRadio);
        panel.add(keyChoiceRow, gbc);
        row++;

        JButton browseKeyButton = compactButton("Browse\u2026");
        browseKeyButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(System.getProperty("user.home") + "/.ssh");
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                keyPathField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        gbc.gridy = row;
        gbc.gridx = 0;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        panel.add(new JLabel("Key file:"), gbc);
        gbc.gridx = 1;
        gbc.gridwidth = 2;
        gbc.weightx = 1;
        panel.add(keyPathField, gbc);
        gbc.gridx = 3;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.EAST;
        panel.add(browseKeyButton, gbc);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        row++;

        gbc.gridy = row;
        gbc.gridx = 0;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        panel.add(new JLabel("Passphrase:"), gbc);
        gbc.gridx = 1;
        gbc.gridwidth = 3;
        gbc.weightx = 1;
        panel.add(keyPassphraseField, gbc);
        gbc.weightx = 0;
        row++;

        JButton testConnectionButton = new JButton("Test Connection");
        testConnectionButton.addActionListener(e -> testConnection());
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 1;
        panel.add(testConnectionButton, gbc);
        gbc.gridx = 1;
        gbc.gridwidth = 3;
        connectionStatusLabel.setForeground(new Color(0x80, 0x80, 0x80));
        panel.add(connectionStatusLabel, gbc);

        return panel;
    }

    private JPanel buildTestConfigSection() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 4, 2, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        gbc.gridy = row;
        gbc.gridx = 0;
        gbc.gridwidth = 4;
        JButton autoFillButton = new JButton("Auto-fill paths from currently-open .jmx + timestamp");
        autoFillButton.setToolTipText("<html>Names the remote .jmx, output CSV, and log using the "
                + "currently-open test plan's own filename plus a<br>timestamp, placed alongside "
                + "Remote JMeter bin dir \u2014 also turns on \"Upload the .jmx here\" below, since<br>"
                + "generating a new remote .jmx path only makes sense together with putting the file "
                + "there.</html>");
        autoFillButton.addActionListener(e -> autoFillRemotePaths());
        panel.add(autoFillButton, gbc);
        row++;

        addRow(panel, gbc, row++, "JVM_ARGS:", jvmArgsField, "Extra args:", extraArgsField);
        addFullRowWithBrowse(panel, gbc, row++, "Remote JMeter bin dir:", jmeterBinDirField);
        addFullRowWithBrowse(panel, gbc, row++, "Remote .jmx path:", jmxPathField);

        gbc.gridy = row;
        gbc.gridx = 1;
        gbc.gridwidth = 3;
        panel.add(uploadJmxFirstCheckbox, gbc);
        row++;

        addFullRowWithBrowse(panel, gbc, row++, "Remote output (-l) CSV path:", outputCsvField);
        addFullRowWithBrowse(panel, gbc, row++, "Remote log (-j) path:", logFileField);
        return panel;
    }

    /**
     * Names the remote .jmx/output CSV/log using the currently-open test
     * plan's own filename (via GuiPackage, the standard way to find it -
     * confirmed before using it, not assumed) plus a timestamp, placed
     * alongside whatever's already in Remote JMeter bin dir - matching the
     * convention already visible in this person's own manual workflow
     * (co-locating the script and its outputs under the JMeter bin
     * directory). Deliberately uses an unambiguous yyyyMMdd_HHmmss format
     * rather than guessing at which of DD_MM_YY or MM_DD_YY a two-digit
     * manual convention meant.
     */
    private void autoFillRemotePaths() {
        String testPlanFile = org.apache.jmeter.gui.GuiPackage.getInstance().getTestPlanFile();
        if (testPlanFile == null || testPlanFile.trim().isEmpty()) {
            javax.swing.JOptionPane.showMessageDialog(this,
                    "The current test plan hasn't been saved yet \u2014 save it first so there's a "
                            + "filename to base the remote paths on.",
                    "Auto-fill", javax.swing.JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String baseName = new File(testPlanFile).getName();
        if (baseName.toLowerCase(java.util.Locale.ROOT).endsWith(".jmx")) {
            baseName = baseName.substring(0, baseName.length() - 4);
        }
        String sanitized = baseName.replaceAll("[^A-Za-z0-9_-]+", "_");
        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());

        String binDir = jmeterBinDirField.getText().trim();
        if (binDir.isEmpty()) {
            javax.swing.JOptionPane.showMessageDialog(this,
                    "Fill in Remote JMeter bin dir first \u2014 the generated paths are placed there.",
                    "Auto-fill", javax.swing.JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String dir = binDir.endsWith("/") ? binDir.substring(0, binDir.length() - 1) : binDir;
        String stem = sanitized + "_" + timestamp;

        jmxPathField.setText(dir + "/" + stem + ".jmx");
        outputCsvField.setText(dir + "/" + stem + ".csv");
        logFileField.setText(dir + "/" + stem + ".log");
        uploadJmxFirstCheckbox.setSelected(true);
    }

    private JPanel buildControlsSection() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        startButton.addActionListener(e -> startTest());
        stopGracefulButton.addActionListener(e -> stopTest(false));
        stopAbruptButton.addActionListener(e -> stopTest(true));
        buttonRow.add(startButton);
        buttonRow.add(stopGracefulButton);
        buttonRow.add(stopAbruptButton);
        runStatusLabel.setForeground(new Color(0x80, 0x80, 0x80));
        buttonRow.add(runStatusLabel);
        panel.add(buttonRow, BorderLayout.NORTH);

        javax.swing.ButtonGroup formatGroup = new javax.swing.ButtonGroup();
        formatGroup.add(csvFormatRadio);
        formatGroup.add(xmlFormatRadio);
        xmlFormatRadio.setToolTipText("<html>CSV can be read safely while the test is still running, so "
                + "it's what makes \"live\" possible - but it's a flat<br>format with no way to encode "
                + "a transaction's sub-requests. XML preserves that structure, but JMeter's own XML "
                + "output<br>isn't valid, parseable XML until the run finishes and its root element "
                + "is properly closed - so this shows the full<br>tree only once the test is done, not "
                + "progressively during it.</html>");
        JPanel formatRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        formatRow.add(new JLabel("Output format:"));
        formatRow.add(csvFormatRadio);
        formatRow.add(xmlFormatRadio);

        JPanel monitorRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        monitorRow.add(liveMonitoringCheckbox);
        monitorRow.add(new JLabel("Poll every (s):"));
        monitorRow.add(pollIntervalSpinner);

        JPanel southStack = new JPanel();
        southStack.setLayout(new javax.swing.BoxLayout(southStack, javax.swing.BoxLayout.Y_AXIS));
        southStack.add(formatRow);
        southStack.add(monitorRow);
        panel.add(southStack, BorderLayout.SOUTH);

        return panel;
    }

    /** Shared by every row-builder below: a small, fixed button sized to
     *  its own text rather than being stretched wide by fill=HORIZONTAL -
     *  the actual fix for "Browse... too big", not just a side effect of
     *  the weightx fix elsewhere in these methods. */
    private static JButton compactButton(String text) {
        JButton button = new JButton(text);
        button.setMargin(new Insets(1, 6, 1, 6));
        return button;
    }

    private void addRow(JPanel panel, GridBagConstraints gbc, int row,
                         String label1, JTextField field1, String label2, JTextField field2) {
        gbc.gridy = row;
        gbc.gridx = 0;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        panel.add(new JLabel(label1), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        panel.add(field1, gbc);
        if (label2 != null) {
            gbc.gridx = 2;
            gbc.weightx = 0;
            panel.add(new JLabel(label2), gbc);
            gbc.gridx = 3;
            gbc.weightx = 1;
            panel.add(field2, gbc);
        }
        gbc.weightx = 0;
    }

    private void addFullRow(JPanel panel, GridBagConstraints gbc, int row, String label, JTextField field) {
        gbc.gridy = row;
        gbc.gridx = 0;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        panel.add(new JLabel(label), gbc);
        gbc.gridx = 1;
        gbc.gridwidth = 3;
        gbc.weightx = 1;
        panel.add(field, gbc);
        gbc.weightx = 0;
    }

    private void addFullRowWithBrowse(JPanel panel, GridBagConstraints gbc, int row, String label, JTextField field) {
        gbc.gridy = row;
        gbc.gridx = 0;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        panel.add(new JLabel(label), gbc);
        gbc.gridx = 1;
        gbc.gridwidth = 2;
        gbc.weightx = 1;
        panel.add(field, gbc);
        gbc.gridx = 3;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.EAST;
        JButton browseButton = compactButton("Browse\u2026");
        browseButton.addActionListener(e -> browseForRemotePath(field));
        panel.add(browseButton, gbc);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
    }

    private void appendOutput(String line) {
        SwingUtilities.invokeLater(() -> {
            outputArea.append(line);
            outputArea.append("\n");
            outputArea.setCaretPosition(outputArea.getDocument().getLength());
        });
    }

    // ---- Remote file browsing ----

    /** Connects first if not already connected (using whatever's currently
     *  in the connection fields), then opens the remote file browser - so
     *  Browse works right away without a separate, explicit "Connect"
     *  step the person would otherwise have to remember to do first. */
    private void browseForRemotePath(JTextField targetField) {
        if (controller.isConnected()) {
            openFileBrowser(targetField);
            return;
        }

        String host = hostField.getText().trim();
        int port = parsePortOrDefault();
        String username = usernameField.getText().trim();
        RemoteRunController.AuthMethod authMethod = selectedAuthMethod();
        String password = new String(passwordField.getPassword());
        String keyPath = keyPathField.getText().trim();
        String keyPassphrase = new String(keyPassphraseField.getPassword());

        if (host.isEmpty() || username.isEmpty()) {
            javax.swing.JOptionPane.showMessageDialog(this,
                    "Fill in host and username first.", "Browse", javax.swing.JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        new SwingWorker<Void, Void>() {
            private Exception error;

            @Override
            protected Void doInBackground() {
                try {
                    controller.connect(host, port, username, authMethod, password, keyPath, keyPassphrase, 10000);
                } catch (Exception ex) {
                    error = ex;
                }
                return null;
            }

            @Override
            protected void done() {
                if (error != null) {
                    javax.swing.JOptionPane.showMessageDialog(RemoteRunPanel.this,
                            "Could not connect: " + error.getMessage(), "Browse",
                            javax.swing.JOptionPane.ERROR_MESSAGE);
                    return;
                }
                connectionStatusLabel.setForeground(new Color(0x2e, 0x7d, 0x32));
                connectionStatusLabel.setText("Connected OK");
                openFileBrowser(targetField);
            }
        }.execute();
    }

    private void openFileBrowser(JTextField targetField) {
        String current = targetField.getText().trim();
        String startPath = current.isEmpty() ? "/" : parentDirectoryGuess(current);
        RemoteFileBrowserDialog dialog = new RemoteFileBrowserDialog(
                SwingUtilities.getWindowAncestor(this), controller, startPath);
        String selected = dialog.showAndGetSelectedPath();
        if (selected != null && !selected.isEmpty()) {
            targetField.setText(selected);
        }
    }

    /** A guess, not a real lookup - deliberately avoids an extra blocking
     *  SFTP stat() call just to decide where to start browsing from. If
     *  the guess is wrong, the dialog's own Directory field is still
     *  directly editable, so nothing is actually blocked by it. */
    private static String parentDirectoryGuess(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash <= 0 ? "/" : path.substring(0, lastSlash);
    }

    // ---- Connection ----

    private RemoteRunController.AuthMethod selectedAuthMethod() {
        return passwordAuthRadio.isSelected()
                ? RemoteRunController.AuthMethod.PASSWORD : RemoteRunController.AuthMethod.KEY;
    }

    private void testConnection() {
        connectionStatusLabel.setForeground(new Color(0x1a, 0x73, 0xe8));
        connectionStatusLabel.setText("Connecting\u2026");
        String host = hostField.getText().trim();
        int port = parsePortOrDefault();
        String username = usernameField.getText().trim();
        RemoteRunController.AuthMethod authMethod = selectedAuthMethod();
        String password = new String(passwordField.getPassword());
        String keyPath = keyPathField.getText().trim();
        String keyPassphrase = new String(keyPassphraseField.getPassword());

        new SwingWorker<Void, Void>() {
            private Exception error;

            @Override
            protected Void doInBackground() {
                try {
                    controller.connect(host, port, username, authMethod, password, keyPath, keyPassphrase, 10000);
                    controller.disconnect(); // this call is just a connectivity test, not a persistent session
                } catch (Exception ex) {
                    error = ex;
                }
                return null;
            }

            @Override
            protected void done() {
                if (error != null) {
                    connectionStatusLabel.setForeground(new Color(0xc6, 0x28, 0x28));
                    connectionStatusLabel.setText("Failed: " + error.getMessage());
                } else {
                    connectionStatusLabel.setForeground(new Color(0x2e, 0x7d, 0x32));
                    connectionStatusLabel.setText("Connected OK");
                }
            }
        }.execute();
    }

    private int parsePortOrDefault() {
        try {
            return Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException nfe) {
            return 22;
        }
    }

    // ---- Start/stop ----

    private void startTest() {
        String host = hostField.getText().trim();
        int port = parsePortOrDefault();
        String username = usernameField.getText().trim();
        RemoteRunController.AuthMethod authMethod = selectedAuthMethod();
        String password = new String(passwordField.getPassword());
        String keyPath = keyPathField.getText().trim();
        String keyPassphrase = new String(keyPassphraseField.getPassword());
        String jvmArgs = jvmArgsField.getText();
        String jmeterBinDir = jmeterBinDirField.getText().trim();
        String jmxPath = jmxPathField.getText().trim();
        String outputCsv = outputCsvField.getText().trim();
        String logFile = logFileField.getText().trim();
        String extraArgs = extraArgsField.getText();
        boolean outputFormatXml = xmlFormatRadio.isSelected();
        boolean uploadJmx = uploadJmxFirstCheckbox.isSelected();
        String localJmxPath = org.apache.jmeter.gui.GuiPackage.getInstance().getTestPlanFile();

        if (host.isEmpty() || username.isEmpty() || jmeterBinDir.isEmpty() || jmxPath.isEmpty()
                || outputCsv.isEmpty() || logFile.isEmpty()) {
            runStatusLabel.setForeground(new Color(0xc6, 0x28, 0x28));
            runStatusLabel.setText("Fill in host, username, bin dir, .jmx, output CSV, and log path first");
            return;
        }
        if (uploadJmx && (localJmxPath == null || localJmxPath.trim().isEmpty())) {
            runStatusLabel.setForeground(new Color(0xc6, 0x28, 0x28));
            runStatusLabel.setText("The current test plan hasn't been saved yet \u2014 save it, or "
                    + "uncheck \"Upload the .jmx here\"");
            return;
        }

        startButton.setEnabled(false);
        runStatusLabel.setForeground(new Color(0x1a, 0x73, 0xe8));
        runStatusLabel.setText("Connecting\u2026");
        outputArea.setText("");

        new SwingWorker<Void, Void>() {
            private Exception error;

            @Override
            protected Void doInBackground() {
                try {
                    controller.connect(host, port, username, authMethod, password, keyPath, keyPassphrase, 10000);
                    if (uploadJmx) {
                        controller.uploadFile(new File(localJmxPath), jmxPath);
                    }
                    // JMeter appends to an existing output file rather than
                    // overwriting it - confirmed in JMeter's own docs,
                    // not assumed - so a path reused from an earlier run
                    // (same or different output format) would otherwise
                    // get this run's data appended after stale or
                    // mismatched-format leftovers, corrupting what live
                    // monitoring then tries to parse.
                    controller.deleteRemoteFileIfExists(outputCsv);
                    controller.deleteRemoteFileIfExists(logFile);
                } catch (Exception ex) {
                    error = ex;
                }
                return null;
            }

            @Override
            protected void done() {
                if (error != null) {
                    startButton.setEnabled(true);
                    runStatusLabel.setForeground(new Color(0xc6, 0x28, 0x28));
                    runStatusLabel.setText((uploadJmx ? "Connect/upload failed: " : "Connection failed: ")
                            + error.getMessage());
                    return;
                }
                runStatusLabel.setForeground(new Color(0x2e, 0x7d, 0x32));
                runStatusLabel.setText("Running");
                stopGracefulButton.setEnabled(true);
                stopAbruptButton.setEnabled(true);

                controller.startRemoteTest(jvmArgs, jmeterBinDir, jmxPath, outputCsv, logFile, extraArgs,
                        outputFormatXml,
                        RemoteRunPanel.this::appendOutput,
                        exitCode -> SwingUtilities.invokeLater(() -> onRemoteTestExited(exitCode, outputFormatXml,
                                outputCsv)),
                        ex -> SwingUtilities.invokeLater(() -> onRemoteTestError(ex)));

                if (liveMonitoringCheckbox.isSelected()) {
                    if (outputFormatXml) {
                        appendOutput("[live monitoring] XML mode: the transaction tree will appear "
                                + "once this test finishes, not progressively during it (JMeter's own "
                                + "XML output isn't valid, parseable XML until the run completes and "
                                + "its root element is closed).");
                    } else {
                        startLiveMonitoring(outputCsv);
                    }
                }
            }
        }.execute();
    }

    private void onRemoteTestExited(int exitCode, boolean outputFormatXml, String outputPath) {
        runStatusLabel.setForeground(exitCode == 0 ? new Color(0x2e, 0x7d, 0x32) : new Color(0xc6, 0x28, 0x28));
        runStatusLabel.setText("Finished (exit code " + exitCode + ")");
        startButton.setEnabled(true);
        stopGracefulButton.setEnabled(false);
        stopAbruptButton.setEnabled(false);
        stopLiveMonitoring();

        if (outputFormatXml && liveMonitoringCheckbox.isSelected()) {
            loadFinalXmlResults(outputPath);
        }
    }

    /**
     * The one-time XML load, done only now that the remote process has
     * actually exited and its output file's root element is properly
     * closed - confirmed via JMeter's own API, {@code SaveService.
     * loadTestResults()}, which (unlike the hand-written CSV path) doesn't
     * need any custom parsing: it reconstructs each SampleResult with its
     * sub-results correctly nested and feeds them to a Visualizer itself,
     * through the same ResultCollectorHelper bridge class JMeter's own
     * "Load results from file" feature uses.
     */
    private void loadFinalXmlResults(String remotePath) {
        appendOutput("[live monitoring] Test finished \u2014 downloading and parsing the full result "
                + "tree\u2026");
        File localTempXml;
        try {
            localTempXml = File.createTempFile("insightdashboard-remote-final-", ".xml");
            localTempXml.deleteOnExit();
        } catch (java.io.IOException ioe) {
            appendOutput("[live monitoring] could not create a local temp file: " + ioe.getMessage());
            return;
        }

        new SwingWorker<Void, Void>() {
            private Exception error;

            @Override
            protected Void doInBackground() {
                try {
                    controller.downloadFile(remotePath, localTempXml);
                    try (java.io.InputStream in = new java.io.FileInputStream(localTempXml)) {
                        org.apache.jmeter.save.SaveService.loadTestResults(in,
                                new org.apache.jmeter.reporters.ResultCollectorHelper(
                                        resultCollectorForCsvReplay, dashboardTarget));
                    }
                } catch (Exception ex) {
                    error = ex;
                }
                return null;
            }

            @Override
            protected void done() {
                if (error != null) {
                    appendOutput("[live monitoring] Could not load the final XML results: "
                            + error.getMessage());
                } else {
                    appendOutput("[live monitoring] Full transaction tree loaded.");
                }
            }
        }.execute();
    }

    private void onRemoteTestError(Exception ex) {
        runStatusLabel.setForeground(new Color(0xc6, 0x28, 0x28));
        runStatusLabel.setText("Error: " + ex.getMessage());
        startButton.setEnabled(true);
        stopGracefulButton.setEnabled(false);
        stopAbruptButton.setEnabled(false);
        stopLiveMonitoring();
    }

    private void stopTest(boolean abrupt) {
        String jmeterBinDir = jmeterBinDirField.getText().trim();
        runStatusLabel.setForeground(new Color(0xe6, 0x7e, 0x22));
        runStatusLabel.setText(abrupt ? "Stopping (abrupt)\u2026" : "Stopping (graceful)\u2026");

        new SwingWorker<String, Void>() {
            private Exception error;

            @Override
            protected String doInBackground() {
                try {
                    return controller.stopRemoteTest(jmeterBinDir, abrupt, 10000);
                } catch (Exception ex) {
                    error = ex;
                    return null;
                }
            }

            @Override
            protected void done() {
                if (error != null) {
                    runStatusLabel.setForeground(new Color(0xc6, 0x28, 0x28));
                    runStatusLabel.setText("Stop failed: " + error.getMessage());
                } else {
                    String remoteOutput;
                    try {
                        remoteOutput = get();
                    } catch (Exception ignored) {
                        remoteOutput = "";
                    }
                    appendOutput("[" + (abrupt ? "stoptest.sh" : "shutdown.sh") + "] " + remoteOutput);
                }
            }
        }.execute();
    }

    // ---- Live monitoring ----

    /**
     * Polls the remote CSV on a Swing Timer, downloading the whole file via
     * a background SwingWorker each tick (never on the EDT) and feeding
     * only the rows not already seen into the dashboard via a
     * {@link SkippingVisualizer} wrapper - see that class for why a skip
     * count, not a timestamp comparison, is the de-dup strategy.
     */
    private void startLiveMonitoring(String remoteCsvPath) {
        try {
            localTempCsv = File.createTempFile("insightdashboard-remote-", ".csv");
            localTempCsv.deleteOnExit();
        } catch (java.io.IOException ioe) {
            appendOutput("[live monitoring] could not create a local temp file, disabled: " + ioe.getMessage());
            return;
        }
        SkippingVisualizer skipping = new SkippingVisualizer(dashboardTarget);
        int intervalMs = ((Number) pollIntervalSpinner.getValue()).intValue() * 1000;
        liveMonitorTimer = new Timer(intervalMs, e -> pollOnce(remoteCsvPath, skipping));
        liveMonitorTimer.start();
    }

    private void stopLiveMonitoring() {
        if (liveMonitorTimer != null) {
            liveMonitorTimer.stop();
            liveMonitorTimer = null;
        }
    }

    private void pollOnce(String remoteCsvPath, SkippingVisualizer skipping) {
        if (pollInFlight) {
            return; // the previous poll is still running - never overlap two downloads of the same growing file
        }
        pollInFlight = true;
        new SwingWorker<Integer, Void>() {
            private Exception error;

            @Override
            protected Integer doInBackground() {
                try {
                    controller.downloadFile(remoteCsvPath, localTempCsv);
                    skipping.resetForNewPoll();
                    CSVSaveService.processSamples(localTempCsv.getAbsolutePath(), skipping,
                            resultCollectorForCsvReplay);
                    return skipping.rowsForwardedThisPoll();
                } catch (Exception ex) {
                    error = ex;
                    return 0;
                }
            }

            @Override
            protected void done() {
                pollInFlight = false;
                if (error != null) {
                    appendOutput("[live monitoring] " + error.getMessage());
                }
            }
        }.execute();
    }

    /**
     * Wraps the real dashboard Visualizer, forwarding only the rows past
     * however many were already processed in earlier polls - needed
     * because {@link CSVSaveService#processSamples} re-reads the WHOLE
     * file every call (it has no "resume from offset" option), so without
     * this, every already-seen row would be re-added and double-counted on
     * every single poll. A skip-count, not a timestamp comparison, since
     * samples from different threads completing at nearly the same moment
     * aren't strictly guaranteed to be in perfect timestamp order in the
     * file - counting rows already consumed is exact regardless of that.
     */
    private static final class SkippingVisualizer implements Visualizer {
        private final Visualizer delegate;
        private final AtomicInteger alreadyProcessed = new AtomicInteger(0);
        private int seenThisPoll;

        SkippingVisualizer(Visualizer delegate) {
            this.delegate = delegate;
        }

        void resetForNewPoll() {
            seenThisPoll = 0;
        }

        int rowsForwardedThisPoll() {
            int forwarded = Math.max(0, seenThisPoll - alreadyProcessed.get());
            alreadyProcessed.set(seenThisPoll);
            return forwarded;
        }

        @Override
        public void add(SampleResult sample) {
            seenThisPoll++;
            if (seenThisPoll > alreadyProcessed.get()) {
                delegate.add(sample);
            }
        }

        @Override
        public boolean isStats() {
            return delegate.isStats();
        }
    }
}
