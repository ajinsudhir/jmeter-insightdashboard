package io.insightdashboard.jmeter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a minimal, valid .xlsx file using only java.util.zip — no Apache
 * POI or any other third-party dependency. An .xlsx is just a ZIP archive
 * of a handful of small XML parts (OOXML SpreadsheetML); this writes
 * exactly the parts a basic single-sheet workbook needs and nothing more.
 *
 * This keeps the plugin a single drop-in jar with no risk of classpath
 * conflicts against JMeter's own bundled libraries (POI's dependency tree
 * overlaps with several things JMeter itself already ships).
 *
 * Deliberately narrow in scope: one sheet, a small report header, a column
 * header row, and data rows with a fixed set of cell styles (bold/filled
 * transaction rows, indented sub-request rows, red SLA-breach highlight)
 * — not a general-purpose spreadsheet library.
 */
final class XlsxExporter {

    private XlsxExporter() {
    }

    /** One row of the exported report — mirrors the live table's columns. */
    static final class ExportRow {
        final String label;
        final int level;      // 0 = transaction, 1 = sub-request
        final boolean breach; // true -> red SLA-breach fill, same check as the live table
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

        ExportRow(String label, int level, boolean breach, long samples, long errors, double errorPct,
                  double avg, long min, long max, long p90, long p95, long p99, double throughput) {
            this.label = label;
            this.level = level;
            this.breach = breach;
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

    private static final String[] HEADERS = {
            "Label", "Samples", "Errors", "Error %", "Avg (ms)", "Min (ms)", "Max (ms)",
            "P90 (ms)", "P95 (ms)", "P99 (ms)", "Throughput (req/s)"
    };

    /** Report header rows occupy 1-3; column headers are row 4; data starts at row 5. */
    private static final int HEADER_ROW = 4;
    private static final int FIRST_DATA_ROW = 5;

    static void write(File file, String scopeLabel, Double slaThresholdMs, List<ExportRow> rows) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            zip.setLevel(6);
            putEntry(zip, "[Content_Types].xml", CONTENT_TYPES);
            putEntry(zip, "_rels/.rels", RELS);
            putEntry(zip, "xl/workbook.xml", WORKBOOK);
            putEntry(zip, "xl/_rels/workbook.xml.rels", WORKBOOK_RELS);
            putEntry(zip, "xl/styles.xml", STYLES);
            putEntry(zip, "xl/worksheets/sheet1.xml", buildSheetXml(scopeLabel, slaThresholdMs, rows));
        }
    }

    private static void putEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    // ---- Cell style indices, defined in STYLES' <cellXfs> below ----
    // 0 default | 1 header | 2/3/4 parent label/int/decimal | 5/6/7 child label/int/decimal
    // 8/9/10 parent-breach label/int/decimal | 11/12/13 child-breach label/int/decimal
    private static int styleFor(int level, boolean breach, int columnType) {
        int base;
        if (level == 0) {
            base = breach ? 8 : 2;
        } else {
            base = breach ? 11 : 5;
        }
        return base + columnType; // columnType: 0 = label, 1 = integer, 2 = decimal
    }

    private static String buildSheetXml(String scopeLabel, Double slaThresholdMs, List<ExportRow> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");

        sb.append("<sheetViews><sheetView tabSelected=\"1\" workbookViewId=\"0\">")
          .append("<pane ySplit=\"").append(HEADER_ROW)
          .append("\" topLeftCell=\"A").append(FIRST_DATA_ROW)
          .append("\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>");

        sb.append("<cols><col min=\"1\" max=\"1\" width=\"34\" customWidth=\"1\"/>")
          .append("<col min=\"2\" max=\"11\" width=\"14\" customWidth=\"1\"/></cols>");

        sb.append("<sheetData>");

        String exportedAt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        sb.append(titleRow(1, "Live Dashboard \u2014 Export", 1));

        String slaText = slaThresholdMs == null ? "none"
                : "P95 > " + formatNumber(slaThresholdMs) + " ms";
        String scopeText = "Scope: " + (scopeLabel == null ? "Overall" : scopeLabel)
                + "   |   Exported: " + exportedAt + "   |   SLA threshold: " + slaText;
        sb.append(titleRow(2, scopeText, 0));

        // Row 3 intentionally left blank (spacer) — no cells emitted.

        sb.append("<row r=\"").append(HEADER_ROW).append("\">");
        for (int col = 0; col < HEADERS.length; col++) {
            sb.append(cellInlineStr(col, HEADER_ROW, HEADERS[col], 1));
        }
        sb.append("</row>");

        int r = FIRST_DATA_ROW;
        for (ExportRow row : rows) {
            int labelStyle = styleFor(row.level, row.breach, 0);
            int intStyle = styleFor(row.level, row.breach, 1);
            int decStyle = styleFor(row.level, row.breach, 2);
            String label = row.level == 1 ? "\u2514 " + row.label : row.label;

            sb.append("<row r=\"").append(r).append("\">");
            sb.append(cellInlineStr(0, r, label, labelStyle));
            sb.append(cellNumber(1, r, row.samples, intStyle));
            sb.append(cellNumber(2, r, row.errors, intStyle));
            sb.append(cellNumber(3, r, row.errorPct, decStyle));
            sb.append(cellNumber(4, r, row.avg, decStyle));
            sb.append(cellNumber(5, r, row.min, intStyle));
            sb.append(cellNumber(6, r, row.max, intStyle));
            sb.append(cellNumber(7, r, row.p90, intStyle));
            sb.append(cellNumber(8, r, row.p95, intStyle));
            sb.append(cellNumber(9, r, row.p99, intStyle));
            sb.append(cellNumber(10, r, row.throughput, decStyle));
            sb.append("</row>");
            r++;
        }

        sb.append("</sheetData>");
        sb.append("</worksheet>");
        return sb.toString();
    }

