package com.codewalnut.bd.imports;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.input.BOMInputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Reads the first sheet of an XLSX file, or a CSV file, into rows keyed by normalised column name
 * ("Account Name" becomes "account_name"). Blank rows are skipped. Row numbers match what the user
 * sees in their spreadsheet: the header is row 1.
 */
final class SpreadsheetReader {

    static final int MAX_ROWS = 5000;

    record SheetRow(int rowNumber, Map<String, String> values) {

        String get(String column) {
            String v = values.get(column);
            return v == null || v.isBlank() ? null : v.trim();
        }
    }

    record Sheet0(List<String> headers, List<SheetRow> rows) {}

    private SpreadsheetReader() {}

    static Sheet0 read(String fileName, InputStream in) throws IOException {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xlsx")) {
            return readXlsx(in);
        }
        if (lower.endsWith(".csv")) {
            return readCsv(in);
        }
        throw new IllegalArgumentException("Upload a .csv or .xlsx file.");
    }

    static String normaliseHeader(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }

    private static Sheet0 readCsv(InputStream in) throws IOException {
        Reader reader = new InputStreamReader(BOMInputStream.builder().setInputStream(in).get(), StandardCharsets.UTF_8);
        CSVFormat format = CSVFormat.DEFAULT.builder().setTrim(true).setIgnoreEmptyLines(false).get();
        try (CSVParser parser = CSVParser.parse(reader, format)) {
            List<String> headers = null;
            List<SheetRow> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                int rowNumber = (int) record.getRecordNumber();
                if (headers == null) {
                    headers = record.stream().map(SpreadsheetReader::normaliseHeader).toList();
                    continue;
                }
                Map<String, String> values = new LinkedHashMap<>();
                for (int i = 0; i < headers.size() && i < record.size(); i++) {
                    values.put(headers.get(i), record.get(i));
                }
                addIfNotBlank(rows, rowNumber, values);
            }
            return new Sheet0(headers == null ? List.of() : headers, rows);
        }
    }

    private static Sheet0 readXlsx(InputStream in) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<String> headers = null;
            List<SheetRow> rows = new ArrayList<>();
            for (Row row : sheet) {
                if (headers == null) {
                    headers = new ArrayList<>();
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        headers.add(normaliseHeader(formatter.formatCellValue(row.getCell(c))));
                    }
                    continue;
                }
                Map<String, String> values = new LinkedHashMap<>();
                for (int c = 0; c < headers.size(); c++) {
                    values.put(headers.get(c), cellText(row.getCell(c), formatter));
                }
                addIfNotBlank(rows, row.getRowNum() + 1, values);
            }
            return new Sheet0(headers == null ? List.of() : headers, rows);
        }
    }

    /** Dates typed into Excel come back as ISO dates; everything else as the text the user sees. */
    private static String cellText(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        if (cell.getCellType() == CellType.FORMULA) {
            return formatter.formatCellValue(cell, cell.getSheet().getWorkbook().getCreationHelper().createFormulaEvaluator());
        }
        return formatter.formatCellValue(cell);
    }

    private static void addIfNotBlank(List<SheetRow> rows, int rowNumber, Map<String, String> values) {
        boolean blank = values.values().stream().allMatch(v -> v == null || v.isBlank());
        if (blank) {
            return;
        }
        if (rows.size() >= MAX_ROWS) {
            throw new IllegalArgumentException("A file can have at most " + MAX_ROWS + " rows. Split it and upload in parts.");
        }
        rows.add(new SheetRow(rowNumber, values));
    }
}
