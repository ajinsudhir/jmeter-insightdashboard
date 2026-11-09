package io.insightdashboard.jmeter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deliberately minimal Markdown-to-HTML converter — handles just the
 * subset Gemini CLI's responses actually use: headings (# .. ######), bold
 * (**text**), inline code (`text`), bullet lists (* / -), numbered lists
 * (1. 2. ...), horizontal rules (--- / ***), and paragraphs. It is not a
 * general-purpose Markdown parser and isn't meant to be one.
 *
 * Output is kept to HTML that Swing's JEditorPane (HTMLEditorKit, roughly
 * an HTML 3.2 / CSS1-level renderer) displays correctly, since the same
 * HTML is used both inline in the GUI and in the saved report file.
 */
final class MarkdownLite {

    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern NUMBERED_ITEM = Pattern.compile("^\\d+\\.\\s+.*");

    private MarkdownLite() {
    }

    static String toHtml(String markdown) {
        if (markdown == null || markdown.trim().isEmpty()) {
            return "<p><i>(no response)</i></p>";
        }

        StringBuilder html = new StringBuilder();
        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);

        boolean inUl = false;
        boolean inOl = false;
        StringBuilder paragraph = new StringBuilder();

        for (String rawLine : lines) {
            String trimmed = rawLine.trim();

            if (trimmed.isEmpty()) {
                flushParagraph(html, paragraph);
                continue;
            }

            if (trimmed.equals("---") || trimmed.equals("***")) {
                flushParagraph(html, paragraph);
                inUl = closeIf(html, inUl, "</ul>\n");
                inOl = closeIf(html, inOl, "</ol>\n");
                html.append("<hr/>\n");
                continue;
            }

            int headingLevel = 0;
            while (headingLevel < trimmed.length() && trimmed.charAt(headingLevel) == '#') {
                headingLevel++;
            }
            if (headingLevel > 0 && headingLevel <= 6 && trimmed.length() > headingLevel
                    && trimmed.charAt(headingLevel) == ' ') {
                flushParagraph(html, paragraph);
                inUl = closeIf(html, inUl, "</ul>\n");
                inOl = closeIf(html, inOl, "</ol>\n");
                String text = inline(escapeHtml(trimmed.substring(headingLevel + 1).trim()));
                html.append('<').append('h').append(headingLevel).append('>').append(text)
                        .append("</h").append(headingLevel).append(">\n");
                continue;
            }

            if (trimmed.startsWith("* ") || trimmed.startsWith("- ")) {
                flushParagraph(html, paragraph);
                inOl = closeIf(html, inOl, "</ol>\n");
                if (!inUl) {
                    html.append("<ul>\n");
                    inUl = true;
                }
                html.append("<li>").append(inline(escapeHtml(trimmed.substring(2).trim()))).append("</li>\n");
                continue;
            }

            if (NUMBERED_ITEM.matcher(trimmed).matches()) {
                flushParagraph(html, paragraph);
                inUl = closeIf(html, inUl, "</ul>\n");
                if (!inOl) {
                    html.append("<ol>\n");
                    inOl = true;
                }
                String text = trimmed.replaceFirst("^\\d+\\.\\s+", "");
                html.append("<li>").append(inline(escapeHtml(text))).append("</li>\n");
                continue;
            }

            // Plain text line: close any open list, accumulate into the current paragraph.
            inUl = closeIf(html, inUl, "</ul>\n");
            inOl = closeIf(html, inOl, "</ol>\n");
            if (paragraph.length() > 0) {
                paragraph.append(' ');
            }
            paragraph.append(trimmed);
        }
        flushParagraph(html, paragraph);
        closeIf(html, inUl, "</ul>\n");
        closeIf(html, inOl, "</ol>\n");

        return html.toString();
    }

    private static boolean closeIf(StringBuilder html, boolean open, String closingTag) {
        if (open) {
            html.append(closingTag);
        }
        return false;
    }

    private static void flushParagraph(StringBuilder html, StringBuilder paragraph) {
        if (paragraph.length() > 0) {
            html.append("<p>").append(inline(escapeHtml(paragraph.toString()))).append("</p>\n");
            paragraph.setLength(0);
        }
    }

    private static String inline(String escapedText) {
        String withBold = BOLD.matcher(escapedText).replaceAll("<b>$1</b>");
        return INLINE_CODE.matcher(withBold).replaceAll("<code>$1</code>");
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