    private static String titleRow(int rowNum, String text, int style) {
        return "<row r=\"" + rowNum + "\">" + cellInlineStr(0, rowNum, text, style) + "</row>";
    }

    private static String cellRef(int col, int row) {
        return columnLetter(col) + row;
    }

    private static String columnLetter(int col) {
        StringBuilder sb = new StringBuilder();
        int c = col;
        do {
            sb.insert(0, (char) ('A' + (c % 26)));
            c = c / 26 - 1;
        } while (c >= 0);
        return sb.toString();
    }

    private static String cellInlineStr(int col, int row, String text, int style) {
        return "<c r=\"" + cellRef(col, row) + "\" s=\"" + style + "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">"
                + escapeXml(text) + "</t></is></c>";
    }

    private static String cellNumber(int col, int row, double value, int style) {
        return "<c r=\"" + cellRef(col, row) + "\" s=\"" + style + "\"><v>" + formatNumber(value) + "</v></c>";
    }

    private static String formatNumber(double value) {
        if (!Double.isInfinite(value) && !Double.isNaN(value) && value == Math.rint(value)
                && Math.abs(value) < 1.0e15) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---- Fixed OOXML package parts ----

    private static final String CONTENT_TYPES =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Override PartName=\"/xl/workbook.xml\" "
            + "ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
            + "<Override PartName=\"/xl/worksheets/sheet1.xml\" "
            + "ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
            + "<Override PartName=\"/xl/styles.xml\" "
            + "ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
            + "</Types>";

    private static final String RELS =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" "
            + "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" "
            + "Target=\"xl/workbook.xml\"/>"
            + "</Relationships>";

    private static final String WORKBOOK =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
            + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
            + "<sheets><sheet name=\"Dashboard Snapshot\" sheetId=\"1\" r:id=\"rId1\"/></sheets>"
            + "</workbook>";

    private static final String WORKBOOK_RELS =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" "
            + "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" "
            + "Target=\"worksheets/sheet1.xml\"/>"
            + "<Relationship Id=\"rId2\" "
            + "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" "
            + "Target=\"styles.xml\"/>"
            + "</Relationships>";

    // Style indices referenced by styleFor()/HEADER cells above:
    // fonts:  0 default, 1 bold white (header), 2 bold black (transaction rows)
    // fills:  0 none, 1 gray125 (reserved slot per OOXML convention), 2 header dark blue,
    //         3 transaction-row light gray, 4 SLA-breach red
    // numFmt: 164 = "0.00" (Error %, Avg, Throughput)
    private static final String STYLES =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            + "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"0.00\"/></numFmts>"
            + "<fonts count=\"3\">"
            + "<font><sz val=\"11\"/><color rgb=\"FF000000\"/><name val=\"Calibri\"/></font>"
            + "<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/></font>"
            + "<font><b/><sz val=\"11\"/><color rgb=\"FF000000\"/><name val=\"Calibri\"/></font>"
            + "</fonts>"
            + "<fills count=\"5\">"
            + "<fill><patternFill patternType=\"none\"/></fill>"
            + "<fill><patternFill patternType=\"gray125\"/></fill>"
            + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF2C3E50\"/><bgColor indexed=\"64\"/></patternFill></fill>"
            + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFEDEDED\"/><bgColor indexed=\"64\"/></patternFill></fill>"
            + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFFDE4E1\"/><bgColor indexed=\"64\"/></patternFill></fill>"
            + "</fills>"
            + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
            + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
            + "<cellXfs count=\"14\">"
            // 0: default
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
            // 1: column header (bold white on dark blue)
            + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
            // 2: transaction label (bold, light gray fill)
            + "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"3\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
            // 3: transaction integer cell
            + "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"3\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
            // 4: transaction decimal cell (0.00)
            + "<xf numFmtId=\"164\" fontId=\"2\" fillId=\"3\" borderId=\"0\" xfId=\"0\" "
            + "applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\"/>"
            // 5: sub-request label (indented, no fill)
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\">"
            + "<alignment indent=\"1\"/></xf>"
            // 6: sub-request integer cell
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
            // 7: sub-request decimal cell (0.00)
            + "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
            // 8: transaction label, SLA-breached (bold, red fill)
            + "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"4\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
            // 9: transaction integer cell, SLA-breached
            + "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"4\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
            // 10: transaction decimal cell, SLA-breached
            + "<xf numFmtId=\"164\" fontId=\"2\" fillId=\"4\" borderId=\"0\" xfId=\"0\" "
            + "applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\"/>"
            // 11: sub-request label, SLA-breached (indented, red fill)
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"4\" borderId=\"0\" xfId=\"0\" applyFill=\"1\" applyAlignment=\"1\">"
            + "<alignment indent=\"1\"/></xf>"
            // 12: sub-request integer cell, SLA-breached
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"4\" borderId=\"0\" xfId=\"0\" applyFill=\"1\"/>"
            // 13: sub-request decimal cell, SLA-breached
            + "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"4\" borderId=\"0\" xfId=\"0\" "
            + "applyNumberFormat=\"1\" applyFill=\"1\"/>"
            + "</cellXfs>"
            + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
            + "</styleSheet>";
}
