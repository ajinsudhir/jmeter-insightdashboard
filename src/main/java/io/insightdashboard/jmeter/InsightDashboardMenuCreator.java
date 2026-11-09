package io.insightdashboard.jmeter;

import java.awt.Color;
import java.awt.Component;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JToolBar;
import javax.swing.JTree;
import javax.swing.MenuElement;
import javax.swing.tree.TreePath;

import org.apache.jmeter.exceptions.IllegalUserActionException;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.plugin.MenuCreator;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;

/**
 * Adds a best-effort icon on JMeter's own main toolbar - no Tools menu
 * entry, removed by direct request since the icon alone already does the
 * exact same thing in one click, which made a menu entry pure redundancy -
 * as a faster way to add this plugin's own listener to a test plan than
 * navigating JMeter's own right-click Add \u2192 Listener \u2192 ... menu each
 * time, for a user who already knows they want this specific listener.
 *
 * Unlike this project's siblings (Correlation Studio / JMeter Workbench and
 * MCP Bridge), which open a self-contained dialog from their own menu item
 * and toolbar button, Insight Dashboard is a {@code Listener} GUI component
 * ({@link CustomDashboardVisualizer} extends {@code AbstractVisualizer})
 * meant to be added as a node in the test plan tree, not opened as a
 * dialog. So "quick access" here means inserting that node under whatever's
 * currently selected, using the same documented, public API JMeter's own
 * tree-manipulation code uses -
 * {@link JMeterTreeModel#addComponent(TestElement, JMeterTreeNode)} - not
 * reflection or an undocumented workaround.
 *
 * The toolbar icon itself uses the same workaround as the sibling plugins'
 * own toolbar buttons do, since JMeter has no official SPI for third-party
 * toolbar buttons: {@code GuiPackage.getInstance().getMainToolbar()}, the
 * actual, live {@code JToolBar} instance, with a button appended directly.
 * Wrapped defensively, since this reads JMeter's own internal GUI object
 * rather than a contract JMeter promises to keep stable - if it ever breaks
 * in a future JMeter release, this plugin simply has no toolbar presence,
 * with no menu item left as a fallback (an intentional tradeoff, made with
 * the removal above).
 */
public class InsightDashboardMenuCreator implements MenuCreator {

    private static volatile boolean toolbarButtonAdded;

    @Override
    public JMenuItem[] getMenuItemsAtLocation(MENU_LOCATION location) {
        if (location != MENU_LOCATION.TOOLS) {
            return new JMenuItem[0];
        }
        // No Tools menu item here, by direct request - the toolbar button below does the
        // exact same thing in one click, so a menu entry too was pure redundancy in an
        // already-growing "SmartBlitz -" section shared with this author's other plugins.
        // getMenuItemsAtLocation() is still the right place to trigger the toolbar button,
        // since it's JMeter's own call into this plugin during menu construction.
        addToolbarButtonOnce();

        return new JMenuItem[0];
    }

