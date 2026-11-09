package io.insightdashboard.jmeter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects whether a response body looks like JSON or XML and re-indents it
 * for readability \u2014 no full parse tree built in either case, just a
 * careful character/token scan, since all that's needed here is
 * reformatting, not validation or data extraction. Falls back to the
 * original text completely unchanged for anything that doesn't confidently
 * look like one of those two formats, or that fails a basic structural
 * sanity check (unbalanced braces/tags) \u2014 safer to show raw,
 * unformatted text than to silently mangle something that only partially
 * resembles JSON/XML.
 */
final class ResponseFormatter {

    private ResponseFormatter() {
    }

    /**
     * @param body        the raw response body text
     * @param contentType the response's Content-Type header, if known (may be
     *                    null/empty) \u2014 checked first since it's a direct
     *                    signal from the server, more reliable than guessing
     *                    from the content alone
     * @return the body, pretty-printed if it's confidently JSON or XML,
     *         otherwise returned completely unchanged
     */
    static String format(String body, String contentType) {
        if (body == null || body.isEmpty()) {
            return body == null ? "" : body;
        }
        String trimmed = body.trim();
        String ct = contentType == null ? "" : contentType.toLowerCase(java.util.Locale.ROOT);

        boolean looksJson = ct.contains("json")
                || (ct.isEmpty() && (trimmed.startsWith("{") || trimmed.startsWith("[")));
        if (looksJson && isBalancedJsonLike(trimmed)) {
            try {
                return prettyPrintJson(trimmed);
            } catch (Exception ignored) {
                return body; // Any unexpected issue - show the original rather than risk mangled output.
            }
        }

        boolean looksXml = ct.contains("xml") || ct.contains("html")
                || (ct.isEmpty() && trimmed.startsWith("<"));
        if (looksXml && looksLikeXml(trimmed)) {
            try {
                return prettyPrintXml(trimmed);
            } catch (Exception ignored) {
                return body;
            }
        }

        return body;
    }

    /** Quick structural sanity check \u2014 not a full validator, just confirms
     *  brace/bracket/quote nesting is consistent, so text that merely starts
     *  with {{ or [ without actually being JSON doesn't get silently mangled. */
    private static boolean isBalancedJsonLike(String raw) {
        int depth = 0;
        boolean inString = false;
        boolean escapeNext = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                if (escapeNext) {
                    escapeNext = false;
                } else if (c == '\\') {
                    escapeNext = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth < 0) {
                    return false;
                }
            }
        }
        return depth == 0 && !inString;
    }

    /** Re-formats JSON text via a single character scan, tracking string-literal
     *  state so braces/colons/commas that happen to appear INSIDE a quoted
     *  string are left completely untouched, only structural ones outside any
     *  string get newlines/indentation inserted around them. */
    private static String prettyPrintJson(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 64);
        int indent = 0;
        boolean inString = false;
        boolean escapeNext = false;
        int n = raw.length();

        for (int i = 0; i < n; i++) {
            char c = raw.charAt(i);
            if (inString) {
                out.append(c);
                if (escapeNext) {
                    escapeNext = false;
                } else if (c == '\\') {
                    escapeNext = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
            } else if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                // Skip original whitespace outside strings - our own newlines/indentation replace it.
            } else if (c == '{' || c == '[') {
                char closer = c == '{' ? '}' : ']';
                int j = i + 1;
                while (j < n && Character.isWhitespace(raw.charAt(j))) {
                    j++;
                }
                if (j < n && raw.charAt(j) == closer) {
                    out.append(c).append(closer);
                    i = j;
                    continue;
                }
                out.append(c);
                indent++;
                appendNewlineIndent(out, indent);
            } else if (c == '}' || c == ']') {
                indent--;
                appendNewlineIndent(out, indent);
                out.append(c);
            } else if (c == ',') {
                out.append(c);
                appendNewlineIndent(out, indent);
            } else if (c == ':') {
                out.append(c).append(' ');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static void appendNewlineIndent(StringBuilder out, int level) {
        out.append('\n');
        for (int i = 0; i < level; i++) {
            out.append("  ");
        }
    }

    private static final Pattern TAG_OR_TEXT = Pattern.compile("<[^>]+>|[^<]+");
    private static final Pattern COLLAPSE_SIMPLE_TEXT =
            Pattern.compile("(<[^/!?][^>]*>)([^<\\n]+)\\n\\s*(</)");

    /** Loose check: must start with '<' and contain at least one recognizable
     *  tag structure. Not a validator - just enough to avoid treating
     *  arbitrary text that happens to contain a stray '<' as XML. */
    private static boolean looksLikeXml(String raw) {
        return raw.startsWith("<") && Pattern.compile("</[a-zA-Z]|/>|<[a-zA-Z!?]").matcher(raw).find();
    }

    /** Simple tag-based re-indenter, not a full XML parser: nested elements get
     *  indented one level deeper per open tag; an element containing only text
     *  (no child elements) is collapsed back onto one line after the fact,
     *  since that's the form people actually expect for leaf nodes like
     *  {@code <name>John</name>} rather than spreading it across three lines. */
    private static String prettyPrintXml(String raw) {
        String collapsed = raw.trim().replaceAll(">\\s*<", "><");
        Matcher tokens = TAG_OR_TEXT.matcher(collapsed);

        StringBuilder out = new StringBuilder(collapsed.length() + 64);
        int indent = 0;
        while (tokens.find()) {
            String tok = tokens.group();
            if (tok.startsWith("<")) {
                boolean isClosing = tok.startsWith("</");
                boolean isSelfClosingOrDirective =
                        tok.endsWith("/>") || tok.startsWith("<?") || tok.startsWith("<!");
                if (isClosing) {
                    indent--;
                    out.append('\n');
                    appendIndent(out, indent);
                    out.append(tok);
                } else {
                    if (out.length() > 0) {
                        out.append('\n');
                    }
                    appendIndent(out, indent);
                    out.append(tok);
                    if (!isSelfClosingOrDirective) {
                        indent++;
                    }
                }
            } else {
                String text = tok.trim();
                if (!text.isEmpty()) {
                    out.append(text);
                }
            }
        }

        String result = COLLAPSE_SIMPLE_TEXT.matcher(out.toString()).replaceAll("$1$2$3");
        return result.startsWith("\n") ? result.substring(1) : result;
    }

    private static void appendIndent(StringBuilder out, int level) {
        for (int i = 0; i < level; i++) {
            out.append("  ");
        }
    }
}
