package io.insightdashboard.jmeter;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.datatransfer.StringSelection;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JList;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.ListSelectionModel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.Icon;
import javax.swing.JTree;
import javax.swing.tree.TreePath;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.Scrollable;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JEditorPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.text.JTextComponent;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.reporters.ResultCollectorHelper;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.save.CSVSaveService;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestStateListener;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.visualizers.gui.AbstractVisualizer;

/**
 * A live, in-GUI test dashboard: stat cards, two rolling line charts
 * (response time and throughput, each with an active-thread overlay and an
 * optional SLA threshold line), and a per-label live breakdown table with
 * transaction/sub-request tree expansion, percentile columns, filtering,
 * sorting, SLA breach highlighting, and CSV export.
 *
 * This extends AbstractVisualizer (JMeter's own base class for "listener
 * that renders live sample data"), which already provides: the standard
 * listener title bar, an optional "write results to file" panel, and the
 * Clear / Clear All buttons wired up to clearData().
 *
 * GUI-mode only by nature — there is nothing to paint to in headless (-n)
 * mode, which is expected for a live dashboard.
 *
 * Parent/child (transaction/sub-request) note: JMeter only attaches child
 * SampleResults to a parent when a Transaction Controller has "Generate
 * parent sample" checked. With that on, this listener receives ONE sample
 * for the transaction, and its sub-requests are reachable via
 * SampleResult.getSubResults(). Without it, JMeter sends each request as
 * its own flat, independent sample and there is nothing to nest — the
 * table will simply show no expand arrow for those rows.
 */
