package io.insightdashboard.jmeter;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * A branding bar for this plugin's main panel - a simple, code-drawn icon
 * paired with a styled wordmark, shown once at the bottom of the whole
 * plugin (below the tabs, so it's visible regardless of which tab is
 * active), in the spirit of how commercial JMeter plugins mark their own
 * GUI panels (e.g. BlazeMeter's bzm samplers showing their logo at the
 * bottom of the sampler's configuration panel).
 *
 * The icon is drawn directly in Java2D rather than loaded from an image
 * file: three bars of increasing height, echoing the response-time/
 * throughput/error-rate charts this plugin is actually about, in the same
 * three accent colors already used for those charts elsewhere in this file.
 * This avoids bundling and loading an external image resource - something
 * this environment has no way to actually compile-test, unlike a plain
 * Java2D drawing routine that can be read and reasoned about directly.
 *
 * Version is read from the jar's own manifest (Maven populates this
 * automatically from pom.xml's &lt;version&gt;, so it can't drift out of
 * sync the way a hardcoded string here would), with a plain fallback for
 * the case where that's unavailable (e.g. running from an IDE's classes/
 * directory rather than the built jar, where there is no manifest to read).
 */
final class PluginBranding {

    private static final Color BAR_1 = new Color(0x1e, 0x88, 0xe5); // matches RESP_LINE
    private static final Color BAR_2 = new Color(0x43, 0xa0, 0x47); // matches TPS_LINE
    private static final Color BAR_3 = new Color(0xfb, 0x8c, 0x00); // a warm third accent

    private PluginBranding() {
    }

    static JPanel buildBrandBar() {
        String version = PluginBranding.class.getPackage().getImplementationVersion();
        String versionText = (version == null || version.isEmpty()) ? "" : "  v" + version;

        JPanel iconAndText = new JPanel();
        iconAndText.setLayout(new BoxLayout(iconAndText, BoxLayout.X_AXIS));
        iconAndText.add(new DashboardIcon());
        iconAndText.add(Box.createHorizontalStrut(8));

        JPanel textStack = new JPanel();
        textStack.setLayout(new BoxLayout(textStack, BoxLayout.Y_AXIS));
        JLabel tagline = new JLabel("CUSTOM JMETER PLUGIN");
        tagline.setFont(tagline.getFont().deriveFont(Font.BOLD, 9f));
        tagline.setForeground(new Color(0x90, 0x90, 0x90));
        JLabel wordmark = new JLabel("Insight Dashboard" + versionText);
        wordmark.setFont(wordmark.getFont().deriveFont(Font.BOLD, 16f));
        wordmark.setForeground(BAR_1);
        textStack.add(tagline);
        textStack.add(wordmark);
        iconAndText.add(textStack);

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 6));
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0x60, 0x60, 0x60)),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        bar.add(iconAndText);
        return bar;
    }

    /** Three bars of increasing height - a small, literal bar chart, matching this
     *  plugin's own subject matter rather than an arbitrary decorative mark. */
    private static final class DashboardIcon extends JPanel {
        private static final long serialVersionUID = 1L;

        DashboardIcon() {
            setPreferredSize(new Dimension(30, 28));
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int barWidth = 6;
            int gap = 3;
            int baseline = 24;
            g2.setColor(BAR_1);
            g2.fillRoundRect(2, baseline - 10, barWidth, 10, 2, 2);
            g2.setColor(BAR_2);
            g2.fillRoundRect(2 + barWidth + gap, baseline - 17, barWidth, 17, 2, 2);
            g2.setColor(BAR_3);
            g2.fillRoundRect(2 + 2 * (barWidth + gap), baseline - 22, barWidth, 22, 2, 2);
            g2.dispose();
        }
    }
}