    /** Adds a new Insight Dashboard listener as a child of whichever node is currently
     *  selected in the test plan tree, via the same public, documented API
     *  (JMeterTreeModel#addComponent) JMeter's own tree-manipulation code uses. Selects
     *  the result and expands its parent afterward, so the addition is immediately visible
     *  rather than leaving the user to go find it in a possibly-collapsed branch. */
    private void addToTree() {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return;
        }
        Frame owner = guiPackage.getMainFrame();
        JMeterTreeNode selectedNode = guiPackage.getTreeListener().getCurrentNode();
        if (selectedNode == null) {
            JOptionPane.showMessageDialog(owner, "Select a node in the test plan tree first "
                    + "(a Thread Group or a sampler, say), then try again.",
                    "Nothing Selected", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        try {
            TestElement element = CustomDashboardVisualizer.createStandaloneTestElement();
            JMeterTreeModel treeModel = guiPackage.getTreeModel();
            JMeterTreeNode newNode = treeModel.addComponent(element, selectedNode);

            JTree tree = guiPackage.getMainFrame().getTree();
            tree.expandPath(new TreePath(selectedNode.getPath()));
            TreePath newPath = new TreePath(newNode.getPath());
            tree.setSelectionPath(newPath);
            tree.scrollPathToVisible(newPath);
        } catch (IllegalUserActionException ex) {
            JOptionPane.showMessageDialog(owner, "Couldn't add Insight Dashboard there: "
                    + ex.getMessage() + "\n\nTry selecting a Thread Group or a sampler instead.",
                    "Couldn't Add", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** Best-effort only, and guarded to run exactly once - getMenuItemsAtLocation() is
     *  called once per MENU_LOCATION value, and would otherwise append a duplicate button
     *  to the toolbar each time it's called. Tolerates GuiPackage or the toolbar not being
     *  available by simply not adding the button, rather than letting any of this
     *  interfere with menu construction itself. */
    private void addToolbarButtonOnce() {
        if (toolbarButtonAdded) {
            return;
        }
        toolbarButtonAdded = true; // claim it now regardless of outcome - this only ever gets one attempt
        javax.swing.SwingUtilities.invokeLater(() -> {
        try {
            GuiPackage guiPackage = GuiPackage.getInstance();
            if (guiPackage == null) {
                return;
            }
            JToolBar toolbar = guiPackage.getMainToolbar();
            if (toolbar == null) {
                return;
            }
            JButton button = new JButton(new DashboardToolbarIcon());
            button.setToolTipText("SmartBlitz - Add Insight Dashboard to Selected Node");
            button.addActionListener(e -> addToTree());
            int insertIndex = findInsertionIndex(toolbar);
            toolbar.add(new JToolBar.Separator(), insertIndex);
            toolbar.add(button, insertIndex + 1);
            toolbar.revalidate();
            toolbar.repaint();
        } catch (Exception ignored) {
            // JMeter's internal toolbar structure didn't match what was expected here -
            // the Tools menu item is the guaranteed way in regardless.
        }
        });
    }

    /** Finds the index right after the last button-like component already in the toolbar
     *  (JMeter's own icons, or another of this author's plugins' toolbar button already
     *  inserted the same way) - so a new button lands grouped with the existing icons
     *  rather than appended past whatever flexible spacer JMeter places before its own
     *  far-right status indicators (elapsed time, warnings, thread count). Falls back to
     *  0 (the very start) if no button-like component is found at all, rather than
     *  throwing - an unusual toolbar layout shouldn't prevent the button from appearing
     *  somewhere. */
    private static int findInsertionIndex(JToolBar toolbar) {
        int lastButtonIndex = -1;
        for (int i = 0; i < toolbar.getComponentCount(); i++) {
            if (toolbar.getComponent(i) instanceof javax.swing.AbstractButton) {
                lastButtonIndex = i;
            }
        }
        return lastButtonIndex + 1;
    }

    /** A small, code-drawn toolbar icon (three bars, the same motif as this plugin's own
     *  branding bar) - no external image asset to bundle or load, which this environment
     *  has no way to actually compile-test, unlike a plain Java2D drawing routine that can
     *  be read and reasoned about directly. */
    private static final class DashboardToolbarIcon implements Icon {
        private static final Color BAR_1 = new Color(0x1e, 0x88, 0xe5);
        private static final Color BAR_2 = new Color(0x43, 0xa0, 0x47);
        private static final Color BAR_3 = new Color(0xfb, 0x8c, 0x00);

        @Override
        public int getIconWidth() {
            return 16;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.translate(x, y);
            g2.setColor(BAR_1);
            g2.fillRect(1, 9, 3, 6);
            g2.setColor(BAR_2);
            g2.fillRect(6, 5, 3, 10);
            g2.setColor(BAR_3);
            g2.fillRect(11, 1, 3, 14);
            g2.dispose();
        }
    }

    @Override
    public JMenu[] getTopLevelMenus() {
        return new JMenu[0];
    }

    @Override
    public boolean localeChanged(MenuElement menu) {
        return false;
    }

    @Override
    public void localeChanged() {
        // Static English labels only — nothing to update on locale change.
    }
}