public class CustomDashboardVisualizer extends AbstractVisualizer
        implements TestStateListener, javax.swing.Scrollable {

    private static final long serialVersionUID = 1L;

    private static final Color OK_COLOR = new Color(0x2e, 0x7d, 0x32);
    private static final Color ERROR_COLOR = new Color(0xc6, 0x28, 0x28);
    private static final Color BUSY_COLOR = new Color(0xe6, 0x7e, 0x22);
    private static final Color RESP_LINE = new Color(0x1e, 0x88, 0xe5);
    private static final Color TPS_LINE = new Color(0x43, 0xa0, 0x47);
    private static final Color THREAD_LINE = new Color(0x75, 0x75, 0x75);
    private static final Color BREACH_BG_LIGHT = new Color(0xfd, 0xe4, 0xe1);
    private static final Color BREACH_BG_DARK = new Color(0x5c, 0x1f, 0x1f);
    private static final Color STATUS_FAIL_LIGHT = new Color(0xc6, 0x28, 0x28);
    private static final Color STATUS_FAIL_DARK = new Color(0xef, 0x9a, 0x9a);
    private static final Color STATUS_OK_LIGHT = new Color(0x2e, 0x7d, 0x32);
    private static final Color STATUS_OK_DARK = new Color(0xa5, 0xd6, 0xa7);

    private static final String[] SORT_KEYS = {
            "Avg (ms)", "P90 (ms)", "P95 (ms)", "P99 (ms)", "Max (ms)", "Min (ms)",
            "Throughput (req/s)", "Samples", "Errors", "Error %", "Label"
    };

    /**
     * Theme helpers: rather than hardcoding a light or dark palette, colors
     * are derived from whatever Look & Feel JMeter is actually running
     * (including third-party dark themes), so this listener's panels,
     * cards, and hand-painted charts follow it automatically instead of
     * fighting it with hardcoded white/black.
     */
    private static Color uiColor(String key, Color fallback) {
        Color c = javax.swing.UIManager.getColor(key);
        return c != null ? c : fallback;
    }

    private static boolean isDark(Color c) {
        double luminance = (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) / 255.0;
        return luminance < 0.5;
    }

    private static Color blend(Color base, Color mixIn, double amount) {
        int r = clamp((int) Math.round(base.getRed() * (1 - amount) + mixIn.getRed() * amount));
        int g = clamp((int) Math.round(base.getGreen() * (1 - amount) + mixIn.getGreen() * amount));
        int b = clamp((int) Math.round(base.getBlue() * (1 - amount) + mixIn.getBlue() * amount));
        return new Color(r, g, b);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    // ---- Live aggregate state, written from sampler threads inside add() ----
    private final Map<String, LabelStats> statsByLabel = new ConcurrentHashMap<>();
    // parent label -> (child label -> child stats). Only populated for
    // transactions whose sub-results are attached (see class javadoc).
    private final Map<String, Map<String, LabelStats>> childStatsByParent = new ConcurrentHashMap<>();

    // ---- Per-thread-group breakdown, for the Thread Group filter. Parallel
    // to the two maps above (which stay as the "every thread group combined"
    // view), not a replacement for them - same-named samplers from different
    // thread groups get merged into one row in statsByLabel, which is
    // correct for an "overall" view but wrong once a specific thread group
    // is selected, so that case needs its own, separately-tracked numbers
    // rather than being derived from the already-merged global ones. ----
    private static final char TG_KEY_SEP = '\u0000'; // a character that can't appear in a JMeter name
    private final java.util.Set<String> knownThreadGroups = ConcurrentHashMap.newKeySet();
    private final Map<String, LabelStats> statsByThreadGroupAndLabel = new ConcurrentHashMap<>();
    private final Map<String, Map<String, LabelStats>> childStatsByThreadGroupAndParent =
            new ConcurrentHashMap<>();

    private static String tgKey(String threadGroupName, String label) {
        return threadGroupName + TG_KEY_SEP + label;
    }

    /**
     * Prefers the live JMeterContext (precise, handles any thread group
     * naming) when it's actually available; falls back to parsing the
     * sample's own thread name when it's not - confirmed as a real,
     * reported gap: JMeterContextService only resolves on a genuine JMeter
     * sampling thread, which live monitoring's CSV replay never is (it
     * reconstructs samples on a background Swing thread, so the context
     * lookup always returns null there, surfacing as literally the
     * "(unknown thread group)" fallback string itself in the Thread Group
     * filter). JMeter's own standard thread-naming convention embeds the
     * group name directly - "&lt;Thread Group Name&gt; &lt;N&gt;-&lt;M&gt;"
     * - and that name IS present in CSV data (the threadName column),
     * unlike the live context. Verified against realistic and edge-case
     * names before writing this, not assumed.
     */
    private static String deriveThreadGroupName(SampleResult result, AbstractThreadGroup tg) {
        if (tg != null) {
            return tg.getName();
        }
        String threadName = result.getThreadName();
        if (threadName == null || threadName.isEmpty()) {
            return "(unknown thread group)";
        }
        int lastSpace = threadName.lastIndexOf(' ');
        if (lastSpace <= 0) {
            return threadName;
        }
        String suffix = threadName.substring(lastSpace + 1);
        if (suffix.matches("\\d+-\\d+")) {
            return threadName.substring(0, lastSpace);
        }
        return threadName;
    }

    /** Adds newly-discovered thread groups to the dropdown without
     *  disturbing the current selection - a no-op once every thread group
     *  in the plan has produced at least one sample, which happens quickly
     *  after a run starts. */
    private void refreshThreadGroupCombo() {
        if (threadGroupFilterCombo.getItemCount() - 1 == knownThreadGroups.size()) {
            return;
        }
        Object currentSelection = threadGroupFilterCombo.getSelectedItem();
        List<String> sorted = new ArrayList<>(knownThreadGroups);
        Collections.sort(sorted);
        threadGroupFilterCombo.removeAllItems();
        threadGroupFilterCombo.addItem("All thread groups");
        for (String tg : sorted) {
            threadGroupFilterCombo.addItem(tg);
        }
        if (currentSelection != null) {
            threadGroupFilterCombo.setSelectedItem(currentSelection); // no-op if no longer present
        }
    }

    /** Scoped to one thread group's own samples - a parallel, separately-
     *  tracked view, not derived from the already-merged global stats (see
     *  the class-level comment on the per-thread-group fields for why that
     *  distinction matters: two thread groups sharing a label's numbers
     *  can't be un-merged after the fact). O(n) over the whole per-thread-
     *  group map each call; fine at this dashboard's 1-second refresh
     *  cadence for the realistic sizes involved. */
    private static Map<String, LabelStats> filterByThreadGroup(Map<String, LabelStats> source, String tgName) {
        String prefix = tgName + TG_KEY_SEP;
        Map<String, LabelStats> out = new LinkedHashMap<>();
        for (Map.Entry<String, LabelStats> e : source.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                out.put(e.getKey().substring(prefix.length()), e.getValue());
            }
        }
        return out;
    }

    private static Map<String, Map<String, LabelStats>> filterChildrenByThreadGroup(
            Map<String, Map<String, LabelStats>> source, String tgName) {
        String prefix = tgName + TG_KEY_SEP;
        Map<String, Map<String, LabelStats>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, LabelStats>> e : source.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                out.put(e.getKey().substring(prefix.length()), e.getValue());
            }
        }
        return out;
    }
    private final Map<Long, SecondBucket> buckets = new ConcurrentHashMap<>();
    // label -> (second -> bucket), for the charts' per-transaction drill-down view.
    private final Map<String, Map<Long, SecondBucket>> bucketsByLabel = new ConcurrentHashMap<>();
    private final LongAdder totalCount = new LongAdder();
    private final LongAdder totalErrors = new LongAdder();
    private final LongAdder totalTime = new LongAdder();
    private volatile long testStartMillis = System.currentTimeMillis();
    private volatile long lastSampleMillis = testStartMillis;
    // Fallback test-end detection, independent of whether JMeter's own
    // testEnded() callback actually reaches this component (see
    // checkForTestEndViaThreadCount() for why this exists at all).
    private volatile boolean activeThreadsSeenLastTick = false;
    private volatile boolean reportPopulatedForThisEnd = false;
    private volatile Double slaThresholdMs;
    // Which transaction/request the charts and code breakdown are scoped to;
    // null means "overall" (aggregated across everything, the default view).
    private volatile String selectedLabel;
    // fireTableDataChanged() (called every refresh tick) makes JTable clear its
    // selection by default; this suppresses our selection listener's reaction
    // while refreshUi() restores the selection afterward, so a real user click
    // isn't confused with that internal clear-then-restore.
    private boolean suppressSelectionEvents = false;

    // ---- UI, only ever touched on the Swing Event Dispatch Thread ----
    private final JLabel totalSamplesValue = statValue("0");
    private final JLabel errorsValue = statValue("0");
    private final JLabel errorRateValue = statValue("0.00%");
    private final JLabel avgRespValue = statValue("0 ms");
    private final JLabel throughputValue = statValue("0.0 req/s");
    private final ChartPanel responseTimeChart = new ChartPanel("Avg Response Time (ms)", RESP_LINE);
    private final ChartPanel throughputChart = new ChartPanel("Throughput (req/s)", TPS_LINE);
    private final ChartPanel errorRateChart = new ChartPanel("Error Rate (%)", ERROR_COLOR);
    private final CodeBreakdownPanel responseCodeBreakdown = new CodeBreakdownPanel();
    private final Map<String, LongAdder> responseCodeCounts = new ConcurrentHashMap<>();
    private final Map<String, Map<String, LongAdder>> codeCountsByLabel = new ConcurrentHashMap<>();
    private final JLabel chartsContextLabel = new JLabel("Viewing: Overall (click a row in the table to focus)");
    private final JButton showOverallButton = new JButton("Show Overall");
    private final JButton toggleChartsButton = new JButton("\u25bc Hide Charts");
    private JTabbedPane tabs;
    private final LabelTableModel tableModel = new LabelTableModel();
    private final JTable table = buildTreeTable();
    private TableColumn[] allColumns; // captured once in configureTable(), used to show/hide columns
    private final JTextField filterField = new JTextField(14);
    /** "All thread groups" is always index 0; real thread group names are
     *  appended as they're discovered (see refreshThreadGroupCombo()). */
    private final JComboBox<String> threadGroupFilterCombo = new JComboBox<>(new String[] {"All thread groups"});
    private final JTextField minAvgField = new JTextField(5);
    private final JCheckBox errorsOnlyCheckbox = new JCheckBox("Errors only");
    private final JCheckBox notifyOnBreachCheckbox = new JCheckBox("Notify on SLA/error breach");
    private final JCheckBox flagAnomaliesCheckbox = new JCheckBox("Flag anomalies automatically");
    private final JCheckBox askAiForAnomaliesCheckbox = new JCheckBox("Ask AI to explain anomalies");
    /** Separate from alreadyNotifiedLabels (the SLA/error-threshold one): a transaction can
     *  independently breach a user-set threshold AND show a statistical anomaly relative to
     *  its own baseline, and one firing shouldn't suppress the other from ever being noticed. */
    private final java.util.Set<String> alreadyFlaggedAnomalyLabels = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final JEditorPane anomalyLogPane = new WrappingEditorPane();
    private final StringBuilder anomalyLogHtml = new StringBuilder();
    private final JComboBox<String> presetCombo = new JComboBox<>();
    private final JButton savePresetButton = new JButton("Save Preset\u2026");
    private final JButton deletePresetButton = new JButton("Delete Preset");
    private List<DashboardPreset> savedPresets = new ArrayList<>();
    private boolean applyingPreset; // suppresses the combo's own selection listener while we're the ones setting it
    private final JTextField errorRateThresholdField = new JTextField(5);
    /** Labels that have already triggered a desktop notification this run - checked before
     *  notifying again, so an ongoing breach fires once rather than every refresh tick.
     *  Cleared on clearData() so a fresh run can notify again. */
    private final java.util.Set<String> alreadyNotifiedLabels = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private TrayIcon notificationTrayIcon;
    private final JTextField slaField = new JTextField(5);
    private final JComboBox<String> sortByCombo = new JComboBox<>(SORT_KEYS);
    private final JButton sortDirectionButton = new JButton("\u25BC Desc");
    private boolean sortAscending = false;
    private final Timer refreshTimer = new Timer(1000, e -> refreshUi());

    // ---- Results Summary (AI) tab ----
    private static final String DEFAULT_GEMINI_COMMAND = "gemini --skip-trust -p";
    private static final String AI_PROMPT_INSTRUCTION =
            "You are a performance testing expert. Analyze the following JMeter load test "
            + "summary (piped via stdin) and respond with: 1) a one-line overall verdict "
            + "(healthy / concerning / critical), 2) key findings as bullet points, 3) likely "
            + "causes for any errors or slow transactions, 4) concrete, specific recommendations. "
            + "Be concise and reference the actual numbers from the data.";
    // Editable: the dropdown is for convenience, picking a known-compatible option,
    // not a restriction - typing any other command directly still works exactly
    // as it did with the plain text field this replaces.
    private final JComboBox<String> geminiCommandField =
            new JComboBox<>(new String[] {DEFAULT_GEMINI_COMMAND, "claude -p"});
    private final JEditorPane aiSummaryPane = new WrappingEditorPane();
    private final JProgressBar aiProgressBar = buildBusyIndicator();
    private volatile String lastAiResponseMarkdown = "";
    private volatile boolean aiEverAttempted = false;
    // The report's content, unwrapped (no theme CSS/html/body tags yet) -
    // kept in sync every time buildFullReportHtml() runs, so Save Report can
    // re-wrap it for export (always light, properly padded) without needing
    // to parse the already-wrapped, theme-specific HTML string back apart.
    private volatile String lastReportBodyHtml = "";
    // Incremented every time clearData() runs - including JMeter's own automatic
    // clear on pressing Run, which happens regardless of anything in this plugin.
    // An AI request in flight captures this value at start; if it's changed by
    // the time the response arrives, the data it was analyzing no longer exists,
    // and the response gets discarded with a clear explanation instead of being
    // silently applied on top of now-empty sections.
    private volatile long dataGeneration = 0;

    // ---- AI Chat tab ----
    private static final int MAX_JMX_CHARS = 200_000; // guard against sending megabytes of XML per turn
    private static final int MAX_CHAT_HISTORY_TURNS = 3; // last N user/assistant exchanges included as context
    private static final String CHAT_PROMPT_ARGUMENT =
            "Answer the new question at the end, using only the context and conversation provided "
            + "via stdin. Be concise and specific; reference actual data/numbers where relevant. If "
            + "something can't be determined from the given context, say so rather than guessing.";
    private final JEditorPane chatTranscriptPane = new WrappingEditorPane();
    private final JTextArea chatInputArea = new JTextArea(3, 40);
    private final JButton chatSendButton = new JButton("Send");
    private final JButton chatClearButton = new JButton("Clear Chat");
    private final JCheckBox includeJmxCheckbox = new JCheckBox("Include .jmx test plan", true);
    private final JButton loadJtlFileButton = new JButton("Load JTL File\u2026");
    /** Both CSVSaveService.processSamples() and ResultCollectorHelper call
     *  methods directly on this with no null-check (confirmed from their
     *  own source before relying on this, same finding as the Remote Run
     *  tab's equivalent) - a plain, freshly-constructed instance has every
     *  filtering flag off, meaning every sample loads, none dropped. */
    private final ResultCollector resultCollectorForFileLoad = new ResultCollector();
    private final JLabel chatStatusLabel = new JLabel("Idle");
    private final List<AbstractMap.SimpleEntry<String, String>> chatHistory = new ArrayList<>();
    private final JProgressBar chatProgressBar = buildBusyIndicator();
    private volatile boolean chatThinking = false;
    private volatile int chatThinkingDots = 0;
    private final JLabel aiStatusLabel = new JLabel("Idle");
    private final JLabel geminiAvailabilityLabel = new JLabel("Checking\u2026");
    private final JButton generateSummaryButton = new JButton("Enhance Observations with AI");

    // ---- Failures (AI) tab ----
    private static final int MAX_RECENT_FAILURES = 30; // bounded ring buffer — full response bodies
                                                         // aren't worth retaining for every failure on a long run
    private static final int MAX_FAILURE_SNIPPET_CHARS = 20000;
    private static final String FAILURE_PROMPT_INSTRUCTION =
            "You are a performance testing and web/API troubleshooting expert. A JMeter load test "
            + "sampler failed; its request/response detail is piped via stdin. Explain: 1) the most "
            + "likely root cause in plain language, 2) whether this looks like a script/correlation "
            + "problem (e.g. a stale token, hardcoded value, wrong content-type) versus a genuine "
            + "application/environment problem, 3) concrete next steps to confirm the cause and fix "
            + "it. Be concise and specific to the actual data given, not generic advice.";
    private static final String ANOMALY_PROMPT_INSTRUCTION =
            "You are a performance testing expert. A JMeter load test transaction's recent response "
            + "time or error rate has deviated significantly from its own running average for this "
            + "test \u2014 the specific numbers are piped via stdin. Explain in 2-4 sentences: 1) what "
            + "this pattern commonly indicates (e.g. a downstream dependency slowing down, a resource "
            + "limit being approached, garbage collection pauses, a thundering-herd effect from ramp-up) "
            + "2) one or two concrete things to check to confirm the cause. Be concise and specific to "
            + "the actual numbers given, not generic advice.";
    private final DefaultListModel<FailureDetail> failuresListModel = new DefaultListModel<>();
    private final JList<FailureDetail> failuresList = new JList<>(failuresListModel);
    /** Which failure (by its toString(), since FailureDetail has no stable id) the most
     *  recent "Explain Failure" answer was actually for, and its raw markdown - so the bug
     *  report export can tell whether the AI's take it would bundle actually matches the
     *  currently-selected failure, rather than a stale answer left over from a different one. */
    private String lastExplainedFailureKey;
    private String lastExplainedFailureMarkdown;
    private final JTabbedPane failureDetailTabs = new JTabbedPane();
    private final JEditorPane failureGeneralPane = new WrappingEditorPane();
    private final RequestViewerPanel failureRequestViewer = new RequestViewerPanel();
    private final ResponseViewerPanel failureResponseViewer = new ResponseViewerPanel();
    private final JEditorPane failureAiPane = new WrappingEditorPane();
    private final JButton explainFailureButton = new JButton("Explain This Failure (AI)");
    private final JButton clearFailuresButton = new JButton("Clear Failures");
    private final JLabel failureAiStatusLabel = new JLabel("Select a failure on the left");
    private final JProgressBar failureAiProgressBar = buildBusyIndicator();
    private final Deque<FailureDetail> recentFailures = new ConcurrentLinkedDeque<>();

    // ---- Live Dashboard: per-row request/response detail (click a row to view) ----
    // Off by default: capturing getResponseDataAsString() on every successful sample,
    // not just failures, has a real CPU cost per sample that the existing failures-only
    // capture above never paid. Memory itself stays bounded regardless (one entry per
    // distinct label, overwritten on each new sample for that label, not one per sample
    // ever seen), but the per-sample string-conversion cost is the actual reason this
    // stays opt-in on a live, in-progress load test where overhead matters.
    private final JCheckBox captureSampleDetailCheckbox =
            new JCheckBox("Capture request/response detail for every sample (click a row below to view)");
    private final JButton toggleDetailPanelButton = new JButton("\u25B6 Hide Detail Panel");
    private int lastDetailPanelDividerLocation = -1;
    private final ConcurrentHashMap<String, SampleDetail> latestSampleByLabel = new ConcurrentHashMap<>();
    /** Last-seen full JMeterVariables snapshot per thread name, diffed against on each new
     *  sample on that same thread to approximate "what this sample's own extractors just
     *  set" - see SampleDetail.extractedVariables. Bounded by thread count, not sample count. */
    private final ConcurrentHashMap<String, Map<String, String>> lastVariableSnapshotByThread = new ConcurrentHashMap<>();
    /** One rolling list per thread name of its most recent transaction-level samples
     *  (Transaction Controller parent samples, or a top-level request not under one),
     *  used to render one iteration's sequence of transactions end to end - see
     *  recordTimelineEntry() for how a "new iteration" is detected and the list reset. */
    private final ConcurrentHashMap<String, List<TimelineEntry>> currentIterationByThread = new ConcurrentHashMap<>();
    private static final int MAX_TIMELINE_ENTRIES_PER_THREAD = 60;
    private final JTabbedPane sampleDetailTabs = new JTabbedPane();
    private final JEditorPane sampleGeneralPane = new WrappingEditorPane();
    private final RequestViewerPanel sampleRequestViewer = new RequestViewerPanel();
    private final ResponseViewerPanel sampleResponseViewer = new ResponseViewerPanel();
    private final JEditorPane sampleVariablesPane = new WrappingEditorPane();

    /** Creates the TestElement a new Insight Dashboard tree node should hold, without
     *  leaving a live, ticking refresh Timer running on a throwaway GUI instance -
     *  the constructor unconditionally starts one (see its own comment below for why),
     *  which would otherwise leak forever on an instance built only to call
     *  createTestElement() and immediately discard, exactly what adding this plugin to
     *  the tree from a Tools-menu action (rather than JMeter's own drag-and-drop Add
     *  menu) needs to do. */
    static TestElement createStandaloneTestElement() {
        CustomDashboardVisualizer throwaway = new CustomDashboardVisualizer();
        TestElement element = throwaway.createTestElement();
        throwaway.refreshTimer.stop();
        return element;
    }

    public CustomDashboardVisualizer() {
        super();
        init();
        refreshTimer.setRepeats(true);
        // Start immediately rather than waiting for testStarted(): the real
        // runtime object is a ResultCollector wrapping this panel, and while
        // it reliably forwards add() for every sample, it does NOT reliably
        // forward testStarted() to the wrapped visualizer. Relying on that
        // hook left the UI frozen at zero for the whole run. Running the
        // timer continuously is cheap (it only reads a couple of concurrent
        // maps once a second) and matches how JMeter's own built-in graph
        // listeners behave.
        //
        // testEnded() forwarding turned out to have the same problem, found
        // via an actual user report after a test run showed no change here
        // at all (see checkForTestEndViaThreadCount(), added because of
        // this) - corrected from an earlier, untested assumption in this
        // same comment that testEnded() specifically was reliable while only
        // testStarted() wasn't. Both callbacks get a timer-driven fallback
        // now rather than being depended on directly.
        refreshTimer.start();
    }

    private void init() {
        setLayout(new BorderLayout());
        setBorder(makeBorder());
        savedPresets = loadPresetsFromDisk();

        Container titlePanel = makeTitlePanel();
        // makeTitlePanel() embeds the standard "Write results to file /
        // Read from file" section internally - confirmed from JMeter's own
        // Javadoc ("Returns a panel that includes... a FilePanel"), not a
        // separate, optional addition the way an earlier attempt at this
        // assumed. getFilePanel() returns that same, already-embedded
        // component instance (a cached field inside AbstractVisualizer,
        // not a fresh one each call), so hiding it directly here removes
        // it from wherever it sits inside titlePanel, without needing to
        // know or touch that panel's own internal layout. Unused by this
        // plugin regardless (every sample is processed directly in memory
        // via add()) and unrelated to Clear/Clear All, which comes from
        // the separate Clearable interface and this class's own
        // clearData() override.
        getFilePanel().setVisible(false);
        add(titlePanel, BorderLayout.NORTH);

        tabs = new JTabbedPane();
        tabs.addTab("Live Dashboard", buildDashboardTab());
        tabs.addTab("Remote Run", new RemoteRunPanel(this));
        tabs.addTab("Results Summary (AI)", buildAiSummaryTab());
        tabs.addTab("AI Chat", buildAiChatTab());
        tabs.addTab("Failures (AI)", buildFailuresTab());
        tabs.addTab("Anomalies", buildAnomaliesTab());
        tabs.addTab("Help", buildHelpTab());
        // Needed because getScrollableTracksViewportHeight() below now depends on
        // which tab is selected - without this, switching tabs changes what that
        // method WOULD return, but Swing has no way to know to re-ask it, since
        // nothing else here would otherwise trigger a re-layout at that moment.
        tabs.addChangeListener(e -> {
            revalidate();
            repaint();
        });
        add(tabs, BorderLayout.CENTER);
        add(PluginBranding.buildBrandBar(), BorderLayout.SOUTH);
    }

    /**
     * Loads a saved .jtl file from disk, replacing whatever's currently
     * shown. Sniffs the file's actual content (the first non-whitespace
     * character) rather than trusting its extension, since ".jtl" is used
     * for both CSV- and XML-format output and nothing in the filename
     * itself says which. XML goes through SaveService.loadTestResults() -
     * JMeter's own file-loading API, which genuinely preserves sub-results
     * nesting when the file has it - CSV goes through
     * CSVSaveService.processSamples(), which never can, since CSV has
     * nowhere to put a parent/child relationship at all.
     */
    private void loadJtlFile() {
        int confirm = JOptionPane.showConfirmDialog(parentFrame(),
                "This replaces everything currently shown on this dashboard with the selected file's "
                        + "results. Continue?",
                "Load JTL File", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.OK_OPTION) {
            return;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Load JTL File");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File selected = chooser.getSelectedFile();
        clearData(); // must happen before starting the worker below, not after - execute() is async

        new SwingWorker<Boolean, Void>() {
            private Exception error;

            @Override
            protected Boolean doInBackground() {
                boolean isXml = false;
                try {
                    isXml = sniffIsXml(selected);
                    if (isXml) {
                        try (java.io.InputStream in = new java.io.FileInputStream(selected)) {
                            SaveService.loadTestResults(in,
                                    new ResultCollectorHelper(resultCollectorForFileLoad,
                                            CustomDashboardVisualizer.this));
                        }
                    } else {
                        CSVSaveService.processSamples(selected.getAbsolutePath(),
                                CustomDashboardVisualizer.this, resultCollectorForFileLoad);
                    }
                } catch (Exception ex) {
                    error = ex;
                }
                return isXml;
            }

            @Override
            protected void done() {
                if (error != null) {
                    JOptionPane.showMessageDialog(CustomDashboardVisualizer.this,
                            "Could not load that file: " + error.getMessage(),
                            "Load JTL File", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                boolean isXml;
                try {
                    isXml = get();
                } catch (Exception ignored) {
                    isXml = false;
                }
                refreshUi();
                JOptionPane.showMessageDialog(CustomDashboardVisualizer.this,
                        isXml
                                ? "Loaded " + selected.getName() + " \u2014 XML format, so any "
                                        + "sub-request nesting the file actually contains was preserved."
                                : "Loaded " + selected.getName() + " \u2014 CSV format, which cannot "
                                        + "encode parent/child relationships at all, so this loaded as "
                                        + "a flat list. This isn't fixable after the fact; only an "
                                        + "XML-format JTL (saved with \"Generate parent sample\" "
                                        + "enabled) can show a real tree.",
                        "Load JTL File", JOptionPane.INFORMATION_MESSAGE);
            }
        }.execute();
    }

    /** Reads just the first non-whitespace character to tell XML from CSV -
     *  more reliable than trusting the ".jtl" extension, which is used for
     *  both formats. */
    private static boolean sniffIsXml(File file) throws java.io.IOException {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file))) {
            int c;
            while ((c = reader.read()) != -1) {
                if (!Character.isWhitespace(c)) {
                    return c == '<';
                }
            }
        }
        return false;
    }

    private JPanel buildDashboardTab() {
        JPanel dashboard = new JPanel(new BorderLayout(0, 10));
        dashboard.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        dashboard.add(buildStatCardsRow(), BorderLayout.NORTH);

        JPanel charts = new ScrollableChartsPanel(new GridLayout(2, 2, 10, 10));
        charts.add(wrapCard(responseTimeChart));
        charts.add(wrapCard(throughputChart));
        charts.add(wrapCard(errorRateChart));
        charts.add(wrapCard(responseCodeBreakdown));

        JPanel chartsHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        chartsHeader.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        chartsContextLabel.setFont(chartsContextLabel.getFont().deriveFont(Font.ITALIC));
        chartsHeader.add(toggleChartsButton);
        chartsHeader.add(chartsContextLabel);
        chartsHeader.add(showOverallButton);
        showOverallButton.setEnabled(false);
        showOverallButton.addActionListener(e -> {
            table.clearSelection();
            selectedLabel = null;
            refreshUi();
        });

        loadJtlFileButton.setToolTipText("<html>Loads a saved .jtl results file from disk, replacing "
                + "what's currently shown. Whether this can show a real<br>transaction tree depends "
                + "entirely on the file itself: XML-format JTLs (saved with \"Generate parent "
                + "sample\"<br>enabled) genuinely contain that structure; CSV-format JTLs (the far more "
                + "common default) fundamentally<br>cannot encode parent/child relationships at all, "
                + "so those always load as a flat list \u2014 not a limitation<br>of this button, just "
                + "what the file format can hold.</html>");
        loadJtlFileButton.addActionListener(e -> loadJtlFile());
        chartsHeader.add(loadJtlFileButton);

        JPanel chartsSection = new JPanel(new BorderLayout());
        chartsSection.add(chartsHeader, BorderLayout.NORTH);
        JScrollPane chartsScroll = new JScrollPane(charts);
        chartsScroll.setBorder(BorderFactory.createEmptyBorder());
        chartsScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        chartsScroll.getVerticalScrollBar().setUnitIncrement(16);
        chartsSection.add(chartsScroll, BorderLayout.CENTER);

        // The header (with its toggle button, context label, and Show Overall
        // button) always stays visible; only the chart grid's own scroll pane
        // collapses, freeing that space for the table below it. setVisible(false)
        // on a BorderLayout.CENTER component correctly shrinks its container to
        // just the NORTH component's height, with no leftover blank gap - but
        // that only works on chartsScroll itself (the actual CENTER component
        // since the scroll-pane wrapper was added), not on the charts grid
        // nested inside it: toggling the inner panel's visibility alone left
        // the (now-empty) JScrollPane still occupying its own space, which is
        // exactly the "space not reclaimed when collapsed" bug this replaces.
        toggleChartsButton.addActionListener(e -> {
            boolean nowVisible = !chartsScroll.isVisible();
            chartsScroll.setVisible(nowVisible);
            toggleChartsButton.setText(nowVisible ? "\u25bc Hide Charts" : "\u25b6 Show Charts");
            dashboard.revalidate();
            dashboard.repaint();
        });

        JPanel tableContainer = new JPanel(new BorderLayout());
        tableContainer.setBorder(BorderFactory.createTitledBorder("Per-request stats"));
        tableContainer.setToolTipText("<html>Click a transaction row to expand/collapse sub-requests, "
                + "click any row to focus the charts above on it.</html>");

        JPanel[] controlsRows = buildControlsBar();
        JPanel filterSortRow = controlsRows[0];
        JPanel moreOptionsPanel = controlsRows[1];

        // "More Options" collapses Alerts, View & Tools, Saved Views, and the capture-detail
        // checkbox together - all set occasionally and left alone, unlike Filter & Sort which
        // gets touched on every refresh - so the table below has room to breathe by default
        // instead of competing with five-plus rows of configuration controls for space.
        JPanel moreOptionsExpandedContent = new JPanel();
        moreOptionsExpandedContent.setLayout(new BoxLayout(moreOptionsExpandedContent, BoxLayout.Y_AXIS));
        moreOptionsExpandedContent.add(moreOptionsPanel);
        moreOptionsExpandedContent.add(thinSeparator());
        moreOptionsExpandedContent.add(buildPresetsRow());
        moreOptionsExpandedContent.add(thinSeparator());
        JPanel captureRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        captureSampleDetailCheckbox.setToolTipText("<html>When on, the detail panel to the right of the "
                + "table shows the most recent request/response<br>for whichever row is selected \u2014 "
                + "the same information View Results Tree shows, for one sample<br>at a time per row "
                + "rather than every sample. Off by default: reading each response body as text on "
                + "every<br>successful sample, not just failures, has a real per-sample cost worth "
                + "opting into rather than paying always.</html>");
        captureSampleDetailCheckbox.addActionListener(e -> refreshSampleDetailPanel());
        captureRow.add(captureSampleDetailCheckbox);
        moreOptionsExpandedContent.add(captureRow);
        moreOptionsExpandedContent.setVisible(false); // collapsed by default

        JButton moreOptionsToggle = new JButton("\u25B6 More Options (Alerts, View & Tools, Saved Views)");
        moreOptionsToggle.setToolTipText("Alert thresholds, column visibility, export, run tools, "
                + "saved view presets, and request/response capture \u2014 all set occasionally rather "
                + "than every refresh, so they're tucked away here by default.");
        moreOptionsToggle.addActionListener(e -> {
            boolean nowVisible = !moreOptionsExpandedContent.isVisible();
            moreOptionsExpandedContent.setVisible(nowVisible);
            moreOptionsToggle.setText(nowVisible
                    ? "\u25BC Hide More Options"
                    : "\u25B6 More Options (Alerts, View & Tools, Saved Views)");
            tableContainer.revalidate();
            tableContainer.repaint();
        });
        // Moved here from the detail panel's own header (far to the right) after being
        // reported as hard to click reliably - alongside More Options at the top, in
        // immediate view, rather than requiring the eye to travel across the whole width
        // of the screen to find it.
        toggleDetailPanelButton.setFont(toggleDetailPanelButton.getFont().deriveFont(Font.BOLD));
        toggleDetailPanelButton.setToolTipText("Same as dragging the divider between the table and the "
                + "detail panel all the way \u2014 just one click either way.");
        JPanel toggleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        toggleRow.add(moreOptionsToggle);
        toggleRow.add(toggleDetailPanelButton);

        JPanel controlsHeader = new JPanel();
        controlsHeader.setLayout(new BoxLayout(controlsHeader, BoxLayout.Y_AXIS));
        controlsHeader.add(filterSortRow);
        controlsHeader.add(toggleRow);
        controlsHeader.add(moreOptionsExpandedContent);
        tableContainer.add(controlsHeader, BorderLayout.NORTH);

        JScrollPane generalScroll = buildFailureSubPane(sampleGeneralPane);
        JScrollPane variablesScroll = buildFailureSubPane(sampleVariablesPane);
        sampleDetailTabs.addTab("Sampler Result", generalScroll);
        sampleDetailTabs.addTab("Request", sampleRequestViewer);
        sampleDetailTabs.addTab("Response Data", sampleResponseViewer);
        sampleDetailTabs.addTab("Extracted Variables", variablesScroll);
        JPanel detailPanel = new JPanel(new BorderLayout());
        detailPanel.setBorder(BorderFactory.createTitledBorder("Selected row \u2014 most recent sample"));
        JPanel detailHeaderRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        JButton findDynamicValuesButton = new JButton("Find Dynamic Values\u2026");
        findDynamicValuesButton.setToolTipText("<html>Scans the currently-shown sample's response body and "
                + "headers for values that commonly need<br>correlating in a load-test script \u2014 known "
                + "token-like names, UUID-shaped strings, and long random-looking ones.</html>");
        findDynamicValuesButton.addActionListener(e -> {
            SampleDetail detail = selectedLabel != null ? latestSampleByLabel.get(selectedLabel) : null;
            if (detail == null) {
                JOptionPane.showMessageDialog(parentFrame(), "Select a row with a captured sample first.",
                        "Nothing to Scan", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            new DynamicValuesDialog((Frame) SwingUtilities.getWindowAncestor(this), detail.label,
                    detail.responseSnippet, detail.responseHeaders, detail.requestHeaders, detail.requestData)
                    .setVisible(true);
        });
        detailHeaderRow.add(findDynamicValuesButton);
        detailPanel.add(detailHeaderRow, BorderLayout.NORTH);
        detailPanel.add(sampleDetailTabs, BorderLayout.CENTER);
        detailPanel.setMinimumSize(new Dimension(0, 0));
        refreshSampleDetailPanel();

        JSplitPane tableAndDetail = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, configureTable(), detailPanel);
        tableAndDetail.setResizeWeight(0.68);
        // No one-touch-expand arrow here - reported directly as small and hard to click
        // reliably, and the Hide/Show Detail Panel button (in the top controls row) already
        // does the same thing without that problem, so there's no need for a second,
        // harder-to-use control for the same action.
        tableAndDetail.setDividerSize(14); // default (~8px) is too thin to reliably grab or even notice
        tableContainer.add(tableAndDetail, BorderLayout.CENTER);
        // setResizeWeight() alone only governs how *extra* space is distributed on resize,
        // not the initial split - without this, the divider's starting position often
        // defaults to the table's own preferred width instead, squeezing the detail panel
        // into an unreadably thin sliver. Deferred, since setDividerLocation(double) called
        // before this component has its actual on-screen size is a well-known case where
        // it's silently ignored.
        SwingUtilities.invokeLater(() -> tableAndDetail.setDividerLocation(0.68));

        toggleDetailPanelButton.addActionListener(e -> {
            boolean currentlyHidden = "\u25C0 Show Detail Panel".equals(toggleDetailPanelButton.getText());
            if (currentlyHidden) {
                if (lastDetailPanelDividerLocation >= 0) {
                    tableAndDetail.setDividerLocation(lastDetailPanelDividerLocation); // absolute pixels
                } else {
                    tableAndDetail.setDividerLocation(0.68); // proportional default, matching resizeWeight
                }
            } else {
                lastDetailPanelDividerLocation = tableAndDetail.getDividerLocation();
                tableAndDetail.setDividerLocation(1.0);
            }
        });
        // Keeps the button's label correct regardless of how the divider actually moved -
        // this button or manual dragging - rather than only reacting to this button's own
        // clicks.
        tableAndDetail.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            int maxLocation = tableAndDetail.getMaximumDividerLocation();
            boolean nowHidden = tableAndDetail.getDividerLocation() >= maxLocation - 4; // small slop
            toggleDetailPanelButton.setText(nowHidden ? "\u25C0 Show Detail Panel" : "\u25B6 Hide Detail Panel");
        });

        JPanel center = new JPanel(new BorderLayout(0, 10));
        center.add(chartsSection, BorderLayout.NORTH);
        center.add(tableContainer, BorderLayout.CENTER);

        dashboard.add(center, BorderLayout.CENTER);
        return dashboard;
    }

    /**
     * Built as a field initializer (see the `table` field) so we can override
     * prepareRenderer to tint an entire row red when it breaches the SLA
     * threshold, without needing a per-column renderer for every column.
     */
    private JTable buildTreeTable() {
        return new JTable(tableModel) {
            private static final long serialVersionUID = 1L;

            @Override
            public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
                Component c = super.prepareRenderer(renderer, row, column);
                Row meta = tableModel.getRowAt(row);
                boolean breach = meta != null && slaThresholdMs != null && meta.p95 > slaThresholdMs;
                if (!isRowSelected(row)) {
                    Color base = getBackground();
                    Color breachColor = isDark(base) ? BREACH_BG_DARK : BREACH_BG_LIGHT;
                    c.setBackground(breach ? breachColor : base);
                }
                return c;
            }

            @Override
            public String getToolTipText(MouseEvent e) {
                int viewRow = rowAtPoint(e.getPoint());
                int viewCol = columnAtPoint(e.getPoint());
                if (viewRow < 0 || viewCol != 0) {
                    return super.getToolTipText(e);
                }
                Row meta = tableModel.getRowAt(viewRow);
                if (meta == null) {
                    return super.getToolTipText(e);
                }
                // Wrapped in a fixed-width <html> block so a very long path doesn't
                // produce a single, screen-spanning tooltip line.
                return "<html><body style='width: 500px'>" + escapeHtmlStatic(meta.label) + "</body></html>";
            }
        };
    }

    private JPanel buildAiChatTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        topBar.add(includeJmxCheckbox);
        topBar.add(chatStatusLabel);
        topBar.add(chatProgressBar);
        topBar.add(chatClearButton);

        chatTranscriptPane.setContentType("text/html");
        chatTranscriptPane.setEditable(false);
        chatTranscriptPane.setBackground(uiColor("Panel.background", Color.WHITE));

        chatInputArea.setLineWrap(true);
        chatInputArea.setWrapStyleWord(true);
        // Plain Enter sends the message; Shift+Enter still inserts a newline,
        // by re-binding it to JTextArea's own default "insert-break" action.
        chatInputArea.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "send");
        chatInputArea.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("shift ENTER"), "insert-break");
        chatInputArea.getActionMap().put("send", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                sendChatMessage();
            }
        });

        JPanel inputBar = new JPanel(new BorderLayout(6, 0));
        inputBar.add(new JScrollPane(chatInputArea), BorderLayout.CENTER);
        inputBar.add(chatSendButton, BorderLayout.EAST);

        // JTabbedPane sizes its tab strip to fit the tallest tab (Live Dashboard, with
        // its charts and table). Anchoring this block at CENTER (not NORTH) lets the
        // transcript stretch to fill whatever space is actually available and shrink
        // gracefully on a shorter tab area, while the input bar stays pinned to SOUTH —
        // always rendered at the true bottom of the panel, never clipped regardless of
        // how much space JTabbedPane allocates here.
        JScrollPane transcriptScroll = new JScrollPane(chatTranscriptPane);
        installMouseWheelScrolling(chatTranscriptPane, transcriptScroll);

        JPanel chatBlock = new JPanel(new BorderLayout(0, 8));
        chatBlock.add(topBar, BorderLayout.NORTH);
        chatBlock.add(transcriptScroll, BorderLayout.CENTER);
        chatBlock.add(inputBar, BorderLayout.SOUTH);

        panel.add(chatBlock, BorderLayout.CENTER);

        chatSendButton.addActionListener(e -> sendChatMessage());
        chatClearButton.addActionListener(e -> {
            chatHistory.clear();
            chatStatusLabel.setForeground(null);
            chatStatusLabel.setText("Idle");
            renderChatTranscript();
        });

        renderChatTranscript();
        return panel;
    }

    private void sendChatMessage() {
        String question = chatInputArea.getText().trim();
        if (question.isEmpty()) {
            return;
        }
        String commandLine = currentAiCommand().trim();
        if (commandLine.isEmpty()) {
            chatStatusLabel.setText("Set a Gemini CLI command in the Results Summary tab first");
            return;
        }

        chatInputArea.setText("");
        chatHistory.add(new AbstractMap.SimpleEntry<>("user", question));

        String stdinText = buildChatPromptText(question);
        chatSendButton.setEnabled(false);
        chatInputArea.setEnabled(false);
        chatStatusLabel.setForeground(BUSY_COLOR);
        chatStatusLabel.setFont(chatStatusLabel.getFont().deriveFont(Font.BOLD));
        chatProgressBar.setVisible(true);
        chatThinking = true;
        chatThinkingDots = 0;
        renderChatTranscript();

        long startMillis = System.currentTimeMillis();
        Timer thinkingTimer = new Timer(500, null);
        thinkingTimer.addActionListener(e -> {
            chatThinkingDots = (chatThinkingDots + 1) % 4;
            chatStatusLabel.setText("Thinking\u2026 (" + (System.currentTimeMillis() - startMillis) / 1000 + "s)");
            renderChatTranscript();
        });
        thinkingTimer.start();

        new SwingWorker<String, Void>() {
            private Exception error;

            @Override
            protected String doInBackground() {
                try {
                    return invokeGeminiCli(commandLine, stdinText, CHAT_PROMPT_ARGUMENT);
                } catch (Exception ex) {
                    error = ex;
                    return null;
                }
            }

            @Override
            protected void done() {
                thinkingTimer.stop();
                chatThinking = false;
                chatProgressBar.setVisible(false);
                chatStatusLabel.setFont(chatStatusLabel.getFont().deriveFont(Font.PLAIN));
                chatSendButton.setEnabled(true);
                chatInputArea.setEnabled(true);
                if (error != null) {
                    chatStatusLabel.setForeground(ERROR_COLOR);
                    chatStatusLabel.setText("Error");
                    String msg = error.getMessage() == null ? "(no details)" : error.getMessage();
                    chatHistory.add(new AbstractMap.SimpleEntry<>("ai",
                            "**Failed to get a response:**\n" + msg));
                } else {
                    chatStatusLabel.setForeground(null);
                    chatStatusLabel.setText("Idle");
                    String response;
                    try {
                        response = stripCliNoise(get());
                    } catch (Exception ignored) {
                        response = "(no response)";
                    }
                    chatHistory.add(new AbstractMap.SimpleEntry<>("ai", response));
                }
                renderChatTranscript();
            }
        }.execute();
    }

    /**
     * Builds the stdin content for one chat turn: instructions, the current
     * .jmx test plan (if the checkbox is on and the plan has been saved to a
     * file), the same results-summary report used for Enhance Observations
     * with AI,
     * a bounded window of prior conversation turns, and the new question.
     * Each turn is a fresh, self-contained subprocess call (matching
     * Gemini CLI's one-shot headless design) rather than a persistent
     * session, so earlier turns are re-sent as text context each time.
     */
    private String buildChatPromptText(String question) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a helpful assistant answering questions about a JMeter performance test. "
                + "Use ONLY the context below (the test plan, if included, and the current results "
                + "summary) and the conversation so far. If something can't be determined from this "
                + "data, say so plainly rather than guessing.\n\n");

        if (includeJmxCheckbox.isSelected()) {
            String jmx = readCurrentJmx();
            sb.append("=== Test Plan (JMX) ===\n");
            sb.append(jmx.isEmpty() ? "(not available \u2014 test plan hasn't been saved to a file yet)\n" : jmx + "\n");
            sb.append('\n');
        }

        sb.append("=== Results Summary ===\n").append(buildReportText()).append('\n');

        if (chatHistory.size() > 1) {
            int from = Math.max(0, chatHistory.size() - 1 - MAX_CHAT_HISTORY_TURNS * 2);
            sb.append("=== Conversation so far ===\n");
            for (int i = from; i < chatHistory.size() - 1; i++) {
                AbstractMap.SimpleEntry<String, String> m = chatHistory.get(i);
                sb.append("user".equals(m.getKey()) ? "User: " : "Assistant: ").append(m.getValue()).append('\n');
            }
            sb.append('\n');
        }

        sb.append("=== New question ===\n").append(question);
        return sb.toString();
    }

    /** Reads the currently loaded/saved .jmx file's raw XML, truncated if very large. */
    private String readCurrentJmx() {
        try {
            String path = GuiPackage.getInstance().getTestPlanFile();
            if (path == null || path.trim().isEmpty()) {
                return "";
            }
            String content = new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
            if (content.length() > MAX_JMX_CHARS) {
                content = content.substring(0, MAX_JMX_CHARS)
                        + "\n... [truncated, " + (content.length() - MAX_JMX_CHARS) + " more characters]";
            }
            return content;
        } catch (Exception e) {
            return "(could not read the test plan file: " + e.getMessage() + ")";
        }
    }

    private void renderChatTranscript() {
        StringBuilder body = new StringBuilder();
        body.append("<h2>AI Chat</h2>\n");
        body.append("<p class=\"meta\">Ask follow-up questions about this test \u2014 uses the test "
                + "plan (if checked above) and the current results summary as context. Uses the "
                + "Gemini CLI command configured in the Results Summary tab. Each message is sent as "
                + "a fresh request (Gemini CLI's headless mode is one-shot, not a persistent session), "
                + "so recent conversation history is re-sent as text context each turn \u2014 responses "
                + "may take a little longer as the conversation grows.</p>\n");
        body.append("<p class=\"meta\"><b>Unlike Results Summary, this tab has no data-only "
                + "fallback</b> \u2014 a conversation genuinely needs the AI to respond to. If Gemini "
                + "CLI isn't installed or logged in yet, sending a message will show an error here "
                + "(with whatever your system reported) rather than a reply. See the Results Summary "
                + "(AI) tab for setup steps.</p>\n");
        if (chatHistory.isEmpty() && !chatThinking) {
            body.append("<p><i>Try: \u201cWhy might T01_Token_POST be slow given my test plan?\u201d or "
                    + "\u201cWhich transactions are breaching my SLA?\u201d</i></p>\n");
        }
        for (AbstractMap.SimpleEntry<String, String> m : chatHistory) {
            boolean isUser = "user".equals(m.getKey());
            String rendered = isUser
                    ? "<p>" + escapeHtmlStatic(m.getValue()).replace("\n", "<br/>") + "</p>"
                    : MarkdownLite.toHtml(m.getValue());
            body.append("<div class=\"").append(isUser ? "chat-user" : "chat-ai").append("\"><b>")
                    .append(isUser ? "You" : "Gemini").append(":</b>").append(rendered).append("</div>\n");
        }
        if (chatThinking) {
            String dots = ".".repeat(1 + chatThinkingDots);
            body.append("<div class=\"chat-thinking\"><b>Gemini:</b> <i>thinking").append(dots).append("</i></div>\n");
        }
        chatTranscriptPane.setText(wrapHtmlDocument(body.toString()));
        chatTranscriptPane.setCaretPosition(chatTranscriptPane.getDocument().getLength());
    }

    private JPanel buildAnomaliesTab() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel intro = new JLabel("<html><body style='width: 800px'>"
                + "Transactions flagged because their recent response time or error rate deviated "
                + "significantly from their own running average for this test \u2014 not against a "
                + "threshold you set (that's \u201cNotify on SLA/error breach\u201d on the Live "
                + "Dashboard tab), but against the test's own baseline so far. Enable \u201cFlag "
                + "anomalies automatically\u201d there to start detecting; optionally also enable "
                + "\u201cAsk AI to explain anomalies\u201d for a short explanation per finding.</body></html>");

        anomalyLogPane.setContentType("text/html");
        anomalyLogPane.setEditable(false);
        anomalyLogPane.setBackground(uiColor("Panel.background", Color.WHITE));
        resetAnomalyLog();

        JScrollPane logScroll = new JScrollPane(anomalyLogPane);
        installMouseWheelScrolling(anomalyLogPane, logScroll);

        JButton clearLogButton = new JButton("Clear Log");
        clearLogButton.addActionListener(e -> resetAnomalyLog());
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        toolbar.add(clearLogButton);

        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.add(intro, BorderLayout.NORTH);
        top.add(toolbar, BorderLayout.SOUTH);
        panel.add(top, BorderLayout.NORTH);
        panel.add(logScroll, BorderLayout.CENTER);
        return panel;
    }

    private void resetAnomalyLog() {
        anomalyLogHtml.setLength(0);
        anomalyLogPane.setText(wrapHtmlDocument("<p><i>No anomalies flagged yet.</i></p>"));
    }

    private JPanel buildFailuresTab() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel introLabel = new JLabel("<html><body style='width: 750px'>"
                + "Shows the most recent failed samples (up to " + MAX_RECENT_FAILURES + ", most recent "
                + "first), including request/response detail — from either a live run or a loaded "
                + ".jtl. Select one and click <b>Explain This Failure</b> to ask Gemini what likely "
                + "went wrong and how to fix it. Uses the same command as Results Summary (AI); "
                + "configure it there first if you haven't already.</body></html>");
        panel.add(introLabel, BorderLayout.NORTH);

        failuresList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        failuresList.setFont(failuresList.getFont().deriveFont(12f));
        JScrollPane listScroll = new JScrollPane(failuresList);
        listScroll.setPreferredSize(new Dimension(300, 400));
        listScroll.setBorder(BorderFactory.createTitledBorder("Recent failures"));

        JScrollPane generalScroll = buildFailureSubPane(failureGeneralPane);
        failureDetailTabs.addTab("General", generalScroll);
        failureDetailTabs.addTab("Request", failureRequestViewer);
        failureDetailTabs.addTab("Response", failureResponseViewer);
        resetFailureDetailPanes();

        failureAiPane.setContentType("text/html");
        failureAiPane.setEditable(false);
        failureAiPane.setBackground(uiColor("Panel.background", Color.WHITE));
        failureAiPane.setText(wrapHtmlDocument("<p><i>Select a failure and click \"Explain This "
                + "Failure\" to see Gemini's analysis here.</i></p>"));
        JScrollPane aiScroll = new JScrollPane(failureAiPane);
        installMouseWheelScrolling(failureAiPane, aiScroll);
        aiScroll.setBorder(BorderFactory.createTitledBorder("AI explanation"));

        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        actionRow.add(explainFailureButton);
        actionRow.add(clearFailuresButton);
        actionRow.add(failureAiProgressBar);
        actionRow.add(failureAiStatusLabel);
        explainFailureButton.setEnabled(false);

        JSplitPane detailAndAi = new JSplitPane(JSplitPane.VERTICAL_SPLIT, failureDetailTabs, aiScroll);
        detailAndAi.setResizeWeight(0.4);

        JPanel rightPanel = new JPanel(new BorderLayout(0, 6));
        rightPanel.add(actionRow, BorderLayout.NORTH);
        rightPanel.add(detailAndAi, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, rightPanel);
        split.setResizeWeight(0.28);
        panel.add(split, BorderLayout.CENTER);

        failuresList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }
            FailureDetail selected = failuresList.getSelectedValue();
            explainFailureButton.setEnabled(selected != null);
            if (selected != null) {
                failureGeneralPane.setText(wrapHtmlDocument(buildFailureGeneralHtml(selected)));
                failureGeneralPane.setCaretPosition(0);
                failureRequestViewer.setContent("", selected.requestData);
                failureResponseViewer.setContent("", selected.responseSnippet);
                failureAiPane.setText(wrapHtmlDocument("<p><i>Click \"Explain This Failure\" to see "
                        + "Gemini's analysis of this one.</i></p>"));
            }
        });
        explainFailureButton.addActionListener(e -> runExplainFailure());
        clearFailuresButton.addActionListener(e -> {
            recentFailures.clear();
            failuresListModel.clear();
            resetFailureDetailPanes();
            failureAiPane.setText(wrapHtmlDocument("<p><i>Select a failure and click \"Explain This "
                    + "Failure\" to see Gemini's analysis here.</i></p>"));
            explainFailureButton.setEnabled(false);
            failureAiStatusLabel.setForeground(null);
            failureAiStatusLabel.setText("Select a failure on the left");
        });
        failuresList.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowFailurePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowFailurePopup(e);
            }

            private void maybeShowFailurePopup(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                int index = failuresList.locationToIndex(e.getPoint());
                if (index < 0) {
                    return;
                }
                failuresList.setSelectedIndex(index);
                FailureDetail selected = failuresList.getSelectedValue();
                if (selected == null) {
                    return;
                }
                JPopupMenu menu = new JPopupMenu();
                JMenuItem goToItem = new JMenuItem("Go to \"" + selected.label + "\" in Test Plan");
                goToItem.addActionListener(a -> goToInTestPlan(selected.label));
                menu.add(goToItem);
                JMenuItem exportBugReportItem = new JMenuItem("Export as Bug Report (copy to clipboard)");
                exportBugReportItem.addActionListener(a -> exportFailureAsBugReport(selected));
                menu.add(exportBugReportItem);
                JMenuItem findDynamicValuesItem = new JMenuItem("Find Dynamic Values\u2026");
                findDynamicValuesItem.addActionListener(a -> new DynamicValuesDialog(
                        (Frame) SwingUtilities.getWindowAncestor(CustomDashboardVisualizer.this), selected.label,
                        selected.responseSnippet, null, null, selected.requestData).setVisible(true));
                menu.add(findDynamicValuesItem);
                menu.show(failuresList, e.getX(), e.getY());
            }
        });

        return panel;
    }

    /** Common setup for each of the three (General/Request/Response) sub-tab panes —
     *  same content type, background, and reliable mouse-wheel scrolling as everywhere
     *  else AI-generated HTML is shown in this dashboard. */
    /** For the Request/Response Data panes specifically: a plain JTextArea rather than
     *  HTML, after two HTML/CSS-based wrapping fixes both failed to actually take effect.
     *  Swing's HTMLEditorKit is a quite old, limited HTML renderer, and its support for
     *  white-space: pre-wrap on a &lt;pre&gt; element appears unreliable regardless of what
     *  CSS is set - a plain JTextArea's own line-wrapping is simple, well-tested, and
     *  doesn't depend on any HTML/CSS rendering at all. setWrapStyleWord(false) is
     *  deliberate, not an oversight: it wraps even mid-token when there's no earlier space
     *  to break at, which is exactly the case for a long, spaceless value like a session
     *  cookie that would otherwise just run off the edge. */
    /** Headers text with any cookie-related lines pulled out into their own field, for
     *  display in a dedicated Cookies sub-tab rather than mixed in with every other
     *  header. */
    private static final class HeaderSplit {
        final String headersOnly;
        final String cookiesOnly;

        HeaderSplit(String headersOnly, String cookiesOnly) {
            this.headersOnly = headersOnly;
            this.cookiesOnly = cookiesOnly;
        }
    }

    /** Separates cookie-related header lines from the rest. Response headers carry
     *  cookies as one or more "Set-Cookie: ..." lines, one cookie per line already;
     *  request headers carry every cookie joined into a single "Cookie: a=1; b=2; c=3"
     *  line, split here on ";" into one row per cookie, since one long semicolon-joined
     *  line is hard to actually scan. */
    private static HeaderSplit splitCookiesFromHeaders(String headersText, boolean isRequest) {
        if (headersText == null || headersText.isEmpty()) {
            return new HeaderSplit("", "");
        }
        StringBuilder headersOnly = new StringBuilder();
        StringBuilder cookiesOnly = new StringBuilder();
        for (String line : headersText.split("\n", -1)) {
            String trimmed = line.trim();
            if (isRequest && trimmed.regionMatches(true, 0, "cookie:", 0, 7)) {
                for (String cookiePair : trimmed.substring(7).trim().split(";")) {
                    String pair = cookiePair.trim();
                    if (!pair.isEmpty()) {
                        cookiesOnly.append(pair).append('\n');
                    }
                }
            } else if (!isRequest && trimmed.regionMatches(true, 0, "set-cookie:", 0, 11)) {
                cookiesOnly.append(trimmed.substring(11).trim()).append('\n');
            } else if (!trimmed.isEmpty()) {
                headersOnly.append(line).append('\n');
            }
        }
        return new HeaderSplit(headersOnly.toString().trim(), cookiesOnly.toString().trim());
    }

    /** A small Find Next/Find Previous bar wired to whatever text component
     *  targetSupplier currently resolves to - a Supplier rather than a fixed target, so
     *  the same bar can search whichever of two components (Raw/Pretty text vs. the
     *  rendered HTML view, say) is actually visible at the moment a search runs, for a
     *  tab where which one that is can change at any time. Works identically for a
     *  JTextArea or a JEditorPane, since both are JTextComponent - case-insensitive,
     *  wraps around at either end, and selecting the match is what actually scrolls it
     *  into view, the same way a normal text editor's own Ctrl+F does this. */
    private static JPanel buildFindBar(java.util.function.Supplier<JTextComponent> targetSupplier) {
        JTextField searchField = new JTextField(16);
        JButton findNextButton = new JButton("Find Next");
        JButton findPrevButton = new JButton("Find Previous");
        JLabel statusLabel = new JLabel(" ");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 11f));

        Runnable findNext = () -> {
            JTextComponent target = targetSupplier.get();
            String query = searchField.getText();
            if (query.isEmpty() || target == null) {
                statusLabel.setText(" ");
                return;
            }
            String lowerText = target.getText().toLowerCase();
            String lowerQuery = query.toLowerCase();
            int idx = lowerText.indexOf(lowerQuery, target.getSelectionEnd());
            boolean wrapped = false;
            if (idx < 0) {
                idx = lowerText.indexOf(lowerQuery);
                wrapped = true;
            }
            if (idx < 0) {
                statusLabel.setText("Not found");
                return;
            }
            target.requestFocusInWindow();
            target.select(idx, idx + query.length());
            statusLabel.setText(wrapped ? "Found (wrapped to top)" : " ");
        };
        Runnable findPrev = () -> {
            JTextComponent target = targetSupplier.get();
            String query = searchField.getText();
            if (query.isEmpty() || target == null) {
                statusLabel.setText(" ");
                return;
            }
            String lowerText = target.getText().toLowerCase();
            String lowerQuery = query.toLowerCase();
            int from = target.getSelectionStart() - 1;
            int idx = from >= 0 ? lowerText.lastIndexOf(lowerQuery, from) : -1;
            boolean wrapped = false;
            if (idx < 0) {
                idx = lowerText.lastIndexOf(lowerQuery);
                wrapped = true;
            }
            if (idx < 0) {
                statusLabel.setText("Not found");
                return;
            }
            target.requestFocusInWindow();
            target.select(idx, idx + query.length());
            statusLabel.setText(wrapped ? "Found (wrapped to bottom)" : " ");
        };
        findNextButton.addActionListener(e -> findNext.run());
        findPrevButton.addActionListener(e -> findPrev.run());
        searchField.addActionListener(e -> findNext.run()); // Enter = find next
        // Typing a different query should re-search from the top, not from wherever the
        // previous query's match happened to land.
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                resetSelection();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                resetSelection();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                resetSelection();
            }

            private void resetSelection() {
                JTextComponent target = targetSupplier.get();
                if (target != null) {
                    target.select(0, 0);
                }
                statusLabel.setText(" ");
            }
        });

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        bar.add(new JLabel("Find:"));
        bar.add(searchField);
        bar.add(findNextButton);
        bar.add(findPrevButton);
        bar.add(statusLabel);
        return bar;
    }

    /** A plain-text sub-pane with a find bar above it - this exact combination (search,
     *  then a scrollable read-only text area) repeats across several Request/Response
     *  Headers and Cookies sub-tabs. */
    private static JPanel buildSearchablePlainTextSubPane(JTextArea pane) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(buildFindBar(() -> pane), BorderLayout.NORTH);
        JPanel contentWrapper = new JPanel(new BorderLayout());
        contentWrapper.add(buildPlainTextSubPane(pane), BorderLayout.NORTH);
        wrapper.add(contentWrapper, BorderLayout.CENTER);
        return wrapper;
    }

    private static JScrollPane buildPlainTextSubPane(JTextArea pane) {
        pane.setEditable(false);
        pane.setLineWrap(true);
        pane.setWrapStyleWord(false);
        pane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        pane.setRows(16); // a bounded, modest default height, not however tall its content or container happen to be
        Color panelBg = uiColor("Panel.background", Color.WHITE);
        pane.setBackground(panelBg);
        pane.setForeground(isDark(panelBg) ? new Color(0xe6, 0xe6, 0xe6) : new Color(0x22, 0x22, 0x22));
        JScrollPane scroll = new JScrollPane(pane);
        installMouseWheelScrolling(pane, scroll);
        return scroll;
    }

    private JScrollPane buildFailureSubPane(JEditorPane pane) {
        pane.setContentType("text/html");
        pane.setEditable(false);
        pane.setBackground(uiColor("Panel.background", Color.WHITE));
        JScrollPane scroll = new JScrollPane(pane);
        installMouseWheelScrolling(pane, scroll);
        return scroll;
    }

    private void resetFailureDetailPanes() {
        failureGeneralPane.setText(wrapHtmlDocument("<p><i>Select a failure on the left.</i></p>"));
        String plainPlaceholder = "Select a failure on the left.";
        failureRequestViewer.setPlaceholder(plainPlaceholder);
        failureResponseViewer.setContent("", plainPlaceholder);
    }

    /** A compact, two-column key/value table - requested directly for the Sampler Result
     *  tab, replacing a loose stack of "<b>Label:</b> value<br/>" lines with properly
     *  aligned rows at a smaller, dedicated font (set via the kv-table CSS class in
     *  wrapHtmlDocument()) than the shared document's normal body text size. Each row is
     *  a {label, already-HTML-safe value} pair - callers are responsible for escaping
     *  their own values first, the same way the two callers below already did before this
     *  was extracted into a shared helper. */
    private static String buildKeyValueTable(String title, String... labelValuePairs) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='kv-title'>").append(title).append("</div>");
        sb.append("<table class='kv-table' cellspacing='0' cellpadding='0'>");
        for (int i = 0; i + 1 < labelValuePairs.length; i += 2) {
            sb.append("<tr><td class='kv-label'>").append(labelValuePairs[i])
                    .append("</td><td class='kv-value'>").append(labelValuePairs[i + 1]).append("</td></tr>");
        }
        sb.append("</table>");
        return sb.toString();
    }

    private String buildFailureGeneralHtml(FailureDetail f) {
        return buildKeyValueTable(escapeHtmlStatic(f.label),
                "Time:", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(f.timestampMillis)),
                "URL:", escapeHtmlStatic(f.url),
                "Response code:", escapeHtmlStatic(f.responseCode),
                "Response message:", escapeHtmlStatic(f.responseMessage));
    }

    private String buildSampleGeneralHtml(SampleDetail s) {
        List<String> pairs = new ArrayList<>();
        pairs.add("Status:");
        pairs.add(s.successful
                ? "<span style='color:#2e7d32;'>Success</span>"
                : "<span style='color:#c62828;'>Failed</span>");
        pairs.add("Time:");
        pairs.add(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(s.timestampMillis)));
        if (!s.httpMethod.isEmpty()) {
            pairs.add("Method:");
            pairs.add(escapeHtmlStatic(s.httpMethod));
        }
        String protocol = s.url.startsWith("https") ? "HTTPS" : s.url.startsWith("http") ? "HTTP" : "";
        if (!protocol.isEmpty()) {
            pairs.add("Protocol:");
            pairs.add(protocol);
        }
        pairs.add("URL:");
        pairs.add(escapeHtmlStatic(s.url));
        pairs.add("Response code:");
        pairs.add(escapeHtmlStatic(s.responseCode));
        pairs.add("Response message:");
        pairs.add(escapeHtmlStatic(s.responseMessage));
        return buildKeyValueTable(escapeHtmlStatic(s.label), pairs.toArray(new String[0]));
    }

    private String buildSampleVariablesHtml(SampleDetail s) {
        if (s.extractedVariables.isEmpty()) {
            return "<p><i>No variables appear to have been newly set or changed by this sample. "
                    + "This compares this thread's variables against its own previous sample (or, for "
                    + "the very first sample on a thread, against having none at all), so it's an "
                    + "approximation \u2014 not a direct attribution to a specific extractor.</i></p>";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<p><i>Variables new or changed since this thread's previous sample, or - for the "
                + "very first sample on this thread - every variable already set by then (an "
                + "approximation of what this sample's own extractors just set):</i></p>");
        sb.append("<table border='1' cellpadding='4' cellspacing='0'>");
        sb.append("<tr><th>Variable</th><th>Value</th></tr>");
        for (Map.Entry<String, String> e : s.extractedVariables.entrySet()) {
            sb.append("<tr><td>${").append(escapeHtmlStatic(e.getKey())).append("}</td><td>")
                    .append(escapeHtmlStatic(e.getValue())).append("</td></tr>");
        }
        sb.append("</table>");
        return sb.toString();
    }

    /** Refreshes the Live Dashboard's own right-hand detail panel from whatever
     *  is currently captured for selectedLabel — called whenever the table
     *  selection changes, and whenever a fresh sample for the CURRENTLY selected
     *  label arrives (so the panel updates live rather than only on the next click). */
    private void refreshSampleDetailPanel() {
        SampleDetail detail = selectedLabel != null ? latestSampleByLabel.get(selectedLabel) : null;
        if (detail == null) {
            String plainMessage = selectedLabel == null
                    ? "Select a row above to view its most recent sample."
                    : captureSampleDetailCheckbox.isSelected()
                        ? "No sample captured yet for this row since the checkbox above was "
                          + "enabled, or since the last Clear Data."
                        : "Enable \u201cCapture request/response detail\u201d above, then re-run "
                          + "(or wait for the next sample) to see detail here.";
            sampleGeneralPane.setText(wrapHtmlDocument("<p><i>" + escapeHtmlStatic(plainMessage) + "</i></p>"));
            sampleRequestViewer.setPlaceholder(plainMessage);
            sampleResponseViewer.setContent("", plainMessage);
            sampleVariablesPane.setText(wrapHtmlDocument("<p><i>" + escapeHtmlStatic(plainMessage) + "</i></p>"));
            return;
        }
        sampleGeneralPane.setText(wrapHtmlDocument(buildSampleGeneralHtml(detail)));
        sampleGeneralPane.setCaretPosition(0);
        sampleRequestViewer.setContent(detail.requestHeaders, detail.requestData);
        sampleResponseViewer.setContent(detail.responseHeaders, detail.responseSnippet);
        sampleVariablesPane.setText(wrapHtmlDocument(buildSampleVariablesHtml(detail)));
        sampleVariablesPane.setCaretPosition(0);
    }

    /** Plain-text version of the same detail, piped to Gemini via stdin — same
     *  structure as the HTML rendering above, without the markup. */
    private String buildFailurePromptText(FailureDetail f) {
        StringBuilder sb = new StringBuilder();
        sb.append("Failed sampler: ").append(f.label).append('\n');
        sb.append("Time: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(f.timestampMillis))).append('\n');
        sb.append("URL: ").append(f.url).append('\n');
        sb.append("Response code: ").append(f.responseCode).append('\n');
        sb.append("Response message: ").append(f.responseMessage).append('\n');
        if (!f.requestData.isEmpty()) {
            sb.append("\nRequest:\n").append(f.requestData).append('\n');
        }
        sb.append("\nResponse body:\n").append(f.responseSnippet.isEmpty() ? "(empty)" : f.responseSnippet).append('\n');
        return sb.toString();
    }

    /** Copies a plain-text bug report for one failure to the clipboard, ready to paste
     *  directly into Jira or similar - the same request/response detail already captured
     *  for the Failures tab, plus the AI's take from "Explain Failure" if one exists AND
     *  actually corresponds to this exact failure (checked via lastExplainedFailureKey,
     *  since clicking a different failure after explaining one would otherwise bundle a
     *  stale, mismatched answer). */
    private void exportFailureAsBugReport(FailureDetail f) {
        StringBuilder sb = new StringBuilder();
        sb.append("Bug Report: ").append(f.label).append('\n');
        sb.append("=".repeat(Math.min(80, 12 + f.label.length()))).append("\n\n");
        sb.append("Time: ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(f.timestampMillis)))
                .append('\n');
        sb.append("URL: ").append(f.url).append('\n');
        sb.append("Response code: ").append(f.responseCode).append('\n');
        sb.append("Response message: ").append(f.responseMessage).append("\n\n");
        if (!f.requestData.isEmpty()) {
            sb.append("--- Request ---\n").append(f.requestData).append("\n\n");
        }
        sb.append("--- Response Body ---\n")
                .append(f.responseSnippet.isEmpty() ? "(empty)" : f.responseSnippet).append("\n\n");
        if (f.toString().equals(lastExplainedFailureKey) && lastExplainedFailureMarkdown != null
                && !lastExplainedFailureMarkdown.isEmpty()) {
            sb.append("--- AI Analysis ---\n").append(lastExplainedFailureMarkdown).append('\n');
        } else {
            sb.append("--- AI Analysis ---\n(Run \"Explain Failure\" on this failure first to include "
                    + "the AI's analysis here.)\n");
        }

        java.awt.datatransfer.StringSelection sel = new java.awt.datatransfer.StringSelection(sb.toString());
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
        JOptionPane.showMessageDialog(parentFrame(), "Bug report copied to clipboard \u2014 ready to paste into Jira.",
                "Copied", JOptionPane.INFORMATION_MESSAGE);
    }

    private void runExplainFailure() {
        FailureDetail selected = failuresList.getSelectedValue();
        if (selected == null) {
            return;
        }
        String commandLine = currentAiCommand().trim();
        if (commandLine.isEmpty()) {
            failureAiStatusLabel.setText("Enter a command on the Results Summary (AI) tab first (e.g. \""
                    + DEFAULT_GEMINI_COMMAND + "\")");
            return;
        }
        String promptText = buildFailurePromptText(selected);

        explainFailureButton.setEnabled(false);
        failureAiStatusLabel.setForeground(BUSY_COLOR);
        failureAiStatusLabel.setFont(failureAiStatusLabel.getFont().deriveFont(Font.BOLD));
        failureAiProgressBar.setVisible(true);
        failureAiPane.setText(wrapHtmlDocument("<p><i>Thinking\u2026</i></p>"));

        long startMillis = System.currentTimeMillis();
        Timer elapsedTimer = new Timer(500, null);
        elapsedTimer.addActionListener(e -> failureAiStatusLabel.setText(
                "Thinking\u2026 (" + (System.currentTimeMillis() - startMillis) / 1000 + "s)"));
        elapsedTimer.start();

        new SwingWorker<String, Void>() {
            private Exception error;

            @Override
            protected String doInBackground() {
                try {
                    return invokeGeminiCli(commandLine, promptText, FAILURE_PROMPT_INSTRUCTION);
                } catch (Exception ex) {
                    error = ex;
                    return null;
                }
            }

            @Override
            protected void done() {
                elapsedTimer.stop();
                failureAiProgressBar.setVisible(false);
                failureAiStatusLabel.setFont(failureAiStatusLabel.getFont().deriveFont(Font.PLAIN));
                explainFailureButton.setEnabled(true);
                if (error != null) {
                    failureAiStatusLabel.setForeground(ERROR_COLOR);
                    failureAiStatusLabel.setText("AI unavailable: " + error.getMessage());
                    failureAiPane.setText(wrapHtmlDocument("<p><i>Could not reach Gemini CLI: "
                            + escapeHtmlStatic(error.getMessage()) + "</i></p>"));
                    return;
                }
                String markdown;
                try {
                    markdown = get();
                } catch (Exception ignored) {
                    markdown = "";
                }
                failureAiStatusLabel.setForeground(OK_COLOR);
                failureAiStatusLabel.setText("Done");
                failureAiPane.setText(wrapHtmlDocument(MarkdownLite.toHtml(markdown)));
                failureAiPane.setCaretPosition(0);
                lastExplainedFailureKey = selected.toString();
                lastExplainedFailureMarkdown = markdown;
            }
        }.execute();
    }

    private JPanel buildHelpTab() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JEditorPane helpPane = new WrappingEditorPane();
        helpPane.setContentType("text/html");
        helpPane.setEditable(false);
        helpPane.setBackground(uiColor("Panel.background", Color.WHITE));
        helpPane.setText(wrapHtmlDocument(HELP_TAB_BODY_HTML));

        JScrollPane helpScroll = new JScrollPane(helpPane);
        installMouseWheelScrolling(helpPane, helpScroll);
        panel.add(helpScroll, BorderLayout.CENTER);
        return panel;
    }

    private static final String HELP_TAB_BODY_HTML =
            "<h1>Viewing Dashboard Results Outside a Local GUI Run</h1>"
            + "<p>Insight Dashboard's tabs are Swing GUI components \u2014 they can't render during a "
            + "headless (<code>-n</code>) run, since there's no window to draw to. This covers the "
            + "two situations where you still want this view: <b>after</b> a headless run has "
            + "finished, and <b>live</b>, while your load generators are remote machines.</p>"

            + "<h2>Part 1 \u2014 Viewing results after a Non-GUI (-n) run</h2>"
            + "<h3>1. Run headless, saving results in XML format</h3>"
            + "<pre>jmeter -n -t test.jmx -l results.jtl -Jjmeter.save.saveservice.output_format=xml</pre>"
            + "<div class=\"note\"><b>Why XML specifically:</b> the default <code>.jtl</code> format "
            + "is flat CSV, which can't encode \"this row is a sub-request of that row\" \u2014 that "
            + "nesting only exists in memory during a live run. XML format preserves it as nested "
            + "<code>&lt;sample&gt;</code> elements, so the transaction/sub-request tree reconstructs "
            + "correctly on reload. If you only need aggregate stats, plain CSV (no flag) is fine.</div>"
            + "<h3>2. Open the same test.jmx in the JMeter GUI</h3>"
            + "<p>Just open it \u2014 no need to press Run again.</p>"
            + "<h3>3. Select \"SmartBlitz - Insight Dashboard (AI)\" and load the file</h3>"
            + "<p>In its \u201cWrite results to file / Read from file\u201d section, click "
            + "<b>Browse...</b> and select <code>results.jtl</code>. JMeter loads and replays it "
            + "through the same pipeline a live run uses.</p>"
            + "<h3>4. Generate the report as usual</h3>"
            + "<p>Switch to <b>Results Summary (AI)</b> \u2014 sections 1\u20134 populate "
            + "automatically once the file loads, same as after a live run. Click "
            + "<b>Enhance Observations with AI</b> for the written analysis.</p>"

            + "<h2>Part 2 \u2014 Live view with remote Linux load generators (Distributed Testing)</h2>"
            + "<p><b>Yes, this works</b> \u2014 and it isn't specific to this plugin. JMeter's "
            + "built-in distributed/remote testing mode is designed exactly for this: your local "
            + "GUI acts purely as a controller and result collector, while the actual sampling "
            + "happens on remote engines. Samples stream back over RMI and populate every listener "
            + "in your local GUI \u2014 including Insight Dashboard \u2014 live, the same as a "
            + "local run.</p>"

            + "<h3>One-time setup \u2014 on each Linux load generator</h3>"
            + "<ol>"
            + "<li>Install the <b>same JMeter version</b> as your local controller \u2014 version "
            + "mismatch is the most common cause of failures.</li>"
            + "<li>Copy any files your test plan depends on (CSV Data Set Config files, etc.) to "
            + "the <b>same relative paths</b> as on your controller \u2014 only the .jmx itself gets "
            + "pushed automatically when you start remotely.</li>"
            + "<li>Start the server process and leave it running:<pre>cd apache-jmeter-5.6.3/bin\n"
            + "./jmeter-server</pre></li>"
            + "</ol>"

            + "<h3>One-time setup \u2014 on your local controller</h3>"
            + "<ol>"
            + "<li>Edit <code>bin/jmeter.properties</code> (or <code>user.properties</code>):"
            + "<pre>remote_hosts=&lt;linux-ip-1&gt;,&lt;linux-ip-2&gt;</pre></li>"
            + "<li>Since JMeter 4.0, remote testing uses RMI over SSL by default, needing a shared "
            + "keystore. Generate it once:<pre>create-rmi-keystore.bat</pre>then copy the resulting "
            + "<code>bin/rmi_keystore.jks</code> to the <code>bin/</code> folder of every worker "
            + "machine (and keep it on the controller too).</li>"
            + "</ol>"

            + "<h3>Running it</h3>"
            + "<ol>"
            + "<li>Launch JMeter normally \u2014 GUI mode, not <code>-n</code>: <code>jmeter.bat</code></li>"
            + "<li>Open your test plan with Insight Dashboard added as a listener, as usual.</li>"
            + "<li><b>Run menu \u2192 Remote Start All</b> (or <b>Remote Start \u2192 one host</b> "
            + "first, to confirm connectivity before committing to all of them).</li>"
            + "<li>Watch the dashboard update live as samples stream back.</li>"
            + "<li><b>Run menu \u2192 Remote Stop All</b> when done, or let it finish naturally.</li>"
            + "</ol>"

            + "<h3>Troubleshooting</h3>"
            + "<ul>"
            + "<li><b>RMI/SSL certificate errors</b> \u2014 for a trusted internal network, add "
            + "<code>server.rmi.ssl.disable=true</code> to <code>jmeter.properties</code> on "
            + "<b>both</b> the controller and every worker, then restart everything. This disables "
            + "encryption in transit, so only do this on networks you trust.</li>"
            + "<li><b>Firewall blocking the connection</b> \u2014 RMI needs two-way connectivity: "
            + "controller \u2192 worker (default port 1099, fix with "
            + "<code>server.rmi.localport=4000</code> on the worker) <b>and</b> worker \u2192 "
            + "controller callback (fix with <code>client.rmi.localport=5000</code> on the "
            + "controller), so your network team can open two exact ports instead of a wide "
            + "ephemeral range.</li>"
            + "<li><b>Version mismatch</b> \u2014 keep the JMeter version identical everywhere.</li>"
            + "</ul>"

            + "<h3>Worth knowing before you rely on this</h3>"
            + "<ul>"
            + "<li><b>Thread Group settings apply to each remote engine independently.</b> Two "
            + "workers configured for 100 threads each means 200 total virtual users, not 100 "
            + "split between them.</li>"
            + "<li><b>Remote/GUI mode uses more controller-side resources</b> than a pure "
            + "<code>-n</code> run, since the controller is now also receiving, aggregating, and "
            + "rendering every sample live. For very high aggregate throughput, watch the "
            + "controller's CPU \u2014 if it becomes the bottleneck, fall back to Part 1 instead "
            + "(workers write results locally, pull the files afterward) rather than streaming "
            + "everything live.</li>"
            + "</ul>";

    private static final String AI_SUMMARY_PLACEHOLDER_HTML =
            "<h1>JMeter Performance Test Report</h1>"
            + "<p>Run a test to see this fill in automatically \u2014 test overview, slowest "
            + "transactions/requests, failures, error codes, and SLA breaches, all computed "
            + "directly from the data.</p>"
            + "<p>One section, Observations &amp; Analysis, is AI-written instead: click "
            + "\u201cEnhance Observations with AI\u201d once data is showing. Entirely optional and "
            + "safe either way \u2014 everything else works with or without Gemini CLI set up, and "
            + "this does send your test data to Google's API over the network when you use it.</p>";

    private JPanel buildAiSummaryTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        toolbar.add(new JLabel("AI CLI command:"));
        geminiCommandField.setEditable(true);
        geminiCommandField.setPreferredSize(new java.awt.Dimension(160, geminiCommandField.getPreferredSize().height));
        geminiCommandField.setToolTipText("<html>Any CLI that reads context from stdin and prints a "
                + "result to stdout works here, not just Gemini.<br>Claude Code: tested and confirmed "
                + "working \u2014 use <code>claude -p</code>. One caveat: a documented Claude Code issue "
                + "reports empty output<br>(no error, just nothing back) on stdin over roughly 7,000 "
                + "characters, which a test with many transactions can exceed \u2014<br>worth a quick try "
                + "on a small test first if you switch to it.</html>");
        toolbar.add(geminiCommandField);
        JButton recheckAvailabilityButton = new JButton("Re-check");
        recheckAvailabilityButton.setToolTipText("Checks whether the command above can actually be "
                + "found \u2014 not whether it's logged in, just whether the executable exists. Runs "
                + "automatically when this tab first opens; click this again after installing Gemini "
                + "CLI or changing the command, rather than restarting JMeter.");
        toolbar.add(geminiAvailabilityLabel);
        toolbar.add(recheckAvailabilityButton);
        toolbar.add(generateSummaryButton);
        toolbar.add(aiStatusLabel);
        toolbar.add(aiProgressBar);
        JButton copyButton = new JButton("Copy AI Text");
        JButton saveButton = new JButton("Save Report as HTML\u2026");
        JButton reloadDataButton = new JButton("Reload Data");
        reloadDataButton.setToolTipText("<html>Re-populates sections 1\u20134 from the current test data "
                + "right now, without waiting for a test to finish.<br>Also resets section 5 to \u201cnot "
                + "yet generated,\u201d since any existing AI analysis was based on<br>whatever data was "
                + "showing before \u2014 click \u201cEnhance Observations with AI\u201d again afterward if "
                + "you want it refreshed too.</html>");
        JButton clearReportButton = new JButton("Clear Observations");
        toolbar.add(copyButton);
        toolbar.add(saveButton);
        toolbar.add(reloadDataButton);
        toolbar.add(clearReportButton);

        aiSummaryPane.setContentType("text/html");
        aiSummaryPane.setEditable(false);
        aiSummaryPane.setBackground(uiColor("Panel.background", Color.WHITE));
        aiSummaryPane.setText(wrapHtmlDocument(AI_SUMMARY_PLACEHOLDER_HTML));

        JPanel center = new JPanel(new BorderLayout());
        center.add(toolbar, BorderLayout.NORTH);
        JScrollPane summaryScroll = new JScrollPane(aiSummaryPane);
        installMouseWheelScrolling(aiSummaryPane, summaryScroll);
        center.add(summaryScroll, BorderLayout.CENTER);
        panel.add(center, BorderLayout.CENTER);

        generateSummaryButton.addActionListener(e -> runAiSummary());
        copyButton.addActionListener(e -> {
            java.awt.datatransfer.StringSelection sel =
                    new java.awt.datatransfer.StringSelection(lastAiResponseMarkdown);
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
        });
        saveButton.addActionListener(e -> saveReportAsHtml());
        clearReportButton.addActionListener(e -> {
            lastAiResponseMarkdown = "";
            aiEverAttempted = false;
            aiStatusLabel.setForeground(null);
            if (totalCount.sum() > 0) {
                // Real data already exists (a test has run) - only the AI
                // observations get cleared back to "not yet generated".
                // Showing the generic pre-test placeholder instead would
                // wipe out real, current sections 1-4 for no reason, and
                // the placeholder's own text ("click X once a test
                // finishes") would be actively wrong at this point.
                aiStatusLabel.setText("Observations cleared \u2014 data sections unaffected");
                aiSummaryPane.setText(buildFullReportHtml("", null));
            } else {
                aiStatusLabel.setText("Idle");
                aiSummaryPane.setText(wrapHtmlDocument(AI_SUMMARY_PLACEHOLDER_HTML));
            }
            aiSummaryPane.setCaretPosition(0);
        });
        reloadDataButton.addActionListener(e -> {
            if (totalCount.sum() > 0) {
                populateDataOnlyReportAfterTestEnd();
            } else {
                JOptionPane.showMessageDialog(parentFrame(), "No test data to reload yet \u2014 run a test "
                        + "first.", "Nothing to reload", JOptionPane.WARNING_MESSAGE);
            }
        });
        recheckAvailabilityButton.addActionListener(e -> checkGeminiAvailabilityAsync());
        geminiCommandField.addActionListener(e -> checkGeminiAvailabilityAsync());

        // Proactive, automatic check as soon as this tab is built - before the
        // user has clicked anything - rather than only finding out reactively
        // when a Generate/Send/Explain button is clicked.
        checkGeminiAvailabilityAsync();

        return panel;
    }

    /**
     * Runs isCommandAvailable() in the background and updates
     * geminiAvailabilityLabel with the result - non-blocking, so it never
     * delays tab construction, and safe to call repeatedly (e.g. from the
     * Re-check button) since each call is independent.
     */
    private void checkGeminiAvailabilityAsync() {
        geminiAvailabilityLabel.setForeground(null);
        geminiAvailabilityLabel.setText("Checking\u2026");
        String commandLine = currentAiCommand();
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() {
                return isCommandAvailable(commandLine);
            }

            @Override
            protected void done() {
                boolean available;
                try {
                    available = get();
                } catch (Exception ignored) {
                    available = false;
                }
                if (available) {
                    geminiAvailabilityLabel.setForeground(OK_COLOR);
                    geminiAvailabilityLabel.setText("\u2713 Found on PATH");
                    geminiAvailabilityLabel.setToolTipText("The command's executable was found. This "
                            + "doesn't confirm it's logged in \u2014 that's only checked when you "
                            + "actually generate a summary, send a chat message, or explain a failure.");
                } else {
                    geminiAvailabilityLabel.setForeground(ERROR_COLOR);
                    geminiAvailabilityLabel.setText("\u26a0 Not found \u2014 AI features unavailable");
                    geminiAvailabilityLabel.setToolTipText("Could not find this command on your PATH. "
                            + "Install Gemini CLI, or correct the command/path above, then click "
                            + "Re-check. Everything else in Insight Dashboard works fine without this.");
                }
            }
        }.execute();
    }

    private void runAiSummary() {
        String commandLine = currentAiCommand().trim();
        if (commandLine.isEmpty()) {
            aiStatusLabel.setText("Enter a command first (e.g. \"" + DEFAULT_GEMINI_COMMAND + "\")");
            return;
        }
        String reportText = buildReportText();
        // Captured now, alongside reportText, so the tables shown next to the AI's
        // response always describe the exact same data it actually analyzed - not
        // whatever live data happens to exist whenever the (possibly slow) AI call
        // finishes, which could have moved on by then even without a full test re-run.
        DataSectionsSnapshot dataSectionsAtStart = buildDataSectionsHtml();
        long generationAtStart = dataGeneration;
        generateSummaryButton.setEnabled(false);
        aiStatusLabel.setForeground(BUSY_COLOR);
        aiStatusLabel.setFont(aiStatusLabel.getFont().deriveFont(Font.BOLD));
        aiProgressBar.setVisible(true);

        long startMillis = System.currentTimeMillis();
        Timer elapsedTimer = new Timer(500, null);
        elapsedTimer.addActionListener(e ->
                aiStatusLabel.setText("Thinking\u2026 (" + (System.currentTimeMillis() - startMillis) / 1000 + "s)"));
        elapsedTimer.start();

        new SwingWorker<String, Void>() {
            private Exception error;

            @Override
            protected String doInBackground() {
                try {
                    return invokeGeminiCli(commandLine, reportText, AI_PROMPT_INSTRUCTION);
                } catch (Exception ex) {
                    error = ex;
                    return null;
                }
            }

            @Override
            protected void done() {
                elapsedTimer.stop();
                aiProgressBar.setVisible(false);
                aiStatusLabel.setFont(aiStatusLabel.getFont().deriveFont(Font.PLAIN));
                generateSummaryButton.setEnabled(true);

                if (dataGeneration != generationAtStart) {
                    // The data this request analyzed no longer exists - most likely
                    // because Run was pressed again while this was in flight (JMeter
                    // clears every listener's data automatically on Run, independent
                    // of anything here). Showing the response anyway would pair AI
                    // analysis of the OLD data with empty/different sections above
                    // it - worse than just explaining what happened and letting a
                    // fresh click analyze the current data instead.
                    aiStatusLabel.setForeground(BUSY_COLOR);
                    aiStatusLabel.setText("Discarded \u2014 test was re-run while waiting");
                    aiSummaryPane.setText(buildFullReportHtml("", null));
                    aiSummaryPane.setCaretPosition(0);
                    return;
                }

                aiEverAttempted = true;
                if (error != null) {
                    aiStatusLabel.setForeground(ERROR_COLOR);
                    aiStatusLabel.setText("AI unavailable (showing data-only report)");
                    lastAiResponseMarkdown = "";
                } else {
                    try {
                        lastAiResponseMarkdown = stripCliNoise(get());
                        aiStatusLabel.setForeground(OK_COLOR);
                        aiStatusLabel.setText("Done");
                    } catch (Exception ignored) {
                        // get() can't actually throw here since error would be non-null instead
                        lastAiResponseMarkdown = "";
                    }
                }
                aiSummaryPane.setText(buildFullReportHtml(dataSectionsAtStart, lastAiResponseMarkdown, error));
                aiSummaryPane.setCaretPosition(0);
            }
        }.execute();
    }

    /** Builds an HTML section explaining an AI failure, reusing the same
     *  diagnosis logic (trusted-directory vs generic) as before, so the
     *  formal report still renders even when Gemini CLI itself failed. */
    private String buildAiErrorSectionHtml(Exception error) {
        String msg = error.getMessage() == null ? "" : error.getMessage();
        String escaped = msg.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        StringBuilder sb = new StringBuilder();
        sb.append("<h3>AI Analysis Unavailable</h3>\n");
        sb.append("<p><b>Failed to run Gemini CLI:</b></p>\n<pre>").append(escaped).append("</pre>\n");
        if (msg.toLowerCase().contains("trusted directory") || msg.contains("GEMINI_CLI_TRUST_WORKSPACE")) {
            sb.append("<p>Gemini CLI refuses headless runs in a directory it hasn't been told to trust "
                    + "(a safety guard against a hostile file in an unfamiliar folder tricking it into taking "
                    + "actions there). JMeter runs this from its own \u201cbin\u201d folder, which has never "
                    + "been trusted interactively.</p>\n");
            sb.append("<p><b>Fix:</b> add <code>--skip-trust</code> to the command field (already in the "
                    + "default <code>" + DEFAULT_GEMINI_COMMAND + "</code> \u2014 if you removed it, put it "
                    + "back). This is safe for what this tab does: it only ever sends plain analysis text and "
                    + "reads back plain text, never asking Gemini to read/write files or run tools in that "
                    + "directory.</p>\n");
        } else {
            sb.append("<p>Check that:</p>\n<ul>"
                    + "<li>\"gemini\" is actually on your PATH \u2014 open a NEW terminal and confirm "
                    + "\"gemini --version\" works there</li>"
                    + "<li>Or give a full path in the command field instead of relying on PATH</li>"
                    + "<li>You've run \"gemini\" once interactively already, to complete login/auth</li>"
                    + "<li>The command syntax matches your installed version \u2014 some versions use a "
                    + "positional prompt instead of \"-p\"</li>"
                    + "</ul>\n");
        }
        return sb.toString();
    }

    /** Removes known Gemini CLI startup/terminal chatter that redirectErrorStream(true) otherwise
     *  captures into the response text (e.g. color-support and ripgrep-availability notices). */
    private static String stripCliNoise(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\n", -1)) {
            String lower = line.toLowerCase();
            if (lower.contains("256-color support") || lower.contains("ripgrep is not available")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * Runs the Gemini CLI as a subprocess: writes the report as stdin, reads
     * all of stdout+stderr (merged), and enforces a hard timeout on the
     * WHOLE operation via Future.get(timeout) — not just after reading, since
     * a plain readLine() loop blocks indefinitely if the process hangs (e.g.
     * waiting on an interactive login prompt), which a waitFor() called only
     * after the read loop would not actually catch.
     */
    /**
     * Quick, lightweight check for whether the configured command's executable
     * can actually be found at all \u2014 does NOT confirm it's logged in or
     * working, just that the binary exists. Mirrors invokeGeminiCli's own
     * cmd.exe-routing approach on Windows (reusing a known-working pattern
     * rather than reimplementing PATH/PATHEXT search logic from scratch, which
     * is easy to get subtly wrong, especially for Windows' .cmd/.bat shims).
     * Typically resolves in well under a second, unlike actually invoking the
     * CLI (which can take up to 90s to time out if it hangs on a login
     * prompt) \u2014 safe to run proactively, before the user clicks anything.
     */
    private static boolean isCommandAvailable(String commandLine) {
        String trimmed = commandLine == null ? "" : commandLine.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        String executableName = trimmed.split("\\s+")[0];
        try {
            List<String> checkCommand = new ArrayList<>();
            String osName = System.getProperty("os.name", "").toLowerCase();
            if (osName.contains("win")) {
                checkCommand.add("cmd.exe");
                checkCommand.add("/c");
                checkCommand.add("where");
                checkCommand.add(executableName);
            } else {
                checkCommand.add("sh");
                checkCommand.add("-c");
                checkCommand.add("command -v " + executableName);
            }
            Process process = new ProcessBuilder(checkCommand).redirectErrorStream(true).start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private static String invokeGeminiCli(String commandLine, String stdinText, String promptArgument) throws Exception {
        List<String> userTokens = new ArrayList<>(Arrays.asList(commandLine.trim().split("\\s+")));
        userTokens.add(promptArgument);

        List<String> command = new ArrayList<>();
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("win")) {
            // Most CLIs installed via npm (gemini included) are a `gemini.cmd`
            // shim on Windows, not a raw .exe. Typing "gemini" in cmd/PowerShell
            // resolves that automatically via PATHEXT; Java's ProcessBuilder
            // calls CreateProcess directly and does NOT do that resolution,
            // which is exactly what produces "CreateProcess error=2, The system
            // cannot find the file specified" even though the same command
            // works fine typed into a terminal. Routing through cmd.exe /c
            // makes cmd do that resolution for us, same as it does interactively.
            command.add("cmd.exe");
            command.add("/c");
            command.addAll(userTokens);
        } else {
            command.addAll(userTokens);
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> future = executor.submit(() -> {
                try (OutputStream stdin = process.getOutputStream()) {
                    stdin.write(stdinText.getBytes(StandardCharsets.UTF_8));
                }
                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append('\n');
                    }
                }
                int exit = process.waitFor();
                if (exit != 0) {
                    throw new IOException("Exited with code " + exit + ". Output:\n" + output);
                }
                return output.toString().trim();
            });

            try {
                return future.get(90, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                process.destroyForcibly();
                throw new IOException("Timed out after 90s waiting for a response. If this is the "
                        + "first run, try running \"" + commandLine + "\" directly in a terminal "
                        + "first to complete any one-time login/setup.");
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Plain-text report handed to Gemini CLI via stdin. Reflects exactly what's
     * currently visible in the Live Dashboard table (respecting the Filter/Min
     * Avg boxes, current sort, and expanded rows) and the current selection
     * scope — same semantics as Export CSV / Export Excel, so what you send to
     * the AI matches what you're looking at.
     */
    private String buildReportText() {
        StringBuilder sb = new StringBuilder();
        String scope = selectedLabel == null ? "Overall" : selectedLabel;
        sb.append("JMeter Performance Test Summary\n");
        sb.append("Scope: ").append(scope).append('\n');
        sb.append("Generated: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append("\n\n");

        long n = totalCount.sum();
        long err = totalErrors.sum();
        double avg = n == 0 ? 0 : (double) totalTime.sum() / n;
        double errPct = n == 0 ? 0 : 100.0 * err / n;
        double elapsedSec = Math.max(1, (lastSampleMillis - testStartMillis) / 1000.0);
        double throughput = n / elapsedSec;

        sb.append("Overall totals (whole test, regardless of table filter/selection):\n");
        sb.append(String.format("  Total Samples: %d%n", n));
        sb.append(String.format("  Errors: %d (%.2f%%)%n", err, errPct));
        sb.append(String.format("  Avg Response Time: %.0f ms%n", avg));
        sb.append(String.format("  Throughput: %.1f req/s%n", throughput));
        if (slaThresholdMs != null) {
            sb.append(String.format("  SLA threshold (P95): %.0f ms%n", slaThresholdMs));
        }
        sb.append('\n');

        sb.append("Per-transaction breakdown (as currently filtered/sorted/expanded in the live table):\n");
        sb.append(String.format("%-35s %10s %8s %8s %9s %9s %9s %9s %9s %9s %12s%n",
                "Label", "Samples", "Errors", "Error%", "Avg(ms)", "Min(ms)", "Max(ms)",
                "P90(ms)", "P95(ms)", "P99(ms)", "Throughput"));
        int rowCount = tableModel.getRowCount();
        for (int i = 0; i < rowCount; i++) {
            Row r = tableModel.getRowAt(i);
            if (r == null) {
                continue;
            }
            String label = (r.level == 1 ? "  \u2514 " : "") + displayLabelFor(r);
            sb.append(String.format("%-35s %10d %8d %8.2f %9.0f %9d %9d %9d %9d %9d %12.2f%n",
                    label, r.samples, r.errors, r.errorPct, r.avg, r.min, r.max,
                    r.p90, r.p95, r.p99, r.throughput));
        }
        sb.append('\n');

        sb.append("Top response/error codes:\n");
        Map<String, LongAdder> codeSource = selectedLabel == null
                ? responseCodeCounts
                : codeCountsByLabel.getOrDefault(selectedLabel, Collections.emptyMap());
        for (Map.Entry<String, Long> e : buildCodeBreakdown(codeSource)) {
            sb.append(String.format("  %-50s %d%n", e.getKey(), e.getValue()));
        }

        return sb.toString();
    }

    /**
     * Creates a JFileChooser defaulting to the current test plan's own
     * directory \u2014 the closest equivalent JMeter has to a "project
     * folder" \u2014 rather than whatever directory the JVM happened to be
     * launched from. Falls back to the platform default (JFileChooser's own
     * behavior) if no test plan is currently open/saved yet, or if
     * GuiPackage can't answer (e.g. too early in startup).
     */
    /**
     * Reads the current command text directly from the combo box's editor,
     * reflecting whatever has been typed (even a custom value not in the
     * dropdown's preset list) or selected, at this exact moment \u2014 more
     * reliable than getSelectedItem(), which only updates once an edit is
     * "committed" (e.g. on focus loss or Enter), not on every keystroke.
     */
    private String currentAiCommand() {
        Object item = geminiCommandField.getEditor().getItem();
        return item == null ? "" : item.toString();
    }

    private static JFileChooser newProjectFileChooser() {
        JFileChooser chooser = new JFileChooser();
        try {
            String testPlanPath = GuiPackage.getInstance().getTestPlanFile();
            if (testPlanPath != null && !testPlanPath.isEmpty()) {
                File parent = new File(testPlanPath).getParentFile();
                if (parent != null && parent.isDirectory()) {
                    chooser.setCurrentDirectory(parent);
                }
            }
        } catch (Exception ignored) {
            // Falls back to JFileChooser's own default directory.
        }
        return chooser;
    }

    private void saveReportAsHtml() {
        if (lastReportBodyHtml.isEmpty()) {
            JOptionPane.showMessageDialog(parentFrame(), "Nothing to save yet \u2014 run a test first so this "
                            + "tab has a report to export.", "Nothing to save", JOptionPane.WARNING_MESSAGE);
            return;
        }
        JFileChooser chooser = newProjectFileChooser();
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("HTML Report (*.html)", "html"));
        chooser.setSelectedFile(new File("performance-test-report-" + System.currentTimeMillis() + ".html"));
        int choice = chooser.showSaveDialog(this);
        if (choice != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (!file.getName().toLowerCase().endsWith(".html")) {
            file = new File(file.getParentFile(), file.getName() + ".html");
        }
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8))) {
            pw.print(wrapHtmlDocumentForExport(lastReportBodyHtml));
        } catch (IOException e) {
            JOptionPane.showMessageDialog(parentFrame(), "Failed to save: " + e.getMessage(),
                    "Save failed", JOptionPane.ERROR_MESSAGE);
            return;
        }
        JOptionPane.showMessageDialog(parentFrame(), "Report saved to:\n" + file.getAbsolutePath()
                        + "\n\nAlways saved as a light, properly-margined document regardless of "
                        + "JMeter's own current theme \u2014 meant for opening in a browser and sharing, "
                        + "not for matching whatever Look & Feel you're running here.",
                "Saved", JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Assembles the full formal report: data-driven sections (computed directly
     * from the test data, no AI involved) plus the AI analysis section (or an
     * explanation of why it's unavailable, if aiError is non-null). Data
     * sections always render regardless of whether the AI call succeeded.
     *
     * Unlike Export CSV/Excel/the AI prompt text (which respect the live
     * table's current filter/sort/expand state), these Top-N sections are
     * computed from the FULL underlying dataset — expand/collapse is just a
     * table browsing convenience and shouldn't silently hide requests from a
     * report; the Filter/Min Avg boxes are for narrowing what you're looking
     * at interactively, not for defining what counts as "the test results".
     * The Overall-vs-single-transaction scope (selectedLabel) is still
     * respected, since that's a deliberate choice, not incidental UI state.
     */
    /** Frozen output of the data-only sections (1\u20134), captured once so an AI analysis
     *  and the sections displayed alongside it always describe the exact same data -
     *  even if the AI call takes a while and whatever these sections would otherwise
     *  re-query live has changed in the meantime (a different row selected, a JTL
     *  reloaded, anything else). Without this, re-querying live data only when the AI
     *  response comes back can pair that response with sections that no longer match
     *  what it actually analyzed. */
    private static final class DataSectionsSnapshot {
        final String html;
        final boolean slaSectionEmpty;

        DataSectionsSnapshot(String html, boolean slaSectionEmpty) {
            this.html = html;
            this.slaSectionEmpty = slaSectionEmpty;
        }
    }

    /** Builds sections 1\u20134 (Test Overview, Performance Metrics, Failures & Errors,
     *  SLA Compliance) from whatever the live data is at the moment this is called. */
    private DataSectionsSnapshot buildDataSectionsHtml() {
        List<ParentSnapshot> transactions = collectTransactionSnapshots();
        List<ParentSnapshot> requests = collectRequestSnapshots();

        List<ParentSnapshot> slowestTransactions = new ArrayList<>(transactions);
        slowestTransactions.sort((a, b) -> Double.compare(b.avg, a.avg));

        List<ParentSnapshot> slowestRequests = new ArrayList<>(requests);
        slowestRequests.sort((a, b) -> Double.compare(b.avg, a.avg));

        List<ParentSnapshot> failedNodes = new ArrayList<>();
        failedNodes.addAll(transactions);
        failedNodes.addAll(requests);
        failedNodes.removeIf(p -> p.errors == 0);
        failedNodes.sort((a, b) -> Long.compare(b.errors, a.errors));

        List<Map.Entry<String, Long>> topErrorCodes = collectTopErrorCodes();

        String scope = selectedLabel == null ? "Overall" : selectedLabel;
        String generatedAt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

        StringBuilder body = new StringBuilder();
        body.append("<h1>JMeter Performance Test Report</h1>\n");
        body.append("<p class=\"meta\">Scope: ").append(escapeHtmlStatic(scope))
                .append(" &nbsp;|&nbsp; Generated: ").append(escapeHtmlStatic(generatedAt))
                .append("</p>\n");
        body.append("<p class=\"meta\"><i>Sections 1\u20134 below are computed directly from the full "
                + "test dataset for the current scope \u2014 independent of the Live Dashboard table's "
                + "Filter/Min Avg boxes and which rows happen to be expanded there \u2014 and populate "
                + "automatically as soon as the test finishes, with no AI involved. Section 5 "
                + "specifically is the one AI-enhanced part.</i></p>\n");

        body.append("<h2>1. Test Overview</h2>\n");
        body.append(buildOverallStatsHtml());

        body.append("<h2>2. Performance Metrics</h2>\n");
        body.append(renderTransactionTableHtml("Top 10 Slowest Transactions", slowestTransactions, 10, "Transaction"));
        body.append(renderTransactionTableHtml("Top 10 Slowest Requests", slowestRequests, 10, "Request"));

        body.append("<h2>3. Failures &amp; Errors</h2>\n");
        body.append(renderTransactionTableHtml(
                "Failed Transactions &amp; Requests", failedNodes, 10, "Label"));
        body.append(renderCodeTableHtml("Top 5 Error/Response Codes", topErrorCodes, 5));

        String slaSection = buildSlaBreachesHtml(transactions, requests);
        if (!slaSection.isEmpty()) {
            body.append("<h2>4. SLA Compliance</h2>\n");
            body.append(slaSection);
        }

        return new DataSectionsSnapshot(body.toString(), slaSection.isEmpty());
    }

    /** Appends the AI-observations section to an already-built data-sections snapshot -
     *  does NOT re-query anything live, so whatever snapshot is passed in is exactly
     *  what ends up displayed alongside the AI text. */
    private String buildFullReportHtml(DataSectionsSnapshot dataSections, String aiMarkdown, Exception aiError) {
        StringBuilder body = new StringBuilder(dataSections.html);

        body.append("<hr/>\n");
        body.append("<h2>").append(dataSections.slaSectionEmpty ? "4" : "5").append(". Observations &amp; Analysis</h2>\n");
        if (!aiEverAttempted) {
            body.append("<p class=\"meta\"><i>Not yet generated \u2014 click \u201cEnhance Observations "
                    + "with AI\u201d below to add a written analysis of the data above (likely causes, "
                    + "an overall verdict, concrete recommendations). Sections 1\u20134 are already "
                    + "complete without it.</i></p>\n");
        } else if (aiError != null) {
            body.append(buildAiErrorSectionHtml(aiError));
        } else {
            body.append(MarkdownLite.toHtml(aiMarkdown));
        }

        lastReportBodyHtml = body.toString();
        return wrapHtmlDocument(lastReportBodyHtml);
    }

    /** Convenience overload for callers that want "fresh, right now" semantics (data-only
     *  reload, clearing the report, the test-just-ended auto-populate) - captures a new
     *  snapshot at call time rather than reusing one from earlier. */
    private String buildFullReportHtml(String aiMarkdown, Exception aiError) {
        return buildFullReportHtml(buildDataSectionsHtml(), aiMarkdown, aiError);
    }

    /** Transaction-level snapshots for the whole test (or just the selected transaction, if one is focused). */
    private List<ParentSnapshot> collectTransactionSnapshots() {
        List<ParentSnapshot> list = new ArrayList<>();
        if (selectedLabel != null) {
            LabelStats s = statsByLabel.get(selectedLabel);
            if (s != null) {
                list.add(snapshotOf(selectedLabel, s));
            }
        } else {
            for (Map.Entry<String, LabelStats> e : statsByLabel.entrySet()) {
                list.add(snapshotOf(e.getKey(), e.getValue()));
            }
        }
        return list;
    }

    /** Sub-request-level snapshots, flattened across all transactions (or just the selected one). */
    private List<ParentSnapshot> collectRequestSnapshots() {
        List<ParentSnapshot> list = new ArrayList<>();
        if (selectedLabel != null) {
            Map<String, LabelStats> children = childStatsByParent.get(selectedLabel);
            if (children != null) {
                for (Map.Entry<String, LabelStats> e : children.entrySet()) {
                    list.add(snapshotOf(e.getKey(), e.getValue()));
                }
            }
        } else {
            for (Map<String, LabelStats> children : childStatsByParent.values()) {
                for (Map.Entry<String, LabelStats> e : children.entrySet()) {
                    list.add(snapshotOf(e.getKey(), e.getValue()));
                }
            }
        }
        return list;
    }

    /** Response/error codes only (excludes 2xx success codes), sorted by count descending. */
    private List<Map.Entry<String, Long>> collectTopErrorCodes() {
        Map<String, LongAdder> source = selectedLabel == null
                ? responseCodeCounts
                : codeCountsByLabel.getOrDefault(selectedLabel, Collections.emptyMap());
        List<Map.Entry<String, Long>> errors = new ArrayList<>();
        for (Map.Entry<String, LongAdder> e : source.entrySet()) {
            if (!e.getKey().startsWith("2")) {
                errors.add(new AbstractMap.SimpleEntry<>(e.getKey(), e.getValue().sum()));
            }
        }
        errors.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return errors;
    }

    private ParentSnapshot snapshotOf(String label, LabelStats s) {
        long n = s.count.sum();
        long errCount = s.errors.sum();
        double avg = n == 0 ? 0 : (double) s.sumTime.sum() / n;
        double errPct = n == 0 ? 0 : 100.0 * errCount / n;
        long min = s.minTime == Long.MAX_VALUE ? 0 : s.minTime;
        long max = s.maxTime == Long.MIN_VALUE ? 0 : s.maxTime;
        long[] sorted = s.sortedReservoirSnapshot();
        long p90 = percentileOf(sorted, 90);
        long p95 = percentileOf(sorted, 95);
        long p99 = percentileOf(sorted, 99);
        return new ParentSnapshot(label, n, errCount, errPct, avg, min, max, p90, p95, p99, s.throughputSafe());
    }

    private String buildOverallStatsHtml() {
        long n = totalCount.sum();
        long err = totalErrors.sum();
        double avg = n == 0 ? 0 : (double) totalTime.sum() / n;
        double errPct = n == 0 ? 0 : 100.0 * err / n;
        double elapsedSec = Math.max(1, (lastSampleMillis - testStartMillis) / 1000.0);
        double throughput = n / elapsedSec;

        StringBuilder sb = new StringBuilder();
        sb.append("<h3>Overall Totals</h3>\n<table class=\"report-table\">\n");
        sb.append(kv("Total Samples", String.valueOf(n)));
        sb.append(kv("Errors", err + " (" + String.format("%.2f", errPct) + "%)"));
        sb.append(kv("Avg Response Time", String.format("%.0f ms", avg)));
        sb.append(kv("Throughput", String.format("%.1f req/s", throughput)));
        if (slaThresholdMs != null) {
            sb.append(kv("SLA Threshold (P95)", String.format("%.0f ms", slaThresholdMs)));
        }
        sb.append("</table>\n");
        return sb.toString();
    }

    private String buildSlaBreachesHtml(List<ParentSnapshot> transactions, List<ParentSnapshot> requests) {
        if (slaThresholdMs == null) {
            return "";
        }
        List<ParentSnapshot> breaches = new ArrayList<>();
        for (ParentSnapshot p : transactions) {
            if (p.p95 > slaThresholdMs) {
                breaches.add(p);
            }
        }
        for (ParentSnapshot p : requests) {
            if (p.p95 > slaThresholdMs) {
                breaches.add(p);
            }
        }
        breaches.sort((a, b) -> Long.compare(b.p95, a.p95));
        return renderTransactionTableHtml(
                "SLA Breaches (P95 &gt; " + String.format("%.0f", slaThresholdMs) + " ms)", breaches, 10, "Label");
    }

    private static String kv(String key, String value) {
        return "<tr><th style=\"text-align:left\">" + escapeHtmlStatic(key) + "</th><td>"
                + escapeHtmlStatic(value) + "</td></tr>\n";
    }

    private static String renderTransactionTableHtml(String title, List<ParentSnapshot> rows, int limit, String labelHeader) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h3>").append(title).append("</h3>\n"); // title may itself contain a deliberate &gt; etc.
        if (rows.isEmpty()) {
            sb.append("<p><i>None.</i></p>\n");
            return sb.toString();
        }
        sb.append("<table class=\"report-table\">\n<tr><th>").append(escapeHtmlStatic(labelHeader))
                .append("</th><th>Samples</th><th>Errors</th><th>Error %</th><th>Avg (ms)</th>"
                        + "<th>Min (ms)</th><th>Max (ms)</th><th>P90 (ms)</th><th>P95 (ms)</th>"
                        + "<th>P99 (ms)</th><th>Throughput (req/s)</th></tr>\n");
        int count = 0;
        for (ParentSnapshot p : rows) {
            if (count >= limit) {
                break;
            }
            sb.append("<tr><td>").append(escapeHtmlStatic(p.label)).append("</td>")
                    .append("<td class=\"num\">").append(p.samples).append("</td>")
                    .append("<td class=\"num\">").append(p.errors).append("</td>")
                    .append("<td class=\"num\">").append(String.format("%.2f", p.errorPct)).append("</td>")
                    .append("<td class=\"num\">").append(String.format("%.0f", p.avg)).append("</td>")
                    .append("<td class=\"num\">").append(p.min).append("</td>")
                    .append("<td class=\"num\">").append(p.max).append("</td>")
                    .append("<td class=\"num\">").append(p.p90).append("</td>")
                    .append("<td class=\"num\">").append(p.p95).append("</td>")
                    .append("<td class=\"num\">").append(p.p99).append("</td>")
                    .append("<td class=\"num\">").append(String.format("%.2f", p.throughput)).append("</td>")
                    .append("</tr>\n");
            count++;
        }
        sb.append("</table>\n");
        return sb.toString();
    }

    private static String renderCodeTableHtml(String title, List<Map.Entry<String, Long>> entries, int limit) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h3>").append(escapeHtmlStatic(title)).append("</h3>\n");
        if (entries.isEmpty()) {
            sb.append("<p><i>None \u2014 no non-2xx response/error codes recorded.</i></p>\n");
            return sb.toString();
        }
        sb.append("<table class=\"report-table\">\n<tr><th>Code</th><th>Count</th></tr>\n");
        int count = 0;
        for (Map.Entry<String, Long> e : entries) {
            if (count >= limit) {
                break;
            }
            sb.append("<tr><td>").append(escapeHtmlStatic(e.getKey())).append("</td>")
                    .append("<td class=\"num\">").append(e.getValue()).append("</td></tr>\n");
            count++;
        }
        sb.append("</table>\n");
        return sb.toString();
    }

    private static String wrapHtmlDocument(String bodyHtml) {
        Color panelBg = uiColor("Panel.background", Color.WHITE);
        boolean dark = isDark(panelBg);
        String bg = toCssColor(panelBg);
        String text = dark ? "#e6e6e6" : "#222222";
        String muted = dark ? "#aaaaaa" : "#666666";
        String heading = dark ? "#7fb3ff" : "#2c3e50";
        String tableHeaderBg = dark ? "#1f2937" : "#2c3e50";
        String borderColor = dark ? "#555555" : "#cccccc";
        String codeBg = dark ? "#3a3a3a" : "#eeeeee";

        return "<html><head><style>"
                + "body { font-family: Arial, Helvetica, sans-serif; font-size: 12px; "
                + "color: " + text + "; background-color: " + bg + "; }"
                + "h1 { font-size: 18px; color: " + text + "; }"
                + "h2 { font-size: 15px; color: " + heading + "; margin-top: 26px; "
                + "border-top: 2px solid " + borderColor + "; padding-top: 10px; }"
                + "h3 { font-size: 13px; color: " + heading + "; margin-top: 16px; margin-bottom: 4px; }"
                + ".meta { color: " + muted + "; }"
                + "table.report-table { border-collapse: collapse; width: 100%; table-layout: fixed; "
                + "margin-bottom: 10px; }"
                + "table.report-table th { background-color: " + tableHeaderBg + "; color: #ffffff; "
                + "padding: 4px 8px; word-break: break-word; overflow-wrap: break-word; }"
                + "table.report-table td { border: 1px solid " + borderColor + "; padding: 4px 8px; "
                + "color: " + text + "; word-break: break-word; overflow-wrap: break-word; }"
                + "table.report-table th:first-child, table.report-table td:first-child { width: 38%; }"
                + "td.num { text-align: right; }"
                + "code { color: " + text + "; background-color: " + codeBg + "; padding: 1px 3px; }"
                + "pre { color: " + text + "; background-color: " + codeBg + "; padding: 6px; "
                + "font-family: Consolas, 'Courier New', monospace; font-size: 12px; "
                + "white-space: pre-wrap; word-break: break-all; overflow-wrap: break-word; }"
                + "li { color: " + text + "; }"
                + ".note { border-left: 4px solid " + heading + "; background-color: " + codeBg
                + "; padding: 6px 10px; margin: 10px 0; }"
                + ".chat-user { margin: 10px 0; padding: 6px 10px; border-left: 4px solid " + heading + "; }"
                + ".chat-ai { margin: 10px 0; padding: 6px 10px; border-left: 4px solid "
                + (dark ? "#4caf50" : "#2e7d32") + "; background-color: " + codeBg + "; }"
                + ".chat-thinking { margin: 10px 0; padding: 6px 10px; border-left: 4px solid "
                + "#e67e22; font-style: italic; }"
                + "table.plain { border-collapse: collapse; margin: 8px 0; }"
                + "table.plain td, table.plain th { border: 1px solid " + borderColor
                + "; padding: 4px 8px; color: " + text + "; }"
                + "table.plain th { background-color: " + tableHeaderBg + "; color: #ffffff; }"
                + ".kv-title { font-size: 12px; font-weight: bold; color: " + heading
                + "; margin: 0 0 6px 0; }"
                + "table.kv-table { border-collapse: collapse; font-size: 11px; }"
                + "table.kv-table td.kv-label { font-weight: bold; color: " + muted
                + "; padding: 2px 10px 2px 0; vertical-align: top; white-space: nowrap; }"
                + "table.kv-table td.kv-value { padding: 2px 0; color: " + text + "; word-break: break-all; }"
                + "</style></head><body>" + bodyHtml + "</body></html>";
    }

    /**
     * Same content as wrapHtmlDocument(), but always light and laid out like
     * an actual document \u2014 a page with margins and a visible edge,
     * centered on a muted page background, rather than edge-to-edge like the
     * in-app view. Used only for the saved/exported file: that file gets
     * opened in a browser by whoever it's shared with, independent of
     * whatever theme JMeter's own GUI happens to be running on the author's
     * machine, and a shareable report reads better as a light, printable
     * document than inheriting someone's personal dark-mode preference.
     */
    private static String wrapHtmlDocumentForExport(String bodyHtml) {
        String text = "#222222";
        String muted = "#666666";
        String heading = "#2c3e50";
        String tableHeaderBg = "#2c3e50";
        String borderColor = "#cccccc";
        String codeBg = "#eeeeee";

        return "<html><head><style>"
                + "html, body { margin: 0; padding: 0; background-color: #e8e8e8; }"
                + "body { font-family: Arial, Helvetica, sans-serif; font-size: 13px; color: " + text + "; }"
                + ".page { max-width: 1100px; margin: 0 auto; padding: 40px 50px; "
                + "background-color: #ffffff; box-shadow: 0 0 12px rgba(0,0,0,0.15); min-height: 100vh; "
                + "box-sizing: border-box; }"
                + "h1 { font-size: 22px; color: " + text + "; margin-top: 0; }"
                + "h2 { font-size: 16px; color: " + heading + "; margin-top: 28px; "
                + "border-top: 2px solid " + borderColor + "; padding-top: 10px; }"
                + "h3 { font-size: 14px; color: " + heading + "; margin-top: 16px; margin-bottom: 4px; }"
                + ".meta { color: " + muted + "; }"
                + "table.report-table { border-collapse: collapse; width: 100%; table-layout: fixed; "
                + "margin-bottom: 10px; }"
                + "table.report-table th { background-color: " + tableHeaderBg + "; color: #ffffff; "
                + "padding: 6px 10px; word-break: break-word; overflow-wrap: break-word; }"
                + "table.report-table td { border: 1px solid " + borderColor + "; padding: 6px 10px; "
                + "color: " + text + "; word-break: break-word; overflow-wrap: break-word; }"
                + "table.report-table th:first-child, table.report-table td:first-child { width: 38%; }"
                + "td.num { text-align: right; }"
                + "code { color: " + text + "; background-color: " + codeBg + "; padding: 1px 3px; }"
                + "pre { color: " + text + "; background-color: " + codeBg + "; padding: 8px; "
                + "font-family: Consolas, 'Courier New', monospace; font-size: 12px; "
                + "white-space: pre-wrap; word-break: break-all; overflow-wrap: break-word; }"
                + "li { color: " + text + "; }"
                + ".note { border-left: 4px solid " + heading + "; background-color: " + codeBg
                + "; padding: 8px 12px; margin: 12px 0; }"
                + "table.plain { border-collapse: collapse; margin: 8px 0; }"
                + "table.plain td, table.plain th { border: 1px solid " + borderColor
                + "; padding: 6px 10px; color: " + text + "; }"
                + "table.plain th { background-color: " + tableHeaderBg + "; color: #ffffff; }"
                + "@media print { body { background-color: #ffffff; } "
                + ".page { box-shadow: none; max-width: none; } }"
                + "</style></head><body><div class=\"page\">" + bodyHtml + "</div></body></html>";
    }

    /**
     * Explicitly relays mouse wheel events to the given scroll pane's vertical
     * scrollbar. JEditorPane's HTML view can swallow wheel events without
     * forwarding them to its enclosing JScrollPane (a long-documented Swing
     * quirk), so this makes scrolling reliable regardless of that, rather than
     * depending on the default forwarding behavior. Takes a plain JComponent
     * (not specifically JEditorPane) so a JTextArea can share this same,
     * single implementation too, rather than a second, nearly-identical copy
     * of this logic existing elsewhere.
     *
     * Sets an explicit, reasonably large unit increment on the scroll pane -
     * without one, it falls back to Swing's own small default, making every
     * wheel notch move only a few pixels and scrolling feel sluggish. Also
     * consumes the event once handled, so it can't also reach the
     * JViewport's own default wheel handling underneath and fight over the
     * same scrollbar - without this, which of the two "wins" can depend on
     * exactly where over the component the pointer is (its own bounds vs.
     * the viewport's background around short content), which plausibly
     * explains scrolling that feels like it only works "at the edges".
     */
    private static void installMouseWheelScrolling(JComponent pane, JScrollPane scrollPane) {
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        pane.addMouseWheelListener(e -> {
            javax.swing.JScrollBar bar = scrollPane.getVerticalScrollBar();
            int newValue = bar.getValue() + e.getUnitsToScroll() * bar.getUnitIncrement();
            newValue = Math.max(bar.getMinimum(), Math.min(bar.getMaximum(), newValue));
            bar.setValue(newValue);
            e.consume();
        });
    }

    private static String toCssColor(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static String escapeHtmlStatic(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** @return a 2-element array: [0] the Filter & Sort row, meant to stay always visible
     *  since it's the everyday, every-refresh control group; [1] a panel holding Alerts and
     *  View & Tools together, meant for the caller to wrap in a collapsible "More Options"
     *  toggle, since those are set once and left alone far more often than Filter & Sort is
     *  touched. Kept as one method rather than two, despite returning two panels, since
     *  splitting it would mean separating local variables (clearFilters, exportCsv, etc.)
     *  from the listeners wired to them later in this same method body - a real risk of
     *  introducing exactly the kind of "defined in the wrong place" bug already caught once
     *  this session, for no benefit beyond a cosmetic method split. */
    private JPanel[] buildControlsBar() {
        JPanel filterSortContainer = new JPanel(new BorderLayout());
        JPanel moreOptionsContainer = new JPanel();
        moreOptionsContainer.setLayout(new BoxLayout(moreOptionsContainer, BoxLayout.Y_AXIS));

        // ---- Group 1: Filter & Sort - the everyday, every-refresh controls ----
        JPanel filterRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        filterRow.add(groupLabel("Filter & Sort:"));
        filterRow.add(new JLabel("Name contains:"));
        filterRow.add(filterField);

        filterRow.add(new JLabel("Thread Group:"));
        threadGroupFilterCombo.setToolTipText("<html>Same-named transactions in different thread "
                + "groups are combined in \"All thread groups\" \u2014 pick one<br>specific thread group "
                + "here to see its own numbers separately instead.</html>");
        threadGroupFilterCombo.addActionListener(e -> refreshUi());
        filterRow.add(threadGroupFilterCombo);

        filterRow.add(new JLabel("Min Avg (ms):"));
        filterRow.add(minAvgField);

        errorsOnlyCheckbox.setToolTipText("<html>Shows only rows that have at least one error \u2014 a "
                + "transaction row stays visible if ANY of its own<br>sub-requests failed, even if the "
                + "transaction itself shows 0 directly, so you can expand into it<br>and find which "
                + "one; its sub-requests are then filtered to the failing ones too.</html>");
        filterRow.add(errorsOnlyCheckbox);

        filterRow.add(new JLabel("Sort by:"));
        sortByCombo.setSelectedItem("Avg (ms)");
        filterRow.add(sortByCombo);
        filterRow.add(sortDirectionButton);

        JButton clearFilters = new JButton("Clear filters");
        filterRow.add(clearFilters);
        filterSortContainer.add(filterRow, BorderLayout.CENTER);

        // ---- Group 2: Alerts - thresholds and notification opt-ins, set once then left alone ----
        JPanel alertsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        alertsRow.add(groupLabel("Alerts:"));
        alertsRow.add(new JLabel("SLA - P95 (ms):"));
        alertsRow.add(slaField);

        errorRateThresholdField.setToolTipText("<html>Error rate % threshold for the desktop "
                + "notification below \u2014 a transaction crossing this triggers one, separately<br>from "
                + "the SLA (P95) threshold. Leave blank to notify on SLA breaches only.</html>");
        alertsRow.add(new JLabel("Error Rate % Threshold:"));
        alertsRow.add(errorRateThresholdField);

        notifyOnBreachCheckbox.setToolTipText("<html>Opt-in \u2014 shows a desktop notification the "
                + "first time any transaction crosses the SLA (P95) threshold<br>or the error rate % "
                + "threshold above, so a long-running test doesn't need to stay in focus to be noticed. "
                + "Fires once per<br>transaction per run, not repeatedly for an ongoing breach. Needs at "
                + "least one of the two thresholds set to do anything.</html>");
        alertsRow.add(notifyOnBreachCheckbox);

        flagAnomaliesCheckbox.setToolTipText("<html>Opt-in \u2014 compares each transaction's last ~10 "
                + "seconds against its own running average for this test<br>(not a threshold you set) "
                + "and flags it, via desktop notification and the Anomalies tab, the first time it "
                + "deviates<br>significantly. Needs at least 10 samples for a transaction before it has "
                + "a meaningful baseline to compare against.</html>");
        alertsRow.add(flagAnomaliesCheckbox);

        askAiForAnomaliesCheckbox.setToolTipText("<html>Only matters with \u201cFlag anomalies "
                + "automatically\u201d also on \u2014 when an anomaly is detected, additionally asks the "
                + "AI<br>(same command as the Results Summary (AI) tab) for a short explanation, shown "
                + "in the Anomalies tab once it responds.<br>Off by default: an AI call per detected "
                + "anomaly is a real cost worth opting into rather than paying always.</html>");
        alertsRow.add(askAiForAnomaliesCheckbox);
        moreOptionsContainer.add(alertsRow);
        moreOptionsContainer.add(thinSeparator());

        // ---- Group 3: View & Tools - column visibility, export, and the occasional-use
        // run-level tools, all consolidated into dropdown menus rather than spread across
        // individual buttons, since none of these are clicked every few seconds the way the
        // Filter & Sort row's controls are. ----
        JPanel viewRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        viewRow.add(groupLabel("View & Tools:"));

        JButton columnsButton = new JButton("Columns \u25BC");
        columnsButton.setToolTipText("Show/hide individual columns in the table below.");
        viewRow.add(columnsButton);
        columnsButton.addActionListener(e -> buildColumnsPopup().show(columnsButton, 0, columnsButton.getHeight()));

        JButton exportCsv = new JButton("Export CSV\u2026");
        viewRow.add(exportCsv);

        JButton exportXlsx = new JButton("Export Excel\u2026");
        viewRow.add(exportXlsx);

        JButton copyTableButton = new JButton("Copy Table");
        copyTableButton.setToolTipText("<html>Copies every matching transaction and all its sub-"
                + "requests to the clipboard as tab-separated values, ready to<br>paste into Excel or "
                + "Sheets \u2014 regardless of which rows are currently expanded or selected on screen, "
                + "unlike a plain<br>Ctrl+C on the table itself, which only copies what's currently "
                + "visible in the tree.</html>");
        viewRow.add(copyTableButton);

        JButton runToolsButton = new JButton("Run Tools \u25BC");
        runToolsButton.setToolTipText("<html>Save/compare whole-run snapshots, or view one thread's "
                + "transaction timeline.</html>");
        viewRow.add(runToolsButton);
        moreOptionsContainer.add(viewRow);

        JButton saveRunButton = new JButton("Save Run Snapshot\u2026");
        saveRunButton.setToolTipText("<html>Saves the current run's per-transaction stats (samples, "
                + "errors, avg, P90/P95/P99) under a name, so you<br>can compare a later run against it "
                + "via \"Compare Runs\u2026\" \u2014 persisted to disk, survives a JMeter restart.</html>");

        JButton compareRunsButton = new JButton("Compare Runs\u2026");
        compareRunsButton.setToolTipText("Pick a saved run as a baseline and see per-transaction "
                + "deltas against it (or against the current live data), with regressions and "
                + "improvements flagged and an overall pass/fail verdict.");

        JButton timelineButton = new JButton("Transaction Timeline\u2026");
        timelineButton.setToolTipText("<html>Shows one thread's most recent iteration as a sequential "
                + "timeline \u2014 each named transaction as a bar<br>from its start to its end, so you "
                + "can see where the time in one pass through the script actually went,<br>rather than "
                + "scanning the flat per-request table.</html>");

        runToolsButton.addActionListener(e -> {
            JPopupMenu menu = new JPopupMenu();
            menu.add(wrapAsMenuItem(saveRunButton));
            menu.add(wrapAsMenuItem(compareRunsButton));
            menu.add(wrapAsMenuItem(timelineButton));
            menu.show(runToolsButton, 0, runToolsButton.getHeight());
        });

        saveRunButton.addActionListener(e -> {
            if (totalCount.sum() == 0) {
                JOptionPane.showMessageDialog(parentFrame(), "No data to save yet \u2014 run a test first.",
                        "Nothing to Save", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            String name = JOptionPane.showInputDialog(parentFrame(), "Name this run:", "Save Run Snapshot",
                    JOptionPane.PLAIN_MESSAGE);
            if (name == null || name.trim().isEmpty()) {
                return;
            }
            saveRunSnapshotToDisk(captureCurrentRunSnapshot(name.trim()));
        });
        compareRunsButton.addActionListener(e -> {
            List<RunSnapshot> saved = loadAllRunSnapshots();
            RunSnapshot live = totalCount.sum() > 0 ? captureCurrentRunSnapshot("Current (live)") : null;
            if (live != null) {
                live.savedAtMillis = 0; // distinguishes it from any real saved snapshot for RunComparisonDialog's labeling
            }
            if (saved.isEmpty() && live == null) {
                JOptionPane.showMessageDialog(parentFrame(), "No saved runs and no current data to compare \u2014 "
                        + "run a test, or save a run snapshot, first.", "Nothing to Compare",
                        JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (saved.isEmpty()) {
                JOptionPane.showMessageDialog(parentFrame(), "No saved runs yet \u2014 use \"Save Run Snapshot\u2026\" "
                        + "after a run completes, then come back here to compare against it.",
                        "No Saved Runs", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            new RunComparisonDialog((Frame) SwingUtilities.getWindowAncestor(this), saved, live).setVisible(true);
        });
        timelineButton.addActionListener(e -> {
            if (currentIterationByThread.isEmpty()) {
                JOptionPane.showMessageDialog(parentFrame(), "No transaction timing captured yet \u2014 run a "
                        + "test first.", "Nothing to Show", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            new TimelineDialog((Frame) SwingUtilities.getWindowAncestor(this), currentIterationByThread)
                    .setVisible(true);
        });

        DocumentListener triggerRefresh = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refreshUi(); }
            @Override public void removeUpdate(DocumentEvent e) { refreshUi(); }
            @Override public void changedUpdate(DocumentEvent e) { refreshUi(); }
        };
        filterField.getDocument().addDocumentListener(triggerRefresh);
        minAvgField.getDocument().addDocumentListener(triggerRefresh);
        slaField.getDocument().addDocumentListener(triggerRefresh);
        errorRateThresholdField.getDocument().addDocumentListener(triggerRefresh);
        errorsOnlyCheckbox.addActionListener(e -> refreshUi());
        sortByCombo.addActionListener(e -> refreshUi());
        sortDirectionButton.addActionListener(e -> {
            sortAscending = !sortAscending;
            sortDirectionButton.setText(sortAscending ? "\u25B2 Asc" : "\u25BC Desc");
            refreshUi();
        });
        clearFilters.addActionListener(e -> {
            filterField.setText("");
            threadGroupFilterCombo.setSelectedIndex(0);
            minAvgField.setText("");
            slaField.setText("");
            errorRateThresholdField.setText("");
            errorsOnlyCheckbox.setSelected(false);
            sortByCombo.setSelectedItem("Avg (ms)");
            sortAscending = false;
            sortDirectionButton.setText("\u25BC Desc");
            refreshUi();
        });
        exportCsv.addActionListener(e -> exportSnapshotCsv());
        exportXlsx.addActionListener(e -> exportSnapshotXlsx());
        copyTableButton.addActionListener(e -> copyTableToClipboard());

        return new JPanel[] {filterSortContainer, moreOptionsContainer};
    }

    /** A saved view: every filter field above plus which columns are visible, named and
     *  switchable instead of resetting everything by hand each time. Persisted to disk so
     *  presets survive a JMeter restart. */
    private JPanel buildPresetsRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        row.add(groupLabel("Saved Views:"));
        row.add(new JLabel("Apply:"));
        presetCombo.setToolTipText("<html><b>What this does:</b> remembers every filter field and "
                + "checkbox above, plus which columns are visible, under a name<br>you pick - so you "
                + "can switch straight to, say, \"Errors only + key columns\" without resetting each "
                + "control<br>by hand every time. Pick a saved name here to apply it immediately; "
                + "\"Save Preset\u2026\" captures the current<br>state of every control above under a new "
                + "(or existing) name.</html>");
        refreshPresetCombo();
        row.add(presetCombo);
        row.add(savePresetButton);
        row.add(deletePresetButton);

        presetCombo.addActionListener(e -> {
            if (applyingPreset) {
                return;
            }
            String selected = (String) presetCombo.getSelectedItem();
            if (selected == null || selected.isEmpty()) {
                return;
            }
            for (DashboardPreset p : savedPresets) {
                if (p.name.equals(selected)) {
                    applyPreset(p);
                    break;
                }
            }
        });
        savePresetButton.addActionListener(e -> {
            String name = JOptionPane.showInputDialog(parentFrame(), "Name this preset:", "Save View Preset",
                    JOptionPane.PLAIN_MESSAGE);
            if (name == null || name.trim().isEmpty()) {
                return;
            }
            String trimmedName = name.trim();
            DashboardPreset preset = captureCurrentAsPreset(trimmedName);
            savedPresets.removeIf(p -> p.name.equals(trimmedName)); // replace an existing one of the same name
            savedPresets.add(preset);
            savePresetsToDisk();
            refreshPresetCombo();
            applyingPreset = true;
            try {
                presetCombo.setSelectedItem(trimmedName);
            } finally {
                applyingPreset = false;
            }
        });
        deletePresetButton.addActionListener(e -> {
            String selected = (String) presetCombo.getSelectedItem();
            if (selected == null || selected.isEmpty()) {
                return;
            }
            int confirm = JOptionPane.showConfirmDialog(parentFrame(), "Delete preset \"" + selected + "\"?",
                    "Delete Preset", JOptionPane.YES_NO_OPTION);
            if (confirm != JOptionPane.YES_OPTION) {
                return;
            }
            savedPresets.removeIf(p -> p.name.equals(selected));
            savePresetsToDisk();
            refreshPresetCombo();
        });

        return row;
    }

    private void refreshPresetCombo() {
        applyingPreset = true;
        try {
            presetCombo.removeAllItems();
            presetCombo.addItem("");
            for (DashboardPreset p : savedPresets) {
                presetCombo.addItem(p.name);
            }
        } finally {
            applyingPreset = false;
        }
    }

    private DashboardPreset captureCurrentAsPreset(String name) {
        DashboardPreset p = new DashboardPreset();
        p.name = name;
        p.filterText = filterField.getText();
        p.minAvg = minAvgField.getText();
        p.sla = slaField.getText();
        p.errorRateThreshold = errorRateThresholdField.getText();
        p.errorsOnly = errorsOnlyCheckbox.isSelected();
        Object tg = threadGroupFilterCombo.getSelectedItem();
        p.threadGroup = tg == null ? "All thread groups" : tg.toString();
        Object sk = sortByCombo.getSelectedItem();
        p.sortKey = sk == null ? "Avg (ms)" : sk.toString();
        p.sortAscending = sortAscending;
        p.visibleColumns.clear();
        for (int i = 1; i < allColumns.length; i++) { // skip Label (index 0) - always visible
            if (isColumnVisible(i)) {
                p.visibleColumns.add(tableModel.getColumnName(i));
            }
        }
        return p;
    }

    private void applyPreset(DashboardPreset p) {
        filterField.setText(p.filterText);
        minAvgField.setText(p.minAvg);
        slaField.setText(p.sla);
        errorRateThresholdField.setText(p.errorRateThreshold);
        errorsOnlyCheckbox.setSelected(p.errorsOnly);
        threadGroupFilterCombo.setSelectedItem(p.threadGroup);
        sortByCombo.setSelectedItem(p.sortKey);
        sortAscending = p.sortAscending;
        sortDirectionButton.setText(sortAscending ? "\u25B2 Asc" : "\u25BC Desc");
        for (int i = 1; i < allColumns.length; i++) {
            setColumnVisible(i, p.visibleColumns.contains(tableModel.getColumnName(i)));
        }
        refreshUi();
    }

    private JScrollPane configureTable() {
        table.setFillsViewportHeight(true);
        table.setRowHeight(26);
        table.setFont(table.getFont().deriveFont(Font.PLAIN, 13f));
        table.getTableHeader().setFont(table.getTableHeader().getFont().deriveFont(Font.BOLD, 13f));

        DefaultTableCellRenderer numericRenderer = new DefaultTableCellRenderer();
        numericRenderer.setHorizontalAlignment(SwingConstants.RIGHT);
        numericRenderer.setFont(numericRenderer.getFont().deriveFont(Font.BOLD, 13f));
        table.setDefaultRenderer(Object.class, numericRenderer);

        table.getColumnModel().getColumn(0).setCellRenderer(new LabelCellRenderer(tableModel));
        table.getColumnModel().getColumn(0).setPreferredWidth(260);

        TableColumnModel cm = table.getColumnModel();
        allColumns = new TableColumn[cm.getColumnCount()];
        for (int i = 0; i < cm.getColumnCount(); i++) {
            allColumns[i] = cm.getColumn(i);
        }

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowRowPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowRowPopup(e);
            }

            /** Windows/Linux fire the popup trigger on release; some other platforms on
             *  press — checking both in the standard way is what makes this portable. */
            private void maybeShowRowPopup(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                int viewRow = table.rowAtPoint(e.getPoint());
                if (viewRow < 0) {
                    return;
                }
                table.setRowSelectionInterval(viewRow, viewRow);
                Row meta = tableModel.getRowAt(viewRow);
                if (meta == null) {
                    return;
                }
                JPopupMenu menu = new JPopupMenu();
                JMenuItem goToItem = new JMenuItem("Go to \"" + meta.label + "\" in Test Plan");
                goToItem.addActionListener(a -> goToInTestPlan(meta.label));
                menu.add(goToItem);
                menu.show(table, e.getX(), e.getY());
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int viewRow = table.rowAtPoint(e.getPoint());
                int viewCol = table.columnAtPoint(e.getPoint());
                if (viewRow < 0 || viewCol != 0) {
                    return;
                }
                Row meta = tableModel.getRowAt(viewRow);
                if (meta != null && meta.level == 0 && meta.hasChildren) {
                    tableModel.toggleExpanded(meta.label);
                    refreshUi();
                }
            }
        });
        table.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int viewRow = table.rowAtPoint(e.getPoint());
                int viewCol = table.columnAtPoint(e.getPoint());
                Row meta = viewRow >= 0 && viewCol == 0 ? tableModel.getRowAt(viewRow) : null;
                boolean clickable = meta != null && meta.level == 0 && meta.hasChildren;
                table.setCursor(Cursor.getPredefinedCursor(
                        clickable ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            }
        });
        // Click-to-sort on the Label column header specifically - not a native
        // JTable row sorter (setAutoCreateRowSorter), which would conflict with
        // this table's parent/child expand-collapse structure the same way it
        // would for any hierarchical table. Reuses the existing Sort by:/Desc
        // dropdown mechanism instead: selecting "Label" there, or toggling
        // direction via sortDirectionButton's own already-wired logic, produces
        // the exact same result this reaches for via a single header click.
        JTableHeader header = table.getTableHeader();
        header.setToolTipText("<html>Click \u201cLabel\u201d to sort alphabetically (click again to "
                + "reverse direction) \u2014 same effect as the Sort by: dropdown to the left, just one "
                + "click<br>on this specific column instead. Drag a column boundary to resize it, or "
                + "double-click one to auto-fit that column to its content.</html>");
        // JTableHeader's own native drag-to-resize is disabled entirely in favor of the
        // fully custom mousePressed/mouseDragged/mouseReleased handling just below - reported
        // as still not draggable even after an earlier cursor-only fix, and with no way to
        // interactively debug JTableHeader's own internal drag handling in this environment,
        // a self-contained mechanism that doesn't depend on it at all is more reliable than
        // continuing to patch around whatever's actually preventing the native one from
        // working. Disabling it outright also means there's no risk of the two mechanisms
        // fighting over the same drag gesture.
        //
        // Column REORDERING (a separate, enabled-by-default JTableHeader feature, never
        // intentionally used anywhere in this table) also has to be disabled here, not just
        // resizing: with resizing off, the native UI delegate started interpreting every
        // header drag as a reorder attempt instead - visible as columns jumping/swapping
        // places with their neighbors rather than the dragged one actually widening, since
        // that's genuinely what column reordering looks like. Disabling both removes the
        // native mechanism from header drags entirely, leaving only the custom handling below.
        header.setResizingAllowed(false);
        header.setReorderingAllowed(false);
        final int[] resizingColumn = {-1};
        final int[] dragStartX = {0};
        final int[] dragStartWidth = {0};
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int col = columnBoundaryAt(table, e.getPoint());
                if (col < 0) {
                    return;
                }
                resizingColumn[0] = col;
                dragStartX[0] = e.getXOnScreen();
                dragStartWidth[0] = table.getColumnModel().getColumn(col).getWidth();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                resizingColumn[0] = -1;
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && isNearColumnBoundary(table, e.getPoint())) {
                    autoFitLabelColumnWidth();
                    return;
                }
                if (table.columnAtPoint(e.getPoint()) != 0) {
                    return;
                }
                if ("Label".equals(sortByCombo.getSelectedItem())) {
                    sortDirectionButton.doClick(); // same toggle+refresh logic already wired on that button
                } else {
                    sortByCombo.setSelectedItem("Label"); // fires its own already-wired listener -> refreshUi()
                }
            }
        });
        header.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (resizingColumn[0] < 0) {
                    return;
                }
                int delta = e.getXOnScreen() - dragStartX[0];
                int newWidth = Math.max(40, dragStartWidth[0] + delta);
                TableColumn col = table.getColumnModel().getColumn(resizingColumn[0]);
                col.setPreferredWidth(newWidth);
                col.setWidth(newWidth);
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                if (isNearColumnBoundary(table, e.getPoint())) {
                    header.setCursor(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR));
                    return;
                }
                boolean overLabelColumn = table.columnAtPoint(e.getPoint()) == 0;
                header.setCursor(Cursor.getPredefinedCursor(
                        overLabelColumn ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            }
        });

        table.getSelectionModel().addListSelectionListener(e -> {
            if (suppressSelectionEvents || e.getValueIsAdjusting()) {
                return;
            }
            int viewRow = table.getSelectedRow();
            Row meta = viewRow >= 0 ? tableModel.getRowAt(viewRow) : null;
            selectedLabel = meta != null ? meta.label : null;
            refreshUi();
            refreshSampleDetailPanel();
        });

        return new JScrollPane(table);
    }

    /** True when p sits within a few pixels of some column's right edge in the header -
     *  the same rough margin a JTableHeader's own UI delegate uses to decide whether the
     *  mouse is on a resize boundary, so custom cursor logic elsewhere can defer to it
     *  there instead of fighting over which cursor wins. */
    /** Returns the view column index whose right edge is within a few pixels of p, or -1
     *  if p isn't near any column boundary at all. Used both for cursor feedback and this
     *  table's own fully custom resize-drag handling (see the header's own
     *  mousePressed/mouseDragged/mouseReleased further below), which replaces
     *  JTableHeader's native drag-to-resize entirely rather than continuing to rely on it -
     *  reported as still not draggable even after an earlier cursor-only fix, and with no
     *  way to interactively debug JTableHeader's own internal drag handling in this
     *  environment, a fully self-contained mechanism is the more reliable fix than
     *  continuing to patch around whatever's preventing the native one from working. */
    private static int columnBoundaryAt(JTable table, java.awt.Point p) {
        int col = table.columnAtPoint(p);
        if (col < 0) {
            return -1;
        }
        TableColumnModel cm = table.getColumnModel();
        final int margin = 6;
        int edgeX = 0;
        for (int i = 0; i <= col && i < cm.getColumnCount(); i++) {
            edgeX += cm.getColumn(i).getWidth();
        }
        return Math.abs(p.x - edgeX) <= margin ? col : -1;
    }

    private static boolean isNearColumnBoundary(JTable table, java.awt.Point p) {
        return columnBoundaryAt(table, p) >= 0;
    }

    /** Resizes the Label column to fit its longest currently-visible text - the standard
     *  double-click-the-boundary convention from Excel and most table/grid UIs, and a
     *  faster, more reliable way to see full label text than precisely dragging a boundary
     *  that JTable's own native grab area makes genuinely narrow to hit. Accounts for the
     *  same prefix/indent LabelCellRenderer actually draws (the tree arrow or "\u2514",
     *  plus the level-based left indent), not just the raw label text, so the result isn't
     *  short of what's actually needed. */
    private void autoFitLabelColumnWidth() {
        FontMetrics fm = table.getFontMetrics(table.getFont().deriveFont(Font.BOLD, 13f));
        int maxWidth = 80; // sensible floor even with a short or empty table
        int rowCount = table.getRowCount();
        for (int row = 0; row < rowCount; row++) {
            Row meta = tableModel.getRowAt(row);
            if (meta == null) {
                continue;
            }
            String prefix;
            int indent;
            if (meta.level == 0) {
                indent = 4;
                prefix = meta.hasChildren ? (meta.expanded ? "\u25BC " : "\u25B6 ") : "    ";
            } else {
                indent = 28;
                prefix = "\u2514 ";
            }
            int textWidth = fm.stringWidth(prefix + displayLabelFor(meta)) + indent + 24; // + status dot + padding
            maxWidth = Math.max(maxWidth, textWidth);
        }
        maxWidth = Math.min(maxWidth, 700); // ceiling, so one unusually long label can't push this absurdly wide
        table.getColumnModel().getColumn(0).setPreferredWidth(maxWidth);
        table.getColumnModel().getColumn(0).setWidth(maxWidth);
    }

    /**
     * Selects and scrolls to the tree node matching this label in JMeter's own
     * currently-open Test Plan, which — since {@code JMeterTreeListener} is
     * already registered as this same JTree's selection listener, exactly as
     * it is when a person clicks a node by hand — also updates the main edit
     * panel on the right to show that element, the same as a normal click.
     * Only reaches JMeter's own GUI tree structure (a well-supported,
     * documented operation via GuiPackage); this can't reach into a
     * different listener's own private UI like View Results Tree.
     */
    private void goToInTestPlan(String label) {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return;
        }
        JMeterTreeModel treeModel = guiPackage.getTreeModel();
        Object root = treeModel.getRoot();
        if (!(root instanceof JMeterTreeNode)) {
            return;
        }
        JMeterTreeNode found = findNodeByLabel((JMeterTreeNode) root, label);
        if (found == null) {
            javax.swing.JOptionPane.showMessageDialog(parentFrame(),
                    "No matching element named \"" + label + "\" found in the currently open Test Plan.\n"
                            + "This can happen if the results came from a different script, or a loaded "
                            + ".jtl from a previous run whose script isn't the one currently open.",
                    "Not found", javax.swing.JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JTree tree = guiPackage.getMainFrame().getTree();
        TreePath path = new TreePath(found.getPath());
        tree.setSelectionPath(path);
        tree.scrollPathToVisible(path);
    }

    /** Depth-first search by TestElement name — the first match wins if more than one
     *  element in the script shares the same name (uncommon, but not prevented by JMeter). */
    private static JMeterTreeNode findNodeByLabel(JMeterTreeNode node, String label) {
        Object userObject = node.getUserObject();
        if (userObject instanceof TestElement && label.equals(((TestElement) userObject).getName())) {
            return node;
        }
        Enumeration<?> children = node.children();
        while (children.hasMoreElements()) {
            Object child = children.nextElement();
            if (child instanceof JMeterTreeNode) {
                JMeterTreeNode result = findNodeByLabel((JMeterTreeNode) child, label);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    /** Builds a fresh checkbox popup each time it's opened, so it always reflects current visibility. */
    private JPopupMenu buildColumnsPopup() {
        JPopupMenu popup = new JPopupMenu();
        for (int i = 1; i < allColumns.length; i++) { // skip Label (index 0) — always visible
            int modelIndex = i;
            String title = tableModel.getColumnName(modelIndex);
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(title, isColumnVisible(modelIndex));
            item.addActionListener(e -> setColumnVisible(modelIndex, item.isSelected()));
            popup.add(item);
        }
        return popup;
    }

    private boolean isColumnVisible(int modelIndex) {
        TableColumnModel cm = table.getColumnModel();
        for (int i = 0; i < cm.getColumnCount(); i++) {
            if (cm.getColumn(i) == allColumns[modelIndex]) {
                return true;
            }
        }
        return false;
    }

    /** Re-inserts a hidden column at the position it would occupy among the currently visible ones. */
    private void setColumnVisible(int modelIndex, boolean visible) {
        TableColumnModel cm = table.getColumnModel();
        TableColumn col = allColumns[modelIndex];
        boolean currentlyVisible = isColumnVisible(modelIndex);
        if (visible && !currentlyVisible) {
            int insertAt = 0;
            for (int i = 0; i < modelIndex; i++) {
                if (isColumnVisible(i)) {
                    insertAt++;
                }
            }
            cm.addColumn(col);
            cm.moveColumn(cm.getColumnCount() - 1, insertAt);
        } else if (!visible && currentlyVisible) {
            cm.removeColumn(col);
        }
    }

    private JPanel buildStatCardsRow() {
        JPanel row = new JPanel(new GridLayout(1, 5, 10, 0));
        row.add(statCard("Total Samples", totalSamplesValue));
        row.add(statCard("Errors", errorsValue));
        row.add(statCard("Error Rate", errorRateValue));
        row.add(statCard("Avg Response Time", avgRespValue));
        row.add(statCard("Throughput", throughputValue));
        return row;
    }

    private static JProgressBar buildBusyIndicator() {
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        bar.setPreferredSize(new Dimension(110, 16));
        bar.setVisible(false);
        return bar;
    }

    private static JLabel statValue(String initial) {
        JLabel l = new JLabel(initial, SwingConstants.CENTER);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 22f));
        return l;
    }

    private static JPanel statCard(String title, JLabel valueLabel) {
        Color panelBg = uiColor("Panel.background", new Color(0xf5, 0xf6, 0xf8));
        boolean dark = isDark(panelBg);
        Color cardBg = blend(panelBg, dark ? Color.WHITE : Color.BLACK, 0.06);
        Color borderColor = blend(panelBg, dark ? Color.WHITE : Color.BLACK, 0.18);
        Color mutedText = dark ? new Color(0xaa, 0xaa, 0xaa) : Color.GRAY;

        JPanel card = new JPanel(new BorderLayout(0, 4));
        card.setBackground(cardBg);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor),
                BorderFactory.createEmptyBorder(10, 10, 10, 10)));
        JLabel titleLabel = new JLabel(title, SwingConstants.CENTER);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.PLAIN, 12f));
        titleLabel.setForeground(mutedText);
        card.add(titleLabel, BorderLayout.NORTH);
        card.add(valueLabel, BorderLayout.CENTER);
        return card;
    }

    /** A short, bold label anchoring one logical group of controls (Filter, Alerts, View)
     *  within the otherwise-undifferentiated controls bar, so each group is visually
     *  distinct rather than one long, unbroken wall of fields and buttons. */
    private static JLabel groupLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    /** A thin horizontal rule between control groups - deliberately subtle (not a full
     *  titled-border box per group, which would cost more vertical space than this crowded
     *  area can spare) while still giving the eye something to visually break on. */
    private static JSeparator thinSeparator() {
        JSeparator sep = new JSeparator(SwingConstants.HORIZONTAL);
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        return sep;
    }

    /** Wraps an existing JButton (with its own text, tooltip, and action listener already
     *  wired elsewhere) as a JMenuItem for a dropdown menu - forwards the click via
     *  doClick() rather than duplicating that button's own logic in a second place, so the
     *  menu item always does exactly what the button itself would. doClick() fires a
     *  button's listeners regardless of whether it's actually visible or added to any
     *  container, which is what makes this safe even though the source button here never is. */
    private static JMenuItem wrapAsMenuItem(JButton source) {
        JMenuItem item = new JMenuItem(source.getText());
        item.setToolTipText(source.getToolTipText());
        item.addActionListener(e -> source.doClick());
        return item;
    }

    /** Explicitly resolves this panel's actual top-level Frame, for JOptionPane calls to
     *  use instead of passing "this" (the JPanel) directly as the parent. JOptionPane's own
     *  internal parent-to-Frame resolution can behave unreliably when the given component
     *  sits deep inside a complex, non-standard GUI hierarchy - such as the one JMeter
     *  embeds this Visualizer panel within - reportedly causing a dialog to appear
     *  positioned far from the actual main window rather than centered on it. */
    private Frame parentFrame() {
        return (Frame) SwingUtilities.getWindowAncestor(this);
    }

    /** A JEditorPane that always wraps its content to the visible viewport width, instead
     *  of growing wider and requiring horizontal scroll. Fixes a well-known Swing behavior:
     *  JEditorPane's own calculated "natural" width (based on its HTML content) can exceed
     *  the viewport even with white-space: pre-wrap CSS set, since that CSS only controls
     *  where WITHIN that potentially-very-wide natural width wrapping happens, not the
     *  width itself - without this override, a JScrollPane around the pane shows a
     *  horizontal scrollbar instead of the content actually wrapping to what's visible. */
    private static final class WrappingEditorPane extends JEditorPane {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }
    }

    /** A Request viewer with Headers / Body / Cookies sub-tabs, built once and reused for
     *  both the Live Dashboard's and the Failures tab's Request panels - requested
     *  directly, splitting what used to be one long mixed block of text. Headers and
     *  cookies are split apart via splitCookiesFromHeaders() rather than shown together -
     *  cookies in particular tend to be long, unrelated-looking tokens that make the
     *  actually-meaningful headers harder to scan when interleaved with them. */
    private static final class RequestViewerPanel extends JPanel {
        private static final long serialVersionUID = 1L;
        private final JTextArea headersArea = new JTextArea();
        private final JTextArea bodyArea = new JTextArea();
        private final JTextArea cookiesArea = new JTextArea();

        RequestViewerPanel() {
            super(new BorderLayout());
            JTabbedPane subTabs = new JTabbedPane();
            subTabs.addTab("Headers", buildSearchablePlainTextSubPane(headersArea));
            subTabs.addTab("Body", buildSearchablePlainTextSubPane(bodyArea));
            subTabs.addTab("Cookies", buildSearchablePlainTextSubPane(cookiesArea));
            add(subTabs, BorderLayout.CENTER);
        }

        void setContent(String headersText, String bodyText) {
            HeaderSplit split = splitCookiesFromHeaders(headersText, true);
            headersArea.setText(split.headersOnly.isEmpty() ? "(none captured)" : split.headersOnly);
            headersArea.setCaretPosition(0);
            bodyArea.setText(bodyText == null || bodyText.isEmpty()
                    ? "(no request data captured)" : ResponseFormatter.format(bodyText, null));
            bodyArea.setCaretPosition(0);
            cookiesArea.setText(split.cookiesOnly.isEmpty() ? "(none)" : split.cookiesOnly);
            cookiesArea.setCaretPosition(0);
        }

        /** Shows the same plain placeholder text across all three sub-tabs - used when
         *  there's no sample/failure selected at all yet, where splitting a placeholder
         *  message into headers/body/cookies would be nonsensical. */
        void setPlaceholder(String message) {
            headersArea.setText(message);
            bodyArea.setText(message);
            cookiesArea.setText(message);
        }
    }

    /** A Response Data viewer with a Raw / Pretty / Render HTML mode toggle, built once and
     *  reused for both the Live Dashboard's and the Failures tab's Response Data panels.
     *  Raw and Pretty both display into the same plain JTextArea - Pretty re-runs
     *  ResponseFormatter against the raw body with no Content-Type hint, forcing it to try
     *  auto-detecting JSON/XML from the content's own shape even on a response whose actual
     *  Content-Type header didn't trigger that automatically. Render HTML is the genuinely
     *  new capability: switches to a WrappingEditorPane with text/html content type,
     *  actually interpreting the body as markup rather than displaying it as text - useful
     *  for a login page or other full HTML response, though Swing's own HTML renderer is
     *  basic (no modern CSS, no JavaScript), so only simple page structure renders well. */
    private static final class ResponseViewerPanel extends JPanel {
        private static final long serialVersionUID = 1L;

        private final JTextArea textArea = new JTextArea();
        private final WrappingEditorPane htmlPane = new WrappingEditorPane();
        private final CardLayout cardLayout = new CardLayout();
        private final JPanel cardPanel = new JPanel();
        private final JToggleButton rawButton = new JToggleButton("Raw", true);
        private final JToggleButton prettyButton = new JToggleButton("Pretty");
        private final JToggleButton htmlButton = new JToggleButton("Render HTML");
        private final JTextArea headersArea = new JTextArea();
        private final JTextArea cookiesArea = new JTextArea();

        private String bodyText = "";

        ResponseViewerPanel() {
            super(new BorderLayout());

            textArea.setEditable(false);
            textArea.setLineWrap(true);
            textArea.setWrapStyleWord(false);
            textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            textArea.setRows(16); // a bounded, modest default height, matching the other sub-tabs
            Color panelBg = uiColor("Panel.background", Color.WHITE);
            textArea.setBackground(panelBg);
            textArea.setForeground(isDark(panelBg) ? new Color(0xe6, 0xe6, 0xe6) : new Color(0x22, 0x22, 0x22));

            ButtonGroup group = new ButtonGroup();
            group.add(rawButton);
            group.add(prettyButton);
            group.add(htmlButton);
            rawButton.setToolTipText("Exactly as received, no reformatting.");
            prettyButton.setToolTipText("<html>Re-indents the body if it looks like JSON or XML, even "
                    + "if the response's own Content-Type header<br>didn't trigger that "
                    + "automatically.</html>");
            htmlButton.setToolTipText("<html>Renders the body as an actual HTML page rather than "
                    + "showing its markup as text \u2014 useful for<br>login pages and other full HTML "
                    + "responses. Uses Java's own basic HTML renderer: modern CSS<br>and JavaScript "
                    + "won't work, but basic page structure and text will.</html>");
            JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            modeRow.add(new JLabel("View:"));
            modeRow.add(rawButton);
            modeRow.add(prettyButton);
            modeRow.add(htmlButton);

            cardPanel.setLayout(cardLayout);
            JScrollPane textScroll = new JScrollPane(textArea);
            JScrollPane htmlScroll = new JScrollPane(htmlPane);
            htmlScroll.setPreferredSize(textScroll.getPreferredSize());
            installMouseWheelScrolling(textArea, textScroll);
            installMouseWheelScrolling(htmlPane, htmlScroll);
            cardPanel.add(textScroll, "text");
            cardPanel.add(htmlScroll, "html");

            JPanel bodyTop = new JPanel(new BorderLayout());
            bodyTop.add(modeRow, BorderLayout.NORTH);
            bodyTop.add(buildFindBar(() -> htmlButton.isSelected() ? htmlPane : textArea), BorderLayout.SOUTH);
            JPanel bodyContentWrapper = new JPanel(new BorderLayout());
            bodyContentWrapper.add(cardPanel, BorderLayout.NORTH);
            JPanel bodyPanel = new JPanel(new BorderLayout(0, 4));
            bodyPanel.add(bodyTop, BorderLayout.NORTH);
            bodyPanel.add(bodyContentWrapper, BorderLayout.CENTER);

            // Headers and cookies are split out of the raw headers text rather than shown
            // mixed together - requested directly, since cookies in particular tend to be
            // long, unrelated-looking tokens that make the actually-meaningful headers
            // harder to scan when interleaved with them.
            JTabbedPane subTabs = new JTabbedPane();
            subTabs.addTab("Headers", buildSearchablePlainTextSubPane(headersArea));
            subTabs.addTab("Body", bodyPanel);
            subTabs.addTab("Cookies", buildSearchablePlainTextSubPane(cookiesArea));
            add(subTabs, BorderLayout.CENTER);

            rawButton.addActionListener(e -> showRaw());
            prettyButton.addActionListener(e -> showPretty());
            htmlButton.addActionListener(e -> showRenderedHtml());
        }

        /** Sets new content and resets to Raw view each time - the previously-selected mode
         *  might not even make sense for different content (a JSON API response followed by
         *  an HTML login page, say). */
        void setContent(String headersText, String bodyText) {
            HeaderSplit split = splitCookiesFromHeaders(headersText, false);
            headersArea.setText(split.headersOnly.isEmpty() ? "(none captured)" : split.headersOnly);
            headersArea.setCaretPosition(0);
            cookiesArea.setText(split.cookiesOnly.isEmpty() ? "(none)" : split.cookiesOnly);
            cookiesArea.setCaretPosition(0);
            this.bodyText = bodyText == null ? "" : bodyText;
            rawButton.setSelected(true);
            showRaw();
        }

        private void showRaw() {
            cardLayout.show(cardPanel, "text");
            textArea.setText(bodyText.isEmpty() ? "(empty)" : bodyText);
            textArea.setCaretPosition(0);
        }

        private void showPretty() {
            cardLayout.show(cardPanel, "text");
            textArea.setText(bodyText.isEmpty() ? "(empty)" : ResponseFormatter.format(bodyText, null));
            textArea.setCaretPosition(0);
        }

        private static final String TRUNCATION_MARKER = "\u2026 [truncated]";

        /** A genuinely invalid, but surprisingly common, real-world HTML pattern:
         *  style="...font-family: "Some Font", sans-serif;" - the outer double-quoted
         *  attribute value contains an unescaped double quote around the font name, which
         *  Swing's old HTMLEditorKit parser can choke on badly enough to render nothing at
         *  all rather than degrading gracefully. Converting the inner quotes to single
         *  quotes is equally valid CSS and fixes the attribute without needing a full HTML
         *  parser to do it safely. */
        private static final java.util.regex.Pattern NESTED_FONT_FAMILY_QUOTES =
                java.util.regex.Pattern.compile("(font-family\\s*:\\s*)\"([^\"]*)\"",
                        java.util.regex.Pattern.CASE_INSENSITIVE);

        /** Matches a <body>...</body> element, non-greedy so it stops at the *first*
         *  closing </body> rather than any later one a script block's own text might
         *  happen to contain. */
        private static final java.util.regex.Pattern BODY_CONTENT_PATTERN = java.util.regex.Pattern.compile(
                "<body[^>]*>(.*?)</body>", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);

        /** Extracts just the <body>...</body> inner content, discarding the <head>
         *  section, any DOCTYPE declaration, and - critically - anything after the first
         *  closing </body> tag. Real-world pages routinely have structural issues in
         *  exactly those areas (multiple external stylesheets Swing's renderer can't load
         *  anyway, a DOCTYPE it doesn't need, or markup sitting entirely after the page's
         *  own closing </html> tag - confirmed directly in a reported Keycloak login page
         *  with a stray footer div and script outside the closing tag) that are
         *  irrelevant to what's actually visible, but can derail Swing's old, fragile
         *  parser badly enough to render nothing at all rather than just that one area.
         *  Falls back to the original string unchanged if no <body> tag is found, so a
         *  response that's already just a body fragment isn't altered. */
        private static String extractBodyContent(String html) {
            java.util.regex.Matcher bodyMatch = BODY_CONTENT_PATTERN.matcher(html);
            return bodyMatch.find() ? bodyMatch.group(1) : html;
        }

        /** Applies both sanitization steps - body-only extraction, then the font-family
         *  quote fix - and returns inner content only, still needing to be wrapped in a
         *  fresh &lt;html&gt;&lt;body&gt;...&lt;/html&gt; shell by the caller (rather than
         *  wrapping it here), since one caller needs to insert its own warning banner
         *  inside that same shell alongside this content. */
        private static String sanitizeForRendering(String html) {
            String bodyOnly = extractBodyContent(html);
            return NESTED_FONT_FAMILY_QUOTES.matcher(bodyOnly).replaceAll("$1'$2'");
        }

        /** Sets the HTML pane's content with a safety net - if the body still can't be
         *  parsed even after sanitizeForRendering()'s fix (some other malformed markup
         *  this doesn't specifically handle), shows a clear, honest message instead of
         *  silently leaving the pane blank, which otherwise looks like this feature itself
         *  is broken rather than the specific page's markup being the problem. */
        private void setHtmlSafely(String html) {
            try {
                htmlPane.setText(html);
            } catch (Exception renderFailed) {
                htmlPane.setText("<html><body>"
                        + "<div style='background:#fde4e1;color:#5c1f1f;padding:8px;"
                        + "font-family:sans-serif;font-size:12px;'>"
                        + "\u26a0 This page's HTML couldn't be rendered (" + escapeHtmlStatic(renderFailed.getMessage())
                        + ") \u2014 try Raw or Pretty instead to see the actual response.</div>"
                        + "</body></html>");
            }
        }

        private void showRenderedHtml() {
            cardLayout.show(cardPanel, "html");
            htmlPane.setContentType("text/html");
            if (bodyText.isEmpty()) {
                setHtmlSafely("<html><body><i>(empty)</i></body></html>");
            } else if (bodyText.endsWith(TRUNCATION_MARKER)) {
                // The capture itself was cut off mid-document - the marker text isn't valid
                // HTML, and if the cut point landed inside a tag or attribute, Swing's
                // renderer can fail to show anything at all rather than whatever partial
                // content it could. Stripping the marker and warning explicitly beats a
                // silently blank view that looks like this feature itself is broken.
                String withoutMarker = bodyText.substring(0, bodyText.length() - TRUNCATION_MARKER.length());
                setHtmlSafely("<html><body>"
                        + "<div style='background:#fff3cd;color:#664d03;padding:6px;margin-bottom:8px;"
                        + "border:1px solid #ffe69c;font-family:sans-serif;font-size:12px;'>"
                        + "\u26a0 This response was too large to capture in full, so the page below may "
                        + "be incomplete or fail to render past the cut-off point.</div>"
                        + sanitizeForRendering(withoutMarker) + "</body></html>");
            } else {
                setHtmlSafely("<html><body>" + sanitizeForRendering(bodyText) + "</body></html>");
            }
            htmlPane.setCaretPosition(0);
        }
    }

    /** A GridLayout panel that always matches the scroll viewport's width (so its 2x2 grid
     *  of charts never needs horizontal scrolling) while being free to exceed the
     *  viewport's height - so when the window is too short to show all four charts at
     *  their full preferred height, the user scrolls vertically to see the rest, rather
     *  than every chart being squeezed below the height it needs to show its own bottom
     *  axis labels (reported as the chart's bottom - its X-axis - not being visible). */
    private static final class ScrollableChartsPanel extends JPanel implements Scrollable {
        private static final long serialVersionUID = 1L;

        ScrollableChartsPanel(LayoutManager layout) {
            super(layout);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 160;
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

    private static JPanel wrapCard(Component content) {
        Color panelBg = uiColor("Panel.background", Color.WHITE);
        boolean dark = isDark(panelBg);
        Color borderColor = blend(panelBg, dark ? Color.WHITE : Color.BLACK, 0.18);

        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(content.getBackground()); // ChartPanel already picked its own theme-aware background
        card.setBorder(BorderFactory.createLineBorder(borderColor));
        card.add(content, BorderLayout.CENTER);
        return card;
    }

    @Override
    public String getStaticLabel() {
        return "SmartBlitz - Insight Dashboard (AI)";
    }

    @Override
    public String getLabelResource() {
        return "customdashboardvisualizer";
    }

    // ---- Scrollable ----
    // JMeter wraps this panel in a JScrollPane. A plain JPanel doesn't tell
    // that scroll pane to match its width, so widening/narrowing the JMeter
    // window just added a horizontal scrollbar instead of resizing the
    // charts/table — this is what fixes that.
    @Override
    public java.awt.Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(java.awt.Rectangle visibleRect, int orientation, int direction) {
        return 20;
    }

    @Override
    public int getScrollableBlockIncrement(java.awt.Rectangle visibleRect, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    /**
     * Only the Live Dashboard tab (index 0) needs this to be false - its
     * content (stat cards + 2x2 charts + table) can genuinely be taller than
     * available space, and that's fine: the outer JScrollPane handles
     * scrolling it. Every other tab (Results Summary, AI Chat, Failures,
     * Help) already has its own internal scroll pane for its own content,
     * and each one's bottom-pinned button/input bar depends on this whole
     * panel actually being constrained to the real available height - not
     * stretched to whatever height Live Dashboard happens to need, which is
     * what was pushing those bars below the visible area, off-screen, behind
     * an outer scrollbar their own content never needed in the first place.
     */
    @Override
    public boolean getScrollableTracksViewportHeight() {
        return tabs == null || tabs.getSelectedIndex() != 0;
    }

    /**
     * Called by JMeter's sampling thread(s) for every single sample. Keep
     * this fast and Swing-free — all UI updates happen separately on a
     * 1-second Swing Timer via refreshUi().
     */
    @Override
    public void add(SampleResult result) {
        long t = result.getTime();
        totalCount.increment();
        boolean isError = !result.isSuccessful();
        if (isError) {
            totalErrors.increment();
            recordFailure(result);
        }
        totalTime.add(t);
        lastSampleMillis = result.getEndTime();
        String label = result.getSampleLabel();
        statsByLabel.computeIfAbsent(label, l -> new LabelStats()).add(result);
        recordSampleDetail(result);
        recordTimelineEntry(result);

        // getThreadGroup() is documented as nullable ("can happen if called
        // from GUI or from non test threads") - during a real run on a
        // sampling thread it should always be set, but this is cheap
        // insurance against a null label leaking into the UI.
        AbstractThreadGroup tg = JMeterContextService.getContext().getThreadGroup();
        String threadGroupName = deriveThreadGroupName(result, tg);
        knownThreadGroups.add(threadGroupName);
        statsByThreadGroupAndLabel.computeIfAbsent(tgKey(threadGroupName, label), k -> new LabelStats())
                .add(result);

        int activeThreads = JMeterContextService.getThreadCounts().activeThreads;
        long second = result.getEndTime() / 1000L;
        buckets.computeIfAbsent(second, s -> new SecondBucket()).add(t, activeThreads, isError);
        bucketsByLabel.computeIfAbsent(label, l -> new ConcurrentHashMap<>())
                .computeIfAbsent(second, s -> new SecondBucket())
                .add(t, activeThreads, isError);

        String code = normalizeResponseCode(result.getResponseCode());
        responseCodeCounts.computeIfAbsent(code, c -> new LongAdder()).increment();
        codeCountsByLabel.computeIfAbsent(label, l -> new ConcurrentHashMap<>())
                .computeIfAbsent(code, c -> new LongAdder()).increment();

        SampleResult[] subResults = result.getSubResults();
        if (subResults != null && subResults.length > 0) {
            Map<String, LabelStats> children =
                    childStatsByParent.computeIfAbsent(label, l -> new ConcurrentHashMap<>());
            addChildren(children, subResults, activeThreads);

            Map<String, LabelStats> childrenForTg = childStatsByThreadGroupAndParent
                    .computeIfAbsent(tgKey(threadGroupName, label), k -> new ConcurrentHashMap<>());
            addChildren(childrenForTg, subResults, activeThreads);
        }
    }

    /** Truncates overly-long non-HTTP failure messages so slightly-varying text
     *  (e.g. a connection error whose message includes a changing hostname) doesn't
     *  explode into dozens of near-duplicate response-code buckets. */
    private static String normalizeResponseCode(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "(none)";
        }
        return raw.length() > 40 ? raw.substring(0, 40) + "\u2026" : raw;
    }

    /**
     * Called for every failed sample (top-level or nested inside a Transaction
     * Controller). Deliberately called only on failure — retaining response
     * detail for every successful sample too on a long run would grow without
     * bound; this is a small, fixed-size ring buffer instead (MAX_RECENT_FAILURES),
     * enough for "explain what just went wrong" without becoming a memory
     * concern for a multi-hour test.
     */
    private void recordFailure(SampleResult result) {
        String url = "";
        try {
            java.net.URL u = result.getURL();
            if (u != null) {
                url = u.toString();
            }
        } catch (Exception ignored) {
            // Some sampler types don't expose a meaningful URL — leave blank rather than fail.
        }
        String requestData = result.getSamplerData();
        String body = result.getResponseDataAsString();
        if (body == null) {
            body = "";
        }
        String snippet = body.length() > MAX_FAILURE_SNIPPET_CHARS
                ? body.substring(0, MAX_FAILURE_SNIPPET_CHARS) + "\u2026 [truncated]"
                : body;

        String contentType = result.getContentType();
        FailureDetail detail = new FailureDetail(result.getEndTime(), result.getSampleLabel(), url,
                requestData == null ? "" : requestData, result.getResponseCode(),
                result.getResponseMessage(), snippet, contentType == null ? "" : contentType);
        recentFailures.addFirst(detail);
        while (recentFailures.size() > MAX_RECENT_FAILURES) {
            recentFailures.removeLast();
        }

        SwingUtilities.invokeLater(() -> {
            failuresListModel.add(0, detail);
            while (failuresListModel.size() > MAX_RECENT_FAILURES) {
                failuresListModel.remove(failuresListModel.size() - 1);
            }
        });
    }

    /**
     * Captures this sample's request/response detail into latestSampleByLabel,
     * overwriting whatever was there before for this same label — only when
     * captureSampleDetailCheckbox is on, checked first so the common (off) case
     * costs nothing beyond that one read, never touching getResponseDataAsString()
     * at all. Runs for every sample (success or failure), unlike recordFailure()
     * above, which only ever sees failed ones.
     */
    /** Derives the HTTP method from the sampler data's own first line ("METHOD URL" for a
     *  standard HTTP sample), rather than depending on HTTPSampleResult - that class lives
     *  in ApacheJMeter_http, a separate Maven artifact this project doesn't declare as a
     *  dependency (confirmed by an actual compile failure: "package
     *  org.apache.jmeter.protocol.http.sampler does not exist"). Returns empty for a
     *  non-HTTP sample (a JDBC/JMS/etc. sampler's own samplerData has an entirely
     *  different first-line shape, which the method-name check below simply won't match)
     *  rather than guessing. */
    private static String extractMethodFromSamplerData(String samplerData) {
        if (samplerData == null || samplerData.isEmpty()) {
            return "";
        }
        int firstLineEnd = samplerData.indexOf('\n');
        String firstLine = firstLineEnd >= 0 ? samplerData.substring(0, firstLineEnd) : samplerData;
        int firstSpace = firstLine.indexOf(' ');
        if (firstSpace <= 0) {
            return "";
        }
        String candidate = firstLine.substring(0, firstSpace).trim();
        return candidate.matches("[A-Z]{3,10}") ? candidate : "";
    }

    private void recordSampleDetail(SampleResult result) {
        if (!captureSampleDetailCheckbox.isSelected()) {
            return;
        }
        String url = "";
        try {
            java.net.URL u = result.getURL();
            if (u != null) {
                url = u.toString();
            }
        } catch (Exception ignored) {
            // Some sampler types don't expose a meaningful URL — leave blank rather than fail.
        }
        String httpMethod = extractMethodFromSamplerData(result.getSamplerData());
        String requestData = result.getSamplerData();
        String requestHeaders = result.getRequestHeaders();
        String body = result.getResponseDataAsString();
        if (body == null) {
            body = "";
        }
        String snippet = body.length() > MAX_FAILURE_SNIPPET_CHARS
                ? body.substring(0, MAX_FAILURE_SNIPPET_CHARS) + "\u2026 [truncated]"
                : body;
        String contentType = result.getContentType();
        String responseHeaders = result.getResponseHeaders();
        Map<String, String> extractedVariables = diffThreadVariables();
        SampleDetail detail = new SampleDetail(result.getEndTime(), result.getSampleLabel(), url,
                httpMethod == null ? "" : httpMethod, requestData == null ? "" : requestData,
                requestHeaders == null ? "" : requestHeaders, result.isSuccessful(), result.getResponseCode(),
                result.getResponseMessage(), responseHeaders == null ? "" : responseHeaders, snippet,
                contentType == null ? "" : contentType, extractedVariables);
        latestSampleByLabel.put(result.getSampleLabel(), detail);

        if (result.getSampleLabel().equals(selectedLabel)) {
            SwingUtilities.invokeLater(this::refreshSampleDetailPanel);
        }
    }

    /** Compares this thread's current JMeterVariables against the last snapshot taken for
     *  the same thread, returning every variable that's new or whose value changed since
     *  then - an approximation of "what this sample's own post-processors just extracted,"
     *  since JMeter itself doesn't attribute a variable back to the extractor that set it.
     *  Must run synchronously on the sampler thread, right after the sample's own
     *  post-processors have already run and before the next sample on this same thread
     *  does, for the diff to mean anything; recordSampleDetail() satisfies this since it's
     *  called directly from add()/addChildren(), never deferred to another thread. */
    private Map<String, String> diffThreadVariables() {
        JMeterVariables vars = JMeterContextService.getContext().getVariables();
        if (vars == null) {
            return java.util.Collections.emptyMap();
        }
        String threadName = Thread.currentThread().getName();
        Map<String, String> current = new LinkedHashMap<>();
        java.util.Iterator<Map.Entry<String, Object>> it = vars.getIterator();
        while (it.hasNext()) {
            Map.Entry<String, Object> e = it.next();
            Object v = e.getValue();
            current.put(e.getKey(), v == null ? "" : v.toString());
        }
        Map<String, String> previous = lastVariableSnapshotByThread.put(threadName, current);
        if (previous == null) {
            // First sample on this thread - nothing to diff against, but every variable
            // present right now is, by definition, newly set (there was nothing before
            // this sample at all). Reporting them as new here, rather than an empty map,
            // which previously looked exactly like "nothing was extracted" even when
            // extraction had actually worked correctly.
            return current;
        }
        Map<String, String> changed = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : current.entrySet()) {
            String prevValue = previous.get(e.getKey());
            if (prevValue == null || !prevValue.equals(e.getValue())) {
                changed.put(e.getKey(), e.getValue());
            }
        }
        return changed;
    }

    /** A named, saved combination of every filter field plus which columns are visible -
     *  lets the user switch between a few common views ("errors only + key columns" vs.
     *  "full detail") instead of resetting filters by hand each time. Persisted as simple
     *  JSON via MiniJson so presets survive a JMeter restart. */
    private static final class DashboardPreset {
        String name = "";
        String filterText = "";
        String minAvg = "";
        String sla = "";
        String errorRateThreshold = "";
        boolean errorsOnly;
        String threadGroup = "All thread groups";
        String sortKey = "Avg (ms)";
        boolean sortAscending;
        /** Column names (as the table model names them) that should be visible; the Label
         *  column is always visible regardless and isn't stored here. */
        List<String> visibleColumns = new ArrayList<>();

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("filterText", filterText);
            m.put("minAvg", minAvg);
            m.put("sla", sla);
            m.put("errorRateThreshold", errorRateThreshold);
            m.put("errorsOnly", errorsOnly);
            m.put("threadGroup", threadGroup);
            m.put("sortKey", sortKey);
            m.put("sortAscending", sortAscending);
            m.put("visibleColumns", new ArrayList<>(visibleColumns));
            return m;
        }

        static DashboardPreset fromMap(Map<?, ?> m) {
            DashboardPreset p = new DashboardPreset();
            p.name = strOf(m.get("name"));
            p.filterText = strOf(m.get("filterText"));
            p.minAvg = strOf(m.get("minAvg"));
            p.sla = strOf(m.get("sla"));
            p.errorRateThreshold = strOf(m.get("errorRateThreshold"));
            p.errorsOnly = Boolean.TRUE.equals(m.get("errorsOnly"));
            String tg = strOf(m.get("threadGroup"));
            p.threadGroup = tg.isEmpty() ? "All thread groups" : tg;
            String sk = strOf(m.get("sortKey"));
            p.sortKey = sk.isEmpty() ? "Avg (ms)" : sk;
            p.sortAscending = Boolean.TRUE.equals(m.get("sortAscending"));
            Object cols = m.get("visibleColumns");
            if (cols instanceof List) {
                for (Object c : (List<?>) cols) {
                    p.visibleColumns.add(String.valueOf(c));
                }
            }
            return p;
        }

        private static String strOf(Object o) {
            return o == null ? "" : String.valueOf(o);
        }
    }

    private static String jsonStr(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static long jsonLong(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    private static double jsonDouble(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0.0;
    }

    /** A saved summary of one completed (or in-progress) test run - enough per-transaction
     *  detail (samples, errors, error %, avg, P90/P95/P99) to compare against a later run
     *  and flag regressions, without keeping the full raw sample data around. Persisted as
     *  one JSON file per run under ~/.jmeter-insightdashboard/runs/. */
    static final class RunSnapshot {
        String name = "";
        long savedAtMillis;
        long totalSamples;
        long totalErrors;
        double overallAvg;
        double overallThroughput;
        Map<String, RunSnapshotRow> perLabel = new LinkedHashMap<>();

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("savedAtMillis", (double) savedAtMillis);
            m.put("totalSamples", (double) totalSamples);
            m.put("totalErrors", (double) totalErrors);
            m.put("overallAvg", overallAvg);
            m.put("overallThroughput", overallThroughput);
            Map<String, Object> labels = new LinkedHashMap<>();
            for (Map.Entry<String, RunSnapshotRow> e : perLabel.entrySet()) {
                labels.put(e.getKey(), e.getValue().toMap());
            }
            m.put("perLabel", labels);
            return m;
        }

        static RunSnapshot fromMap(Map<?, ?> m) {
            RunSnapshot s = new RunSnapshot();
            s.name = jsonStr(m.get("name"));
            s.savedAtMillis = jsonLong(m.get("savedAtMillis"));
            s.totalSamples = jsonLong(m.get("totalSamples"));
            s.totalErrors = jsonLong(m.get("totalErrors"));
            s.overallAvg = jsonDouble(m.get("overallAvg"));
            s.overallThroughput = jsonDouble(m.get("overallThroughput"));
            Object labels = m.get("perLabel");
            if (labels instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) labels).entrySet()) {
                    if (e.getValue() instanceof Map) {
                        s.perLabel.put(String.valueOf(e.getKey()), RunSnapshotRow.fromMap((Map<?, ?>) e.getValue()));
                    }
                }
            }
            return s;
        }
    }

    static final class RunSnapshotRow {
        long samples;
        long errors;
        double errorPct;
        double avg;
        long p90;
        long p95;
        long p99;

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("samples", (double) samples);
            m.put("errors", (double) errors);
            m.put("errorPct", errorPct);
            m.put("avg", avg);
            m.put("p90", (double) p90);
            m.put("p95", (double) p95);
            m.put("p99", (double) p99);
            return m;
        }

        static RunSnapshotRow fromMap(Map<?, ?> m) {
            RunSnapshotRow r = new RunSnapshotRow();
            r.samples = jsonLong(m.get("samples"));
            r.errors = jsonLong(m.get("errors"));
            r.errorPct = jsonDouble(m.get("errorPct"));
            r.avg = jsonDouble(m.get("avg"));
            r.p90 = jsonLong(m.get("p90"));
            r.p95 = jsonLong(m.get("p95"));
            r.p99 = jsonLong(m.get("p99"));
            return r;
        }
    }

    private static File runsDir() {
        File dir = new File(new File(System.getProperty("user.home"), ".jmeter-insightdashboard"), "runs");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /** One file per run, named from its save time so concurrent saves (unlikely, but this
     *  costs nothing to guard against) can never collide the way a name-only filename could. */
    private static void saveRunSnapshotToDisk(RunSnapshot snap) {
        try {
            File f = new File(runsDir(), "run-" + snap.savedAtMillis + ".json");
            Files.write(f.toPath(), MiniJson.write(snap.toMap()).getBytes(StandardCharsets.UTF_8));
        } catch (Exception writeFailed) {
            // Non-fatal - the snapshot just isn't saved this time; nothing else depends on it.
        }
    }

    private static List<RunSnapshot> loadAllRunSnapshots() {
        List<RunSnapshot> out = new ArrayList<>();
        File[] files = runsDir().listFiles((dir, name) -> name.startsWith("run-") && name.endsWith(".json"));
        if (files == null) {
            return out;
        }
        for (File f : files) {
            try {
                String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                Object parsed = MiniJson.parse(json);
                if (parsed instanceof Map) {
                    out.add(RunSnapshot.fromMap((Map<?, ?>) parsed));
                }
            } catch (Exception corruptedOrUnreadable) {
                // Skip this one file rather than failing the whole list over it.
            }
        }
        out.sort((a, b) -> Long.compare(b.savedAtMillis, a.savedAtMillis)); // newest first
        return out;
    }

    static void deleteRunSnapshot(RunSnapshot snap) {
        new File(runsDir(), "run-" + snap.savedAtMillis + ".json").delete();
    }

    private RunSnapshot captureCurrentRunSnapshot(String name) {
        RunSnapshot snap = new RunSnapshot();
        snap.name = name;
        snap.savedAtMillis = System.currentTimeMillis();
        snap.totalSamples = totalCount.sum();
        snap.totalErrors = totalErrors.sum();
        snap.overallAvg = snap.totalSamples == 0 ? 0 : (double) totalTime.sum() / snap.totalSamples;
        double elapsedSec = Math.max(1, (lastSampleMillis - testStartMillis) / 1000.0);
        snap.overallThroughput = snap.totalSamples / elapsedSec;
        for (Map.Entry<String, LabelStats> e : statsByLabel.entrySet()) {
            LabelStats s = e.getValue();
            long n = s.count.sum();
            if (n == 0) {
                continue;
            }
            RunSnapshotRow row = new RunSnapshotRow();
            row.samples = n;
            row.errors = s.errors.sum();
            row.errorPct = 100.0 * row.errors / n;
            row.avg = (double) s.sumTime.sum() / n;
            long[] sorted = s.sortedReservoirSnapshot();
            row.p90 = percentileOf(sorted, 90);
            row.p95 = percentileOf(sorted, 95);
            row.p99 = percentileOf(sorted, 99);
            snap.perLabel.put(e.getKey(), row);
        }
        return snap;
    }

    private static File presetsFile() {
        File dir = new File(System.getProperty("user.home"), ".jmeter-insightdashboard");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, "dashboard-presets.json");
    }

    private static List<DashboardPreset> loadPresetsFromDisk() {
        File f = presetsFile();
        if (!f.exists()) {
            return new ArrayList<>();
        }
        try {
            String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Object parsed = MiniJson.parse(json);
            List<DashboardPreset> out = new ArrayList<>();
            if (parsed instanceof List) {
                for (Object entry : (List<?>) parsed) {
                    if (entry instanceof Map) {
                        out.add(DashboardPreset.fromMap((Map<?, ?>) entry));
                    }
                }
            }
            return out;
        } catch (Exception corruptedOrUnreadable) {
            return new ArrayList<>(); // start fresh rather than fail the whole dashboard over this
        }
    }

    private void savePresetsToDisk() {
        try {
            List<Object> arr = new ArrayList<>();
            for (DashboardPreset p : savedPresets) {
                arr.add(p.toMap());
            }
            Files.write(presetsFile().toPath(), MiniJson.write(arr).getBytes(StandardCharsets.UTF_8));
        } catch (Exception writeFailed) {
            // Non-fatal - presets just won't persist past this session; the in-memory
            // list the user already has keeps working for the rest of it regardless.
        }
    }

    /** One captured failed sample's detail — enough context to ask the AI what
     *  likely went wrong, without retaining this for every successful sample too. */
    private static final class FailureDetail {
        final long timestampMillis;
        final String label;
        final String url;
        final String requestData;
        final String responseCode;
        final String responseMessage;
        final String responseSnippet;
        final String contentType;

        FailureDetail(long timestampMillis, String label, String url, String requestData,
                      String responseCode, String responseMessage, String responseSnippet,
                      String contentType) {
            this.timestampMillis = timestampMillis;
            this.label = label;
            this.url = url;
            this.requestData = requestData;
            this.responseCode = responseCode;
            this.responseMessage = responseMessage;
            this.responseSnippet = responseSnippet;
            this.contentType = contentType;
        }

        @Override
        public String toString() {
            String time = new SimpleDateFormat("HH:mm:ss").format(new Date(timestampMillis));
            return time + "  " + label + "  [" + responseCode + "]";
        }
    }

    /** The most recent sample's full detail for one label, captured only when
     *  captureSampleDetailCheckbox is on \u2014 same shape as FailureDetail, kept as
     *  a separate type rather than reusing that one directly so the existing,
     *  working Failures tab code is never touched by this feature at all. */
    /** One transaction-level sample's timing, as captured for the timeline/Gantt view. */
    static final class TimelineEntry {
        final String label;
        final long startMillis;
        final long endMillis;
        final boolean successful;

        TimelineEntry(String label, long startMillis, long endMillis, boolean successful) {
            this.label = label;
            this.startMillis = startMillis;
            this.endMillis = endMillis;
            this.successful = successful;
        }
    }

    /**
     * Appends this sample to its thread's current-iteration timeline, detecting a new
     * iteration by noticing its label already appears in that thread's list so far - the
     * normal case for a looping thread group repeating the same script, where each named
     * transaction (markTransactionStart/End, or a Transaction Controller) appears exactly
     * once per pass. A script whose transaction names never repeat at all would never reset
     * this way; MAX_TIMELINE_ENTRIES_PER_THREAD bounds it defensively for that case so
     * memory still can't grow without limit.
     */
    private void recordTimelineEntry(SampleResult result) {
        String threadName = result.getThreadName();
        if (threadName == null) {
            return;
        }
        List<TimelineEntry> list = currentIterationByThread.computeIfAbsent(threadName,
                k -> new CopyOnWriteArrayList<>());
        String label = result.getSampleLabel();
        boolean alreadyInThisIteration = false;
        for (TimelineEntry e : list) {
            if (e.label.equals(label)) {
                alreadyInThisIteration = true;
                break;
            }
        }
        if (alreadyInThisIteration) {
            list.clear();
        }
        list.add(new TimelineEntry(label, result.getStartTime(), result.getEndTime(), result.isSuccessful()));
        while (list.size() > MAX_TIMELINE_ENTRIES_PER_THREAD) {
            list.remove(0);
        }
    }

    private static final class SampleDetail {
        final long timestampMillis;
        final String label;
        final String url;
        final String httpMethod;
        final String requestData;
        final String requestHeaders;
        final boolean successful;
        final String responseCode;
        final String responseMessage;
        final String responseHeaders;
        final String responseSnippet;
        final String contentType;
        /** Variables present in this thread's JMeterVariables that are new or whose value
         *  changed since the previous sample on the same thread - an approximation of "what
         *  this sample's own post-processors (Regex/JSON/XPath extractors) just set," since
         *  JMeter doesn't attribute an extracted variable back to the extractor that set it. */
        final Map<String, String> extractedVariables;

        SampleDetail(long timestampMillis, String label, String url, String httpMethod, String requestData,
                     String requestHeaders, boolean successful, String responseCode, String responseMessage,
                     String responseHeaders, String responseSnippet, String contentType,
                     Map<String, String> extractedVariables) {
            this.timestampMillis = timestampMillis;
            this.label = label;
            this.url = url;
            this.httpMethod = httpMethod;
            this.requestData = requestData;
            this.requestHeaders = requestHeaders;
            this.successful = successful;
            this.responseCode = responseCode;
            this.responseMessage = responseMessage;
            this.responseHeaders = responseHeaders;
            this.responseSnippet = responseSnippet;
            this.contentType = contentType;
            this.extractedVariables = extractedVariables;
        }
    }

    /**
     * Aggregates sub-results into the given parent's child map, and also
     * feeds each child's own per-second buckets and response codes — so
     * selecting a sub-request row in the table (not just its parent
     * transaction) can scope the charts and code breakdown too. Nested
     * Transaction Controllers (children that themselves have sub-results)
     * are flattened one level deeper into the SAME child map rather than
     * building a third tree level, to keep the table a simple two-level
     * (transaction -> requests) view.
     */
    private void addChildren(Map<String, LabelStats> children, SampleResult[] subResults, int activeThreads) {
        for (SampleResult sr : subResults) {
            String childLabel = sr.getSampleLabel();
            children.computeIfAbsent(childLabel, l -> new LabelStats()).add(sr);
            if (!sr.isSuccessful()) {
                recordFailure(sr);
            }
            recordSampleDetail(sr);

            long childSecond = sr.getEndTime() / 1000L;
            bucketsByLabel.computeIfAbsent(childLabel, l -> new ConcurrentHashMap<>())
                    .computeIfAbsent(childSecond, s -> new SecondBucket())
                    .add(sr.getTime(), activeThreads, !sr.isSuccessful());

            String childCode = normalizeResponseCode(sr.getResponseCode());
            responseCodeCounts.computeIfAbsent(childCode, c -> new LongAdder()).increment();
            codeCountsByLabel.computeIfAbsent(childLabel, l -> new ConcurrentHashMap<>())
                    .computeIfAbsent(childCode, c -> new LongAdder()).increment();

            SampleResult[] nested = sr.getSubResults();
            if (nested != null && nested.length > 0) {
                addChildren(children, nested, activeThreads);
            }
        }
    }

    @Override
    public void clearData() {
        dataGeneration++;
        statsByLabel.clear();
        childStatsByParent.clear();
        knownThreadGroups.clear();
        statsByThreadGroupAndLabel.clear();
        childStatsByThreadGroupAndParent.clear();
        buckets.clear();
        bucketsByLabel.clear();
        responseCodeCounts.clear();
        codeCountsByLabel.clear();
        recentFailures.clear();
        latestSampleByLabel.clear();
        lastVariableSnapshotByThread.clear();
        currentIterationByThread.clear();
        alreadyFlaggedAnomalyLabels.clear();
        resetAnomalyLog();
        totalCount.reset();
        totalErrors.reset();
        totalTime.reset();
        testStartMillis = System.currentTimeMillis();
        lastSampleMillis = testStartMillis;
        selectedLabel = null;
        Runnable resetAndRefresh = () -> {
            table.clearSelection();
            failuresListModel.clear();
            threadGroupFilterCombo.removeAllItems();
            threadGroupFilterCombo.addItem("All thread groups");
            refreshUi();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            resetAndRefresh.run();
        } else {
            SwingUtilities.invokeLater(resetAndRefresh);
        }
    }

    @Override
    public void testStarted() {
        testStarted("local");
    }

    @Override
    public void testStarted(String host) {
        // If this fires, great — it resets counters right at test start.
        // If JMeter's ResultCollector doesn't forward it (this is not
        // guaranteed), clearData() still gets called separately: JMeter
        // clears every listener's GUI directly when you press Run,
        // regardless of this hook.
        clearData();
    }

    @Override
    public void testEnded() {
        testEnded("local");
    }

    @Override
    public void testEnded(String host) {
        // Deliberately does NOT stop refreshTimer: this callback reaching this
        // component at all turned out NOT to be reliable in practice (see
        // checkForTestEndViaThreadCount(), which exists because of that), so
        // stopping the timer here risks silently freezing the live view on
        // the rare case this does fire. Leaving it running is cheap and just
        // keeps showing the final numbers regardless.
        //
        // Both calls below are kept anyway, alongside
        // checkForTestEndViaThreadCount()'s own timer-driven trigger: harmless
        // and idempotent on the occasions this callback does arrive, and
        // "sooner" than waiting up to ~1s for the next timer tick on the
        // occasions it does.
        SwingUtilities.invokeLater(this::refreshUi);
        SwingUtilities.invokeLater(this::populateDataOnlyReportAfterTestEnd);
    }

    /**
     * Populates the Results Summary (AI) tab with the data-computed sections
     * (1\u20134) as soon as the test finishes \u2014 no click needed, no AI
     * call involved. Resets any AI Observations from a PRIOR run, since
     * those would now describe stale data that no longer matches what's
     * shown above them; the person can click "Enhance Observations with AI"
     * again for the new run whenever they want that part refreshed too.
     */
    private void populateDataOnlyReportAfterTestEnd() {
        lastAiResponseMarkdown = "";
        aiEverAttempted = false;
        aiStatusLabel.setForeground(null);
        aiStatusLabel.setText("Data populated \u2014 click \"Enhance Observations with AI\" for analysis");
        aiSummaryPane.setText(buildFullReportHtml("", null));
        aiSummaryPane.setCaretPosition(0);
    }

    /** Runs on the EDT (via the Timer, or immediately from a control listener). */
    /**
     * Detects "a test just finished" by watching active thread count directly
     * (via JMeterContextService, the same source JMeter's own toolbar thread
     * counter reads), rather than depending solely on the testEnded()
     * callback actually reaching this component. This exists because that
     * dependency turned out NOT to be reliable in practice \u2014 despite an
     * earlier assumption in this file's constructor comment that it was \u2014
     * refreshUi() itself already runs every second regardless of whether
     * testStarted()/testEnded() ever fire at all (that's the whole reason
     * refreshTimer runs continuously rather than only between those two
     * calls), which is exactly why that unreliability went unnoticed until
     * now: refreshUi() kept the stat cards looking current via the timer
     * alone, independent of testEnded() ever actually firing, masking
     * whether it did. populateDataOnlyReportAfterTestEnd() had no such
     * timer-driven fallback of its own, which is what actually exposed this.
     *
     * Detects the precise transition from "threads active" to "zero active
     * threads," rather than a "no new samples for N seconds" heuristic,
     * since the latter risks a false positive on a test with long think
     * times/pacing between requests, where traffic can genuinely go quiet
     * for a while without the test having ended at all.
     *
     * Known gap: a saved .jtl loaded via "Read from file" never involves any
     * active threads at all, so this specific check does not apply to that
     * path \u2014 only to an actual live run.
     */
    private void checkForTestEndViaThreadCount() {
        boolean activeNow = JMeterContextService.getThreadCounts().activeThreads > 0;
        if (activeNow) {
            reportPopulatedForThisEnd = false; // a run is in progress; ready to populate again once it ends
        } else if (activeThreadsSeenLastTick && totalCount.sum() > 0 && !reportPopulatedForThisEnd) {
            populateDataOnlyReportAfterTestEnd();
            reportPopulatedForThisEnd = true;
        }
        activeThreadsSeenLastTick = activeNow;
    }

    private void refreshUi() {
        checkForTestEndViaThreadCount();

        long n = totalCount.sum();
        long err = totalErrors.sum();
        double avg = n == 0 ? 0 : (double) totalTime.sum() / n;
        double errPct = n == 0 ? 0 : 100.0 * err / n;
        double elapsedSec = Math.max(1, (lastSampleMillis - testStartMillis) / 1000.0);
        double throughput = n / elapsedSec;

        totalSamplesValue.setText(String.valueOf(n));
        errorsValue.setText(String.valueOf(err));
        errorRateValue.setText(String.format("%.2f%%", errPct));
        errorRateValue.setForeground(err > 0 ? ERROR_COLOR : OK_COLOR);
        avgRespValue.setText(String.format("%.0f ms", avg));
        throughputValue.setText(String.format("%.1f req/s", throughput));

        String currentSelection = selectedLabel;
        Map<Long, SecondBucket> activeBuckets = currentSelection == null
                ? buckets
                : bucketsByLabel.getOrDefault(currentSelection, Collections.emptyMap());

        List<Long> seconds = new ArrayList<>(activeBuckets.keySet());
        Collections.sort(seconds);
        int from = Math.max(0, seconds.size() - 120); // last 120 seconds
        List<Double> respSeries = new ArrayList<>();
        List<Double> tpsSeries = new ArrayList<>();
        List<Double> errorRateSeries = new ArrayList<>();
        List<Double> threadSeries = new ArrayList<>();
        for (int i = from; i < seconds.size(); i++) {
            SecondBucket b = activeBuckets.get(seconds.get(i));
            respSeries.add(b.avg());
            tpsSeries.add((double) b.count.sum());
            errorRateSeries.add(b.errorRatePct());
            threadSeries.add((double) b.lastThreadCount);
        }
        responseTimeChart.setData(respSeries);
        throughputChart.setData(tpsSeries);
        errorRateChart.setData(errorRateSeries);
        responseTimeChart.setOverlay(threadSeries, "Active Threads", THREAD_LINE);
        throughputChart.setOverlay(threadSeries, "Active Threads", THREAD_LINE);
        errorRateChart.setOverlay(threadSeries, "Active Threads", THREAD_LINE);

        slaThresholdMs = parseLenient(slaField.getText());
        responseTimeChart.setThreshold(slaThresholdMs, "SLA");

        String contextSuffix = currentSelection == null ? "" : " \u2014 " + currentSelection;
        responseTimeChart.setContextSuffix(contextSuffix);
        throughputChart.setContextSuffix(contextSuffix);
        errorRateChart.setContextSuffix(contextSuffix);
        responseCodeBreakdown.setContextSuffix(contextSuffix);
        chartsContextLabel.setText(currentSelection == null
                ? "Viewing: Overall (click a row in the table to focus)"
                : "Viewing: " + currentSelection);
        showOverallButton.setEnabled(currentSelection != null);

        Map<String, LongAdder> codeSource = currentSelection == null
                ? responseCodeCounts
                : codeCountsByLabel.getOrDefault(currentSelection, Collections.emptyMap());
        responseCodeBreakdown.setData(buildCodeBreakdown(codeSource));

        refreshThreadGroupCombo();
        String selectedThreadGroup = (String) threadGroupFilterCombo.getSelectedItem();
        boolean filteringByThreadGroup = selectedThreadGroup != null
                && !"All thread groups".equals(selectedThreadGroup);
        Map<String, LabelStats> statsForTable = filteringByThreadGroup
                ? filterByThreadGroup(statsByThreadGroupAndLabel, selectedThreadGroup)
                : statsByLabel;
        Map<String, Map<String, LabelStats>> childStatsForTable = filteringByThreadGroup
                ? filterChildrenByThreadGroup(childStatsByThreadGroupAndParent, selectedThreadGroup)
                : childStatsByParent;

        String filterText = filterField.getText().trim();
        Double minAvgMs = parseLenient(minAvgField.getText());
        String sortKey = (String) sortByCombo.getSelectedItem();
        boolean errorsOnly = errorsOnlyCheckbox.isSelected();

        suppressSelectionEvents = true;
        try {
            tableModel.rebuild(statsForTable, childStatsForTable, filterText, minAvgMs, errorsOnly, sortKey, sortAscending);
            if (currentSelection != null) {
                int idx = tableModel.indexOfLabel(currentSelection);
                if (idx >= 0) {
                    table.getSelectionModel().setSelectionInterval(idx, idx);
                } else {
                    // The previously selected row no longer exists (e.g. filtered
                    // out by the Filter/Min Avg boxes) — fall back to overall.
                    selectedLabel = null;
                }
            }
        } finally {
            suppressSelectionEvents = false;
        }
        table.repaint(); // pick up the new slaThresholdMs for row highlighting even if row set is unchanged

        checkForNotifiableConditions(statsForTable);
        checkForAnomalies(statsForTable);
    }

    private static final int ANOMALY_RECENT_WINDOW_SECONDS = 10;
    private static final int ANOMALY_MIN_BASELINE_SAMPLES = 10;
    private static final double ANOMALY_RESPONSE_TIME_FACTOR = 1.5; // 50% above its own running avg
    private static final double ANOMALY_ERROR_RATE_DELTA_PCT = 15.0; // 15 points above its own running rate

    /**
     * Compares each transaction's last {@link #ANOMALY_RECENT_WINDOW_SECONDS} seconds
     * against its OWN running average for this test (not a threshold the user sets - see
     * checkForNotifiableConditions() for that) and flags it the first time it deviates
     * significantly. Purely local, statistical comparison - no AI call happens here at
     * all; recordAnomaly() below is what optionally asks the AI for an explanation, kept
     * separate so this detection pass stays cheap enough to run on every refresh tick.
     */
    private void checkForAnomalies(Map<String, LabelStats> statsForTable) {
        if (!flagAnomaliesCheckbox.isSelected()) {
            return;
        }
        long nowSecond = System.currentTimeMillis() / 1000L;
        for (Map.Entry<String, LabelStats> e : statsForTable.entrySet()) {
            String label = e.getKey();
            if (alreadyFlaggedAnomalyLabels.contains(label)) {
                continue;
            }
            LabelStats s = e.getValue();
            long overallCount = s.count.sum();
            if (overallCount < ANOMALY_MIN_BASELINE_SAMPLES) {
                continue; // not enough history yet for a meaningful baseline
            }
            Map<Long, SecondBucket> labelBuckets = bucketsByLabel.get(label);
            if (labelBuckets == null || labelBuckets.isEmpty()) {
                continue;
            }
            double overallAvg = (double) s.sumTime.sum() / overallCount;
            double overallErrorPct = 100.0 * s.errors.sum() / overallCount;

            long recentSamples = 0;
            long recentTimeSum = 0;
            long recentErrors = 0;
            for (long sec = nowSecond - ANOMALY_RECENT_WINDOW_SECONDS; sec <= nowSecond; sec++) {
                SecondBucket b = labelBuckets.get(sec);
                if (b == null) {
                    continue;
                }
                recentSamples += b.count.sum();
                recentTimeSum += b.sumTime.sum();
                recentErrors += b.errorCount.sum();
            }
            if (recentSamples < 2) {
                continue; // too little recent activity to say anything meaningful yet
            }
            double recentAvg = (double) recentTimeSum / recentSamples;
            double recentErrorPct = 100.0 * recentErrors / recentSamples;

            boolean responseTimeAnomaly = overallAvg > 0 && recentAvg > overallAvg * ANOMALY_RESPONSE_TIME_FACTOR;
            boolean errorRateAnomaly = recentErrorPct > overallErrorPct + ANOMALY_ERROR_RATE_DELTA_PCT;
            if (!responseTimeAnomaly && !errorRateAnomaly) {
                continue;
            }
            alreadyFlaggedAnomalyLabels.add(label);
            String kind = responseTimeAnomaly ? "Response time" : "Error rate";
            String detail = responseTimeAnomaly
                    ? String.format("recent avg %.0f ms vs. this test's own running avg of %.0f ms", recentAvg, overallAvg)
                    : String.format("recent error rate %.1f%% vs. this test's own running rate of %.1f%%",
                            recentErrorPct, overallErrorPct);
            recordAnomaly(label, kind, detail);
        }
    }

    /** Logs one detected anomaly to the Anomalies tab, shows a desktop notification, and -
     *  only if the user has separately opted into it - kicks off a single, rate-limited
     *  background AI call for a short explanation, appended to the same log entry once it
     *  responds. The AI call happens here, once per flagged anomaly, specifically so the
     *  cheap statistical detection above never itself triggers one on every tick. */
    private void recordAnomaly(String label, String kind, String detail) {
        showDesktopNotification("Anomaly: " + label, kind + " \u2014 " + detail);

        String timestamp = new SimpleDateFormat("HH:mm:ss").format(new Date());
        String entryId = "anomaly-" + System.nanoTime();
        anomalyLogHtml.insert(0, "<p id='" + entryId + "'><b>[" + timestamp + "] " + escapeHtmlStatic(label)
                + "</b> \u2014 " + escapeHtmlStatic(kind) + ": " + escapeHtmlStatic(detail)
                + (askAiForAnomaliesCheckbox.isSelected()
                    ? "<br/><i id='" + entryId + "-ai'>Asking AI for an explanation\u2026</i>" : "")
                + "</p><hr/>");
        anomalyLogPane.setText(wrapHtmlDocument(anomalyLogHtml.toString()));
        anomalyLogPane.setCaretPosition(0);

        if (!askAiForAnomaliesCheckbox.isSelected()) {
            return;
        }
        String commandLine = currentAiCommand().trim();
        if (commandLine.isEmpty()) {
            return; // same "no command configured" case the other AI features already handle
        }
        String promptText = "Transaction: " + label + "\nAnomaly type: " + kind + "\nDetail: " + detail;
        new SwingWorker<String, Void>() {
            private Exception error;

            @Override
            protected String doInBackground() {
                try {
                    return invokeGeminiCli(commandLine, promptText, ANOMALY_PROMPT_INSTRUCTION);
                } catch (Exception ex) {
                    error = ex;
                    return null;
                }
            }

            @Override
            protected void done() {
                String replacement;
                if (error != null) {
                    replacement = "AI unavailable: " + escapeHtmlStatic(error.getMessage());
                } else {
                    String answer;
                    try {
                        answer = get();
                    } catch (Exception ignored) {
                        answer = null;
                    }
                    replacement = answer == null || answer.isEmpty()
                            ? "AI returned no explanation." : MarkdownLite.toHtml(answer);
                }
                // Rebuilds the whole log from its current HTML text, replacing just this one
                // entry's placeholder - simpler and safer than tracking per-entry DOM nodes
                // across what could be several concurrent AI calls for different anomalies.
                String current = anomalyLogHtml.toString();
                String placeholder = "<i id='" + entryId + "-ai'>Asking AI for an explanation\u2026</i>";
                String updated = current.replace(placeholder, "<i>" + replacement + "</i>");
                anomalyLogHtml.setLength(0);
                anomalyLogHtml.append(updated);
                anomalyLogPane.setText(wrapHtmlDocument(anomalyLogHtml.toString()));
            }
        }.execute();
    }

    /** Checks every label's current aggregate stats against the SLA (P95) and/or error-rate
     *  thresholds, firing a desktop notification the first time each label crosses either -
     *  alreadyNotifiedLabels prevents repeating it on every subsequent refresh tick for the
     *  same ongoing breach. A no-op unless the checkbox is on and at least one threshold is
     *  actually set, so this costs nothing for anyone not using the feature. */
    private void checkForNotifiableConditions(Map<String, LabelStats> statsForTable) {
        if (!notifyOnBreachCheckbox.isSelected()) {
            return;
        }
        Double errorRateThreshold = parseLenient(errorRateThresholdField.getText());
        if (slaThresholdMs == null && errorRateThreshold == null) {
            return;
        }
        for (Map.Entry<String, LabelStats> e : statsForTable.entrySet()) {
            String label = e.getKey();
            if (alreadyNotifiedLabels.contains(label)) {
                continue;
            }
            LabelStats s = e.getValue();
            long n = s.count.sum();
            if (n == 0) {
                continue;
            }
            long p95 = percentileOf(s.sortedReservoirSnapshot(), 95);
            double errPct = 100.0 * s.errors.sum() / n;
            if (slaThresholdMs != null && p95 > slaThresholdMs) {
                alreadyNotifiedLabels.add(label);
                showDesktopNotification("SLA breach: " + label,
                        String.format("P95 %d ms exceeds the %.0f ms threshold", p95, slaThresholdMs));
            } else if (errorRateThreshold != null && errPct > errorRateThreshold) {
                alreadyNotifiedLabels.add(label);
                showDesktopNotification("Error rate breach: " + label,
                        String.format("Error rate %.1f%% exceeds the %.1f%% threshold", errPct, errorRateThreshold));
            }
        }
    }

    /** Shows a desktop notification via the system tray, if the platform supports one -
     *  silently does nothing otherwise (a headless CI box, or a platform/desktop environment
     *  without tray support) rather than failing the whole refresh over a notification that
     *  simply can't be shown there. The tray icon itself is created once and reused; nothing
     *  about its own (invisible, 1x1) image matters since only the popup notification text
     *  is ever actually seen. */
    private void showDesktopNotification(String title, String message) {
        if (!SystemTray.isSupported()) {
            return;
        }
        try {
            if (notificationTrayIcon == null) {
                Image blank = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                notificationTrayIcon = new TrayIcon(blank, "Insight Dashboard");
                notificationTrayIcon.setImageAutoSize(true);
                SystemTray.getSystemTray().add(notificationTrayIcon);
            }
            notificationTrayIcon.displayMessage(title, message, TrayIcon.MessageType.WARNING);
        } catch (Exception trayUnavailable) {
            // Some platforms/window managers report isSupported() true but still fail here -
            // never let a notification failure interrupt the actual dashboard refresh.
        }
    }

    /** Top 8 response/error codes by count, with the rest folded into "Other". */
    private List<Map.Entry<String, Long>> buildCodeBreakdown(Map<String, LongAdder> source) {
        List<Map.Entry<String, Long>> entries = new ArrayList<>();
        for (Map.Entry<String, LongAdder> e : source.entrySet()) {
            entries.add(new AbstractMap.SimpleEntry<>(e.getKey(), e.getValue().sum()));
        }
        entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

        List<Map.Entry<String, Long>> top = new ArrayList<>();
        long otherSum = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (i < 8) {
                top.add(entries.get(i));
            } else {
                otherSum += entries.get(i).getValue();
            }
        }
        if (otherSum > 0) {
            top.add(new AbstractMap.SimpleEntry<>("Other", otherSum));
        }
        return top;
    }

    private static Double parseLenient(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Writes what's currently visible in the table (respecting filter/sort/expand state) to a CSV file. */
    /** Builds the full row list - every matching transaction and all its sub-requests,
     *  regardless of current expand/collapse state - using the dashboard's own current
     *  filter/sort/thread-group selections. This is the data Export CSV/Excel and "Copy
     *  Table" all actually want, as opposed to tableModel's own row list, which only
     *  contains whatever happens to be currently visible/expanded on screen. */
    private List<Row> buildCurrentFullRows() {
        String selectedThreadGroup = (String) threadGroupFilterCombo.getSelectedItem();
        boolean filteringByThreadGroup = selectedThreadGroup != null
                && !"All thread groups".equals(selectedThreadGroup);
        Map<String, LabelStats> statsForTable = filteringByThreadGroup
                ? filterByThreadGroup(statsByThreadGroupAndLabel, selectedThreadGroup)
                : statsByLabel;
        Map<String, Map<String, LabelStats>> childStatsForTable = filteringByThreadGroup
                ? filterChildrenByThreadGroup(childStatsByThreadGroupAndParent, selectedThreadGroup)
                : childStatsByParent;
        String filterText = filterField.getText().trim();
        Double minAvgMs = parseLenient(minAvgField.getText());
        String sortKey = (String) sortByCombo.getSelectedItem();
        boolean errorsOnly = errorsOnlyCheckbox.isSelected();
        return tableModel.buildFullRows(statsForTable, childStatsForTable, filterText, minAvgMs,
                errorsOnly, sortKey, sortAscending);
    }

    /** Copies every matching transaction and all its sub-requests to the clipboard as
     *  tab-separated values, ready to paste straight into a spreadsheet - using the same
     *  full dataset (including children whose parent is currently collapsed) the CSV/Excel
     *  exports use, rather than a plain Ctrl+C on the table, which only copies whatever
     *  JTable's own default TransferHandler finds in the model - and that model only
     *  contains rows currently visible/expanded on screen. */
    private void copyTableToClipboard() {
        List<Row> rows = buildCurrentFullRows();
        if (rows.isEmpty()) {
            JOptionPane.showMessageDialog(parentFrame(), "No rows to copy \u2014 check your filters.",
                    "Nothing to Copy", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Label\tLevel\tSamples\tErrors\tError %\tAvg (ms)\tMin (ms)\tMax (ms)\t"
                + "P90 (ms)\tP95 (ms)\tP99 (ms)\tThroughput (req/s)\n");
        for (Row r : rows) {
            sb.append(r.label).append('\t')
                    .append(r.level).append('\t')
                    .append(r.samples).append('\t')
                    .append(r.errors).append('\t')
                    .append(String.format("%.2f", r.errorPct)).append('\t')
                    .append(String.format("%.0f", r.avg)).append('\t')
                    .append(r.min).append('\t')
                    .append(r.max).append('\t')
                    .append(r.p90).append('\t')
                    .append(r.p95).append('\t')
                    .append(r.p99).append('\t')
                    .append(String.format("%.2f", r.throughput)).append('\n');
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sb.toString()), null);
        JOptionPane.showMessageDialog(parentFrame(), rows.size() + " row(s) copied to the clipboard "
                + "\u2014 paste directly into Excel or Sheets.", "Copied", JOptionPane.INFORMATION_MESSAGE);
    }

    private void exportSnapshotCsv() {
        JFileChooser chooser = newProjectFileChooser();
        chooser.setSelectedFile(new File("dashboard-snapshot-" + System.currentTimeMillis() + ".csv"));
        int choice = chooser.showSaveDialog(this);
        if (choice != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8))) {
            pw.println("Label,Level,Samples,Errors,Error %,Avg (ms),Min (ms),Max (ms),P90 (ms),P95 (ms),P99 (ms),Throughput (req/s)");
            for (Row r : buildCurrentFullRows()) {
                pw.println(String.join(",",
                        csvEscape(r.label),
                        String.valueOf(r.level),
                        String.valueOf(r.samples),
                        String.valueOf(r.errors),
                        String.format("%.2f", r.errorPct),
                        String.format("%.0f", r.avg),
                        String.valueOf(r.min),
                        String.valueOf(r.max),
                        String.valueOf(r.p90),
                        String.valueOf(r.p95),
                        String.valueOf(r.p99),
                        String.format("%.2f", r.throughput)));
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(parentFrame(), "Failed to write CSV: " + e.getMessage(),
                    "Export failed", JOptionPane.ERROR_MESSAGE);
            return;
        }
        JOptionPane.showMessageDialog(parentFrame(), "Snapshot exported to:\n" + file.getAbsolutePath(),
                "Export complete", JOptionPane.INFORMATION_MESSAGE);
    }

    private static String csvEscape(String s) {
        if (s == null) {
            return "";
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /**
     * Writes what's currently visible in the table (respecting filter/sort/expand
     * state) to a real .xlsx workbook — same visible rows as Export CSV, but with
     * the transaction/sub-request tree preserved visually (bold+filled parent rows,
     * indented children) and SLA-breaching rows highlighted red, matching the table.
     */
    private void exportSnapshotXlsx() {
        JFileChooser chooser = newProjectFileChooser();
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Excel Workbook (*.xlsx)", "xlsx"));
        chooser.setSelectedFile(new File("dashboard-snapshot-" + System.currentTimeMillis() + ".xlsx"));
        int choice = chooser.showSaveDialog(this);
        if (choice != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (!file.getName().toLowerCase().endsWith(".xlsx")) {
            file = new File(file.getParentFile(), file.getName() + ".xlsx");
        }

        List<XlsxExporter.ExportRow> exportRows = new ArrayList<>();
        for (Row r : buildCurrentFullRows()) {
            boolean breach = slaThresholdMs != null && r.p95 > slaThresholdMs;
            exportRows.add(new XlsxExporter.ExportRow(r.label, r.level, breach,
                    r.samples, r.errors, r.errorPct, r.avg, r.min, r.max, r.p90, r.p95, r.p99, r.throughput));
        }

        try {
            XlsxExporter.write(file, selectedLabel, slaThresholdMs, exportRows);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(parentFrame(), "Failed to write Excel file: " + e.getMessage(),
                    "Export failed", JOptionPane.ERROR_MESSAGE);
            return;
        }
        JOptionPane.showMessageDialog(parentFrame(), "Snapshot exported to:\n" + file.getAbsolutePath(),
                "Export complete", JOptionPane.INFORMATION_MESSAGE);
    }

    /** Thread-safe accumulator for one sampler label, including a bounded reservoir sample for percentiles. */
    private static final class LabelStats {
        // Bounded so memory doesn't grow unbounded on long/high-volume runs
        // (this was built against a 100k+ sample run); reservoir sampling
        // (Algorithm R) keeps a uniformly-random subset so percentiles
        // computed from it are a statistically valid estimate.
        private static final int RESERVOIR_SIZE = 2000;

        private final LongAdder count = new LongAdder();
        private final LongAdder errors = new LongAdder();
        private final LongAdder sumTime = new LongAdder();
        private volatile long minTime = Long.MAX_VALUE;
        private volatile long maxTime = Long.MIN_VALUE;
        private volatile long firstTimestamp = Long.MAX_VALUE;
        private volatile long lastTimestamp = Long.MIN_VALUE;
        private final long[] reservoir = new long[RESERVOIR_SIZE];
        private int reservoirCount = 0;
        private final AtomicLong seenCount = new AtomicLong();

        void add(SampleResult result) {
            long t = result.getTime();
            count.increment();
            if (!result.isSuccessful()) {
                errors.increment();
            }
            sumTime.add(t);
            // seenCount gives a unique, sequential 1-based index per sample
            // even under concurrent access, which count.sum() (an eventually
            // consistent aggregate) cannot guarantee — required for
            // unbiased reservoir sampling.
            long idx = seenCount.incrementAndGet();
            synchronized (this) {
                if (t < minTime) minTime = t;
                if (t > maxTime) maxTime = t;
                long start = result.getStartTime();
                long end = result.getEndTime();
                if (start < firstTimestamp) firstTimestamp = start;
                if (end > lastTimestamp) lastTimestamp = end;
                if (idx <= RESERVOIR_SIZE) {
                    reservoir[(int) (idx - 1)] = t;
                    reservoirCount = (int) idx;
                } else {
                    long r = ThreadLocalRandom.current().nextLong(idx);
                    if (r < RESERVOIR_SIZE) {
                        reservoir[(int) r] = t;
                    }
                }
            }
        }

        double avgSafe() {
            long c = count.sum();
            return c == 0 ? 0 : (double) sumTime.sum() / c;
        }

        /** Requests/sec over this label's own active window (first sample start to last sample end),
         *  matching how JMeter's built-in Aggregate Report computes per-label throughput. */
        double throughputSafe() {
            long n = count.sum();
            if (n == 0 || firstTimestamp == Long.MAX_VALUE) {
                return 0;
            }
            double windowSec = Math.max(1, (lastTimestamp - firstTimestamp)) / 1000.0;
            return n / windowSec;
        }

        synchronized long[] sortedReservoirSnapshot() {
            long[] snapshot = Arrays.copyOf(reservoir, reservoirCount);
            Arrays.sort(snapshot);
            return snapshot;
        }
    }

    /** Nearest-rank percentile from an already-sorted array. */
    private static long percentileOf(long[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        idx = Math.max(0, Math.min(sorted.length - 1, idx));
        return sorted[idx];
    }

    /** Thread-safe accumulator for one calendar second (drives the charts, including the thread-count overlay). */
    private static final class SecondBucket {
        private final LongAdder count = new LongAdder();
        private final LongAdder sumTime = new LongAdder();
        private final LongAdder errorCount = new LongAdder();
        private volatile int lastThreadCount;

        void add(long t, int activeThreads, boolean isError) {
            count.increment();
            sumTime.add(t);
            if (isError) {
                errorCount.increment();
            }
            lastThreadCount = activeThreads;
        }

        double avg() {
            long c = count.sum();
            return c == 0 ? 0 : (double) sumTime.sum() / c;
        }

        double errorRatePct() {
            long c = count.sum();
            return c == 0 ? 0 : 100.0 * errorCount.sum() / c;
        }
    }

    /** One rendered row: either a top-level transaction (level 0) or a nested sub-request (level 1). */
    private static final class Row {
        final String label;
        final String parentLabel; // null for a top-level row; the parent transaction's label for a child row
        final int level;
        final boolean hasChildren;
        final boolean expanded;
        final long samples;
        final long errors;
        final double errorPct;
        final double avg;
        final long min;
        final long max;
        final long p90;
        final long p95;
        final long p99;
        final double throughput;

        Row(String label, String parentLabel, int level, boolean hasChildren, boolean expanded,
            long samples, long errors, double errorPct, double avg, long min, long max,
            long p90, long p95, long p99, double throughput) {
            this.label = label;
            this.parentLabel = parentLabel;
            this.level = level;
            this.hasChildren = hasChildren;
            this.expanded = expanded;
            this.samples = samples;
            this.errors = errors;
            this.errorPct = errorPct;
            this.avg = avg;
            this.min = min;
            this.max = max;
            this.p90 = p90;
            this.p95 = p95;
            this.p99 = p99;
            this.throughput = throughput;
        }
    }

    /** Snapshot of one transaction's stats, used only while filtering/sorting during rebuild(). */
    private static final class ParentSnapshot {
        final String label;
        final long samples;
        final long errors;
        final double errorPct;
        final double avg;
        final long min;
        final long max;
        final long p90;
        final long p95;
        final long p99;
        final double throughput;

        ParentSnapshot(String label, long samples, long errors, double errorPct, double avg,
                       long min, long max, long p90, long p95, long p99, double throughput) {
            this.label = label;
            this.samples = samples;
            this.errors = errors;
            this.errorPct = errorPct;
            this.avg = avg;
            this.min = min;
            this.max = max;
            this.p90 = p90;
            this.p95 = p95;
            this.p99 = p99;
            this.throughput = throughput;
        }
    }

    /** Backs the per-label live table: filtering, sorting, percentiles, and tree expand/collapse. */
    private static final class LabelTableModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private static final String[] COLUMNS = {
                "Label", "Samples", "Errors", "Error %", "Avg (ms)", "Min (ms)", "Max (ms)",
                "P90 (ms)", "P95 (ms)", "P99 (ms)", "Throughput (req/s)"
        };

        // Mutated only on the EDT (mouse listener + refreshUi both run there).
        private final Set<String> expandedLabels = new HashSet<>();
        private volatile List<Row> rows = Collections.emptyList();

        void toggleExpanded(String label) {
            if (!expandedLabels.remove(label)) {
                expandedLabels.add(label);
            }
        }

        Row getRowAt(int row) {
            List<Row> snapshot = rows;
            return row >= 0 && row < snapshot.size() ? snapshot.get(row) : null;
        }

        /** First row (by current display order) matching this label, or -1. Used to restore
         *  table selection after rebuild() replaces the row list (which clears selection). */
        int indexOfLabel(String label) {
            if (label == null) {
                return -1;
            }
            List<Row> snapshot = rows;
            for (int i = 0; i < snapshot.size(); i++) {
                if (label.equals(snapshot.get(i).label)) {
                    return i;
                }
            }
            return -1;
        }

        void rebuild(Map<String, LabelStats> statsByLabel,
                     Map<String, Map<String, LabelStats>> childStatsByParent,
                     String filterText, Double minAvgMs, boolean errorsOnly, String sortKey, boolean ascending) {

            List<ParentSnapshot> snapshots = new ArrayList<>();
            for (Map.Entry<String, LabelStats> e : statsByLabel.entrySet()) {
                String label = e.getKey();
                if (filterText != null && !filterText.isEmpty()
                        && !label.toLowerCase().contains(filterText.toLowerCase())) {
                    continue;
                }
                LabelStats s = e.getValue();
                long n = s.count.sum();
                long errCount = s.errors.sum();
                double avg = n == 0 ? 0 : (double) s.sumTime.sum() / n;
                if (minAvgMs != null && avg < minAvgMs) {
                    continue;
                }
                if (errorsOnly && errCount == 0 && !anyChildHasErrors(childStatsByParent.get(label))) {
                    continue; // neither this row nor any of its sub-requests failed - nothing to show here
                }
                double errPct = n == 0 ? 0 : 100.0 * errCount / n;
                long min = s.minTime == Long.MAX_VALUE ? 0 : s.minTime;
                long max = s.maxTime == Long.MIN_VALUE ? 0 : s.maxTime;
                long[] sorted = s.sortedReservoirSnapshot();
                long p90 = percentileOf(sorted, 90);
                long p95 = percentileOf(sorted, 95);
                long p99 = percentileOf(sorted, 99);
                double throughput = s.throughputSafe();
                snapshots.add(new ParentSnapshot(label, n, errCount, errPct, avg, min, max, p90, p95, p99, throughput));
            }

            Comparator<ParentSnapshot> comparator = comparatorFor(sortKey);
            snapshots.sort(ascending ? comparator : comparator.reversed());

            List<Row> newRows = new ArrayList<>();
            for (ParentSnapshot p : snapshots) {
                Map<String, LabelStats> children = childStatsByParent.get(p.label);
                boolean hasChildren = children != null && !children.isEmpty();
                boolean expanded = expandedLabels.contains(p.label);
                newRows.add(new Row(p.label, null, 0, hasChildren, expanded,
                        p.samples, p.errors, p.errorPct, p.avg, p.min, p.max, p.p90, p.p95, p.p99, p.throughput));

                if (hasChildren && expanded) {
                    List<String> childLabels = new ArrayList<>(children.keySet());
                    childLabels.sort((a, b) -> Long.compare(
                            children.get(a).firstTimestamp, children.get(b).firstTimestamp));
                    for (String cl : childLabels) {
                        LabelStats cs = children.get(cl);
                        long cn = cs.count.sum();
                        long ce = cs.errors.sum();
                        if (errorsOnly && ce == 0) {
                            continue; // this particular sub-request didn't fail - only its siblings that did matter here
                        }
                        double cavg = cn == 0 ? 0 : (double) cs.sumTime.sum() / cn;
                        double cerrPct = cn == 0 ? 0 : 100.0 * ce / cn;
                        long cmin = cs.minTime == Long.MAX_VALUE ? 0 : cs.minTime;
                        long cmax = cs.maxTime == Long.MIN_VALUE ? 0 : cs.maxTime;
                        long[] csorted = cs.sortedReservoirSnapshot();
                        long cp90 = percentileOf(csorted, 90);
                        long cp95 = percentileOf(csorted, 95);
                        long cp99 = percentileOf(csorted, 99);
                        double cthroughput = cs.throughputSafe();
                        newRows.add(new Row(cl, p.label, 1, false, false, cn, ce, cerrPct, cavg, cmin, cmax,
                                cp90, cp95, cp99, cthroughput));
                    }
                }
            }
            this.rows = newRows;
            fireTableDataChanged();
        }

        /** Same filter/sort logic as rebuild() above, but always includes every child row
         *  regardless of whether its parent is currently expanded in the UI - for Export
         *  CSV/Excel and "Copy Table", neither of which should be limited to whatever
         *  happens to be currently visible/expanded on screen. Returns the list rather than
         *  mutating this model's own rows/firing a change event, so it has no effect on
         *  what's actually displayed. */
        List<Row> buildFullRows(Map<String, LabelStats> statsByLabel,
                                 Map<String, Map<String, LabelStats>> childStatsByParent,
                                 String filterText, Double minAvgMs, boolean errorsOnly,
                                 String sortKey, boolean ascending) {
            List<ParentSnapshot> snapshots = new ArrayList<>();
            for (Map.Entry<String, LabelStats> e : statsByLabel.entrySet()) {
                String label = e.getKey();
                if (filterText != null && !filterText.isEmpty()
                        && !label.toLowerCase().contains(filterText.toLowerCase())) {
                    continue;
                }
                LabelStats s = e.getValue();
                long n = s.count.sum();
                long errCount = s.errors.sum();
                double avg = n == 0 ? 0 : (double) s.sumTime.sum() / n;
                if (minAvgMs != null && avg < minAvgMs) {
                    continue;
                }
                if (errorsOnly && errCount == 0 && !anyChildHasErrors(childStatsByParent.get(label))) {
                    continue;
                }
                double errPct = n == 0 ? 0 : 100.0 * errCount / n;
                long min = s.minTime == Long.MAX_VALUE ? 0 : s.minTime;
                long max = s.maxTime == Long.MIN_VALUE ? 0 : s.maxTime;
                long[] sorted = s.sortedReservoirSnapshot();
                long p90 = percentileOf(sorted, 90);
                long p95 = percentileOf(sorted, 95);
                long p99 = percentileOf(sorted, 99);
                double throughput = s.throughputSafe();
                snapshots.add(new ParentSnapshot(label, n, errCount, errPct, avg, min, max, p90, p95, p99, throughput));
            }

            Comparator<ParentSnapshot> comparator = comparatorFor(sortKey);
            snapshots.sort(ascending ? comparator : comparator.reversed());

            List<Row> fullRows = new ArrayList<>();
            for (ParentSnapshot p : snapshots) {
                Map<String, LabelStats> children = childStatsByParent.get(p.label);
                boolean hasChildren = children != null && !children.isEmpty();
                boolean expanded = expandedLabels.contains(p.label); // kept accurate on the row itself
                fullRows.add(new Row(p.label, null, 0, hasChildren, expanded,
                        p.samples, p.errors, p.errorPct, p.avg, p.min, p.max, p.p90, p.p95, p.p99, p.throughput));

                if (hasChildren) { // the one real difference from rebuild(): no "&& expanded" here
                    List<String> childLabels = new ArrayList<>(children.keySet());
                    childLabels.sort((a, b) -> Long.compare(
                            children.get(a).firstTimestamp, children.get(b).firstTimestamp));
                    for (String cl : childLabels) {
                        LabelStats cs = children.get(cl);
                        long cn = cs.count.sum();
                        long ce = cs.errors.sum();
                        if (errorsOnly && ce == 0) {
                            continue;
                        }
                        double cavg = cn == 0 ? 0 : (double) cs.sumTime.sum() / cn;
                        double cerrPct = cn == 0 ? 0 : 100.0 * ce / cn;
                        long cmin = cs.minTime == Long.MAX_VALUE ? 0 : cs.minTime;
                        long cmax = cs.maxTime == Long.MIN_VALUE ? 0 : cs.maxTime;
                        long[] csorted = cs.sortedReservoirSnapshot();
                        long cp90 = percentileOf(csorted, 90);
                        long cp95 = percentileOf(csorted, 95);
                        long cp99 = percentileOf(csorted, 99);
                        double cthroughput = cs.throughputSafe();
                        fullRows.add(new Row(cl, p.label, 1, false, false, cn, ce, cerrPct, cavg, cmin, cmax,
                                cp90, cp95, cp99, cthroughput));
                    }
                }
            }
            return fullRows;
        }

        private static boolean anyChildHasErrors(Map<String, LabelStats> children) {
            if (children == null) {
                return false;
            }
            for (LabelStats cs : children.values()) {
                if (cs.errors.sum() > 0) {
                    return true;
                }
            }
            return false;
        }

        private static Comparator<ParentSnapshot> comparatorFor(String sortKey) {
            if (sortKey == null) {
                sortKey = "Avg (ms)";
            }
            switch (sortKey) {
                case "Samples": return Comparator.comparingLong(p -> p.samples);
                case "Errors": return Comparator.comparingLong(p -> p.errors);
                case "Error %": return Comparator.comparingDouble(p -> p.errorPct);
                case "Min (ms)": return Comparator.comparingLong(p -> p.min);
                case "Max (ms)": return Comparator.comparingLong(p -> p.max);
                case "P90 (ms)": return Comparator.comparingLong(p -> p.p90);
                case "P95 (ms)": return Comparator.comparingLong(p -> p.p95);
                case "P99 (ms)": return Comparator.comparingLong(p -> p.p99);
                case "Throughput (req/s)": return Comparator.comparingDouble(p -> p.throughput);
                case "Label": return Comparator.comparing(p -> p.label, String.CASE_INSENSITIVE_ORDER);
                case "Avg (ms)":
                default: return Comparator.comparingDouble(p -> p.avg);
            }
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int column) { return COLUMNS[column]; }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Row r = rows.get(rowIndex);
            switch (columnIndex) {
                case 0: return r.label;
                case 1: return r.samples;
                case 2: return r.errors;
                case 3: return String.format("%.2f", r.errorPct);
                case 4: return String.format("%.0f", r.avg);
                case 5: return r.min;
                case 6: return r.max;
                case 7: return r.p90;
                case 8: return r.p95;
                case 9: return r.p99;
                case 10: return String.format("%.2f", r.throughput);
                default: return "";
            }
        }
    }

    /** A small filled circle \u2014 the status dot shown per row, rather than a
     *  unicode checkmark/cross glyph, which renders inconsistently (a missing-glyph
     *  box on some fonts/platforms) where a plain colored dot never can. */
    private static final class StatusDotIcon implements Icon {
        private static final int SIZE = 9;
        private final Color color;

        StatusDotIcon(Color color) {
            this.color = color;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillOval(x, y + 2, SIZE, SIZE);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE + 2;
        }
    }

    /** The label text to actually display for a row - a child row's redundantly repeated
     *  parent-name prefix stripped off (the tree's own indentation already shows which
     *  parent it belongs to), the full original label used as-is for a top-level row, or
     *  for a child whose label doesn't actually start with its parent's (an unexpected
     *  naming convention shouldn't silently hide real content). Shared by every place that
     *  needs to show or measure this text - the live table's own cell renderer, its column
     *  auto-fit-width calculation, and the plain-text results summary - so none of them can
     *  drift out of sync with how the others display the same row. */
    private static String displayLabelFor(Row meta) {
        if (meta.level != 0 && meta.parentLabel != null && !meta.parentLabel.isEmpty()
                && meta.label.startsWith(meta.parentLabel)) {
            String remainder = meta.label.substring(meta.parentLabel.length()).trim();
            if (!remainder.isEmpty()) {
                return remainder;
            }
        }
        return meta.label;
    }

    /** Renders the Label column with tree indentation, an expand/collapse marker,
     *  and a red/green status dot \u2014 red whenever this row (or, for a collapsed
     *  parent, any of its children) has at least one error, green once it has
     *  samples and none; no dot yet for a row with no samples at all. */
    private static final class LabelCellRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;
        private final LabelTableModel model;

        LabelCellRenderer(LabelTableModel model) {
            this.model = model;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                        boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            Row meta = model.getRowAt(row);
            if (meta != null) {
                String prefix;
                int indent;
                if (meta.level == 0) {
                    indent = 4;
                    prefix = meta.hasChildren ? (meta.expanded ? "\u25BC " : "\u25B6 ") : "    ";
                } else {
                    indent = 28;
                    prefix = "\u2514 ";
                }
                String displayLabel = displayLabelFor(meta);
                setText(prefix + displayLabel);
                setToolTipText(displayLabel.equals(meta.label) ? null : meta.label);
                setBorder(BorderFactory.createEmptyBorder(0, indent, 0, 4));
                setFont(getFont().deriveFont(meta.level == 0 ? Font.BOLD : Font.PLAIN, 13f));
                boolean dark = isDark(c.getBackground());
                if (meta.samples <= 0) {
                    setIcon(null);
                } else if (meta.errors > 0) {
                    setIcon(new StatusDotIcon(dark ? STATUS_FAIL_DARK : STATUS_FAIL_LIGHT));
                } else {
                    setIcon(new StatusDotIcon(dark ? STATUS_OK_DARK : STATUS_OK_LIGHT));
                }
                setIconTextGap(6);
            }
            return c;
        }
    }

    /**
     * A minimal, self-contained rolling line chart — no external charting
     * library required. Supports an optional secondary "overlay" series
     * (drawn against its own right-hand axis, used here for active thread
     * count) and an optional dashed SLA threshold line on the primary axis.
     */
    private static final class ChartPanel extends JPanel {
        private static final long serialVersionUID = 1L;

        private static final int LEFT_PAD = 55;
        private static final int RIGHT_PAD = 50; // room for the overlay's right-hand axis labels
        private static final int TOP_PAD = 30;
        private static final int BOTTOM_PAD = 24;
        private static final int GRID_LINES = 4;
        private static final Color SLA_COLOR = new Color(0xe5, 0x39, 0x35);

        private final String seriesLabel;
        private final Color lineColor;
        private final Color gridColor;
        private final Color axisTextColor;
        private final Color mutedTextColor;
        private volatile List<Double> data = Collections.emptyList();
        private volatile List<Double> overlayData = Collections.emptyList();
        private volatile String overlayLabel;
        private volatile Color overlayColor;
        private volatile Double threshold;
        private volatile String thresholdLabel;
        private volatile String contextSuffix = "";

        ChartPanel(String seriesLabel, Color lineColor) {
            this.seriesLabel = seriesLabel;
            this.lineColor = lineColor;
            Color panelBg = CustomDashboardVisualizer.uiColor("Panel.background", Color.WHITE);
            boolean dark = CustomDashboardVisualizer.isDark(panelBg);
            this.gridColor = dark ? new Color(255, 255, 255, 25) : new Color(0, 0, 0, 20);
            this.axisTextColor = dark ? new Color(210, 210, 210) : Color.DARK_GRAY;
            this.mutedTextColor = dark ? new Color(150, 150, 150) : Color.GRAY;
            setPreferredSize(new Dimension(400, 170));
            setBackground(panelBg);
        }

        void setData(List<Double> data) {
            this.data = data;
            repaint();
        }

        void setOverlay(List<Double> data, String label, Color color) {
            this.overlayData = data;
            this.overlayLabel = label;
            this.overlayColor = color;
            repaint();
        }

        void setThreshold(Double value, String label) {
            this.threshold = value;
            this.thresholdLabel = label;
            repaint();
        }

        void setContextSuffix(String suffix) {
            this.contextSuffix = suffix == null ? "" : suffix;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            int plotLeft = LEFT_PAD;
            int plotRight = w - RIGHT_PAD;
            int plotTop = TOP_PAD;
            int plotBottom = h - BOTTOM_PAD;
            int plotW = plotRight - plotLeft;
            int plotH = plotBottom - plotTop;

            List<Double> d = data;
            double max = 1;
            for (double v : d) {
                if (v > max) max = v;
            }
            if (threshold != null && threshold > max) {
                max = threshold;
            }
            max = niceCeiling(max);

            drawYAxis(g2, plotLeft, plotTop, plotH, plotW, max);

            if (d.size() < 2) {
                g2.setColor(mutedTextColor);
                g2.drawString("Waiting for samples...", plotLeft + 10, plotTop + plotH / 2);
                drawLegend(g2);
                return;
            }

            drawXAxis(g2, plotLeft, plotBottom, plotW, d.size());
            drawSeries(g2, plotLeft, plotTop, plotW, plotH, d, max, lineColor, 2f);
            drawOverlay(g2, plotLeft, plotTop, plotW, plotH);
            drawThresholdLine(g2, plotLeft, plotTop, plotW, plotH, max);
            drawLegend(g2);
        }

        private void drawSeries(Graphics2D g2, int plotLeft, int plotTop, int plotW, int plotH,
                                 List<Double> series, double max, Color color, float strokeWidth) {
            int n = series.size();
            g2.setColor(color);
            g2.setStroke(new BasicStroke(strokeWidth));
            Path2D path = new Path2D.Double();
            for (int i = 0; i < n; i++) {
                double x = plotLeft + (double) i / (n - 1) * plotW;
                double y = plotTop + plotH - (series.get(i) / max) * plotH;
                if (i == 0) {
                    path.moveTo(x, y);
                } else {
                    path.lineTo(x, y);
                }
            }
            g2.draw(path);
        }

        private void drawOverlay(Graphics2D g2, int plotLeft, int plotTop, int plotW, int plotH) {
            List<Double> od = overlayData;
            if (overlayColor == null || od.size() < 2) {
                return;
            }
            double overlayMax = 1;
            for (double v : od) {
                if (v > overlayMax) overlayMax = v;
            }
            overlayMax = niceCeiling(overlayMax);

            g2.setColor(overlayColor);
            for (int i = 0; i <= GRID_LINES; i++) {
                int y = plotTop + i * plotH / GRID_LINES;
                double value = overlayMax * (GRID_LINES - i) / GRID_LINES;
                String label = formatAxisValue(value);
                g2.drawString(label, plotLeft + plotW + 8, y + 4);
            }

            drawSeries(g2, plotLeft, plotTop, plotW, plotH, od, overlayMax, overlayColor, 1.5f);
        }

        private void drawThresholdLine(Graphics2D g2, int plotLeft, int plotTop, int plotW, int plotH, double max) {
            if (threshold == null) {
                return;
            }
            double clamped = Math.min(threshold, max);
            int y = plotTop + plotH - (int) Math.round((clamped / max) * plotH);
            g2.setColor(SLA_COLOR);
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    10f, new float[] {6f, 4f}, 0f));
            g2.drawLine(plotLeft, y, plotLeft + plotW, y);

            String text = (thresholdLabel != null ? thresholdLabel : "Threshold") + ": " + formatAxisValue(threshold);
            g2.setStroke(new BasicStroke(1f));
            int textWidth = g2.getFontMetrics().stringWidth(text);
            g2.drawString(text, plotLeft + plotW - textWidth - 4, Math.max(plotTop + 12, y - 4));
        }

        /** Color swatch(es) + series name(s), top-left. */
        private void drawLegend(Graphics2D g2) {
            int x = 10;
            int y = 14;
            x = drawLegendEntry(g2, x, y, lineColor, seriesLabel + contextSuffix);
            if (overlayColor != null && overlayLabel != null && !overlayData.isEmpty()) {
                drawLegendEntry(g2, x + 16, y, overlayColor, overlayLabel);
            }
        }

        private int drawLegendEntry(Graphics2D g2, int x, int y, Color color, String label) {
            g2.setColor(color);
            g2.setStroke(new BasicStroke(3f));
            g2.drawLine(x, y, x + 18, y);
            g2.setColor(axisTextColor);
            g2.drawString(label, x + 24, y + 4);
            return x + 24 + g2.getFontMetrics().stringWidth(label);
        }

        /** Horizontal gridlines with numeric value labels on the left. */
        private void drawYAxis(Graphics2D g2, int plotLeft, int plotTop, int plotH, int plotW, double max) {
            for (int i = 0; i <= GRID_LINES; i++) {
                int y = plotTop + i * plotH / GRID_LINES;
                double value = max * (GRID_LINES - i) / GRID_LINES;

                g2.setColor(gridColor);
                g2.drawLine(plotLeft, y, plotLeft + plotW, y);

                g2.setColor(mutedTextColor);
                String label = formatAxisValue(value);
                int labelWidth = g2.getFontMetrics().stringWidth(label);
                g2.drawString(label, plotLeft - labelWidth - 6, y + 4);
            }
        }

        /** "Seconds ago" labels along the bottom: oldest point on the left, "now" on the right. */
        private void drawXAxis(Graphics2D g2, int plotLeft, int plotBottom, int plotW, int pointCount) {
            int secondsSpan = pointCount - 1;
            int ticks = Math.min(5, pointCount);
            g2.setColor(mutedTextColor);
            for (int t = 0; t < ticks; t++) {
                double fraction = ticks == 1 ? 0 : (double) t / (ticks - 1);
                int x = plotLeft + (int) Math.round(fraction * plotW);
                int secondsAgo = (int) Math.round(secondsSpan * (1 - fraction));
                String label = secondsAgo == 0 ? "now" : "-" + secondsAgo + "s";
                int labelWidth = g2.getFontMetrics().stringWidth(label);
                int labelX = t == ticks - 1 ? x - labelWidth : (t == 0 ? x : x - labelWidth / 2);
                g2.drawString(label, labelX, plotBottom + 16);
            }
        }

        private static String formatAxisValue(double value) {
            if (value >= 100) {
                return String.format("%.0f", value);
            } else if (value >= 10) {
                return String.format("%.1f", value);
            } else {
                return String.format("%.2f", value);
            }
        }

        /** Rounds up to a visually clean axis maximum (1/2/5 * 10^n), same idea most charting libs use. */
        private static double niceCeiling(double value) {
            if (value <= 0) {
                return 1;
            }
            double magnitude = Math.pow(10, Math.floor(Math.log10(value)));
            double normalized = value / magnitude;
            double niceNormalized;
            if (normalized <= 1) {
                niceNormalized = 1;
            } else if (normalized <= 2) {
                niceNormalized = 2;
            } else if (normalized <= 5) {
                niceNormalized = 5;
            } else {
                niceNormalized = 10;
            }
            return niceNormalized * magnitude;
        }
    }

    /**
     * A minimal horizontal bar chart of response/error code counts — no
     * external charting library required, same approach as ChartPanel.
     * Colors by HTTP status class (2xx/3xx/4xx/5xx); non-HTTP codes (e.g.
     * connection errors) get a distinct color since they don't fit that
     * scheme.
     */
    private static final class CodeBreakdownPanel extends JPanel {
        private static final long serialVersionUID = 1L;

        private final Color textColor;
        private final Color mutedTextColor;
        private volatile List<Map.Entry<String, Long>> entries = Collections.emptyList();
        private volatile String contextSuffix = "";

        CodeBreakdownPanel() {
            Color panelBg = CustomDashboardVisualizer.uiColor("Panel.background", Color.WHITE);
            boolean dark = CustomDashboardVisualizer.isDark(panelBg);
            this.textColor = dark ? new Color(210, 210, 210) : Color.DARK_GRAY;
            this.mutedTextColor = dark ? new Color(150, 150, 150) : Color.GRAY;
            setBackground(panelBg);
            setPreferredSize(new Dimension(400, 170));
            setToolTipText(""); // registers this component with ToolTipManager so getToolTipText(MouseEvent) is consulted
        }

        void setData(List<Map.Entry<String, Long>> entries) {
            this.entries = entries;
            repaint();
        }

        void setContextSuffix(String suffix) {
            this.contextSuffix = suffix == null ? "" : suffix;
            repaint();
        }

        /** Row layout is deterministic from entries + panel height, so this recomputes
         *  the same geometry paintComponent uses, rather than caching hit-regions. */
        @Override
        public String getToolTipText(java.awt.event.MouseEvent event) {
            List<Map.Entry<String, Long>> d = entries;
            if (d.isEmpty()) {
                return null;
            }
            int top = 28;
            int available = getHeight() - top - 6;
            int barHeight = Math.max(12, Math.min(20, available / d.size()));
            int idx = (event.getY() - top) / barHeight;
            if (idx < 0 || idx >= d.size()) {
                return null;
            }
            Map.Entry<String, Long> e = d.get(idx);
            return e.getKey() + "  (" + e.getValue() + ")";
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            g2.setColor(textColor);
            g2.drawString("Response / Error Codes" + contextSuffix, 10, 16);

            List<Map.Entry<String, Long>> d = entries;
            if (d.isEmpty()) {
                g2.setColor(mutedTextColor);
                g2.drawString("Waiting for samples...", 10, 40);
                return;
            }

            long max = 1;
            for (Map.Entry<String, Long> e : d) {
                if (e.getValue() > max) {
                    max = e.getValue();
                }
            }

            int top = 28;
            int available = getHeight() - top - 6;
            int barHeight = Math.max(12, Math.min(20, available / d.size()));
            // Scale with the panel's actual width now that resizing works,
            // instead of a fixed pixel count that cut off the very text that
            // distinguishes two similar codes (e.g. two different "Non HTTP
            // response..." failures).
            int labelAreaWidth = Math.max(140, Math.min(440, (int) (getWidth() * 0.45)));
            int barAreaLeft = labelAreaWidth;
            int barAreaWidth = Math.max(20, getWidth() - barAreaLeft - 55);

            int y = top;
            for (Map.Entry<String, Long> e : d) {
                String code = e.getKey();
                long count = e.getValue();
                Color barColor = colorForCode(code);

                g2.setColor(textColor);
                drawTruncated(g2, code, 2, y + barHeight - 6, labelAreaWidth - 6);

                int barW = Math.max(2, (int) Math.round((double) count / max * barAreaWidth));
                g2.setColor(barColor);
                g2.fillRect(barAreaLeft, y, barW, Math.max(6, barHeight - 6));

                g2.setColor(mutedTextColor);
                g2.drawString(String.valueOf(count), barAreaLeft + barW + 6, y + barHeight - 6);

                y += barHeight;
                if (y > getHeight()) {
                    break;
                }
            }
        }

        private static void drawTruncated(Graphics2D g2, String text, int x, int y, int maxWidth) {
            java.awt.FontMetrics fm = g2.getFontMetrics();
            if (fm.stringWidth(text) <= maxWidth) {
                g2.drawString(text, x, y);
                return;
            }
            String truncated = text;
            while (truncated.length() > 1 && fm.stringWidth(truncated + "\u2026") > maxWidth) {
                truncated = truncated.substring(0, truncated.length() - 1);
            }
            g2.drawString(truncated + "\u2026", x, y);
        }

        private static Color colorForCode(String code) {
            if (code.startsWith("2")) {
                return new Color(0x43, 0xa0, 0x47); // 2xx: success, green
            } else if (code.startsWith("3")) {
                return new Color(0x1e, 0x88, 0xe5); // 3xx: redirect, blue
            } else if (code.startsWith("4")) {
                return new Color(0xfb, 0x8c, 0x00); // 4xx: client error, orange
            } else if (code.startsWith("5")) {
                return new Color(0xc6, 0x28, 0x28); // 5xx: server error, red
            } else {
                return new Color(0x8e, 0x24, 0xaa); // non-HTTP failure / other, purple
            }
        }
    }
}
