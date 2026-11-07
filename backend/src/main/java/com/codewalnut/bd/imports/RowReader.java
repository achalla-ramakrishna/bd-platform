package com.codewalnut.bd.imports;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads typed values from one spreadsheet row, recording a clear error for each bad cell
 * (docs/data-import/README.md, "General rules"). A method returns null when the cell is empty or
 * wrong, so callers can keep checking the rest of the row.
 */
final class RowReader {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final SpreadsheetReader.SheetRow row;
    private final ImportErrors errors;
    private int errorCount;

    RowReader(SpreadsheetReader.SheetRow row, ImportErrors errors) {
        this.row = row;
        this.errors = errors;
    }

    int rowNumber() {
        return row.rowNumber();
    }

    boolean ok() {
        return errorCount == 0;
    }

    void error(String column, String message) {
        errorCount++;
        errors.add(row.rowNumber(), column, column == null ? null : row.get(column), message);
    }

    String text(String column, boolean required, int maxLength) {
        String v = row.get(column);
        if (v == null) {
            if (required) {
                error(column, "Required.");
            }
            return null;
        }
        if (v.length() > maxLength) {
            error(column, "Too long: at most " + maxLength + " characters.");
            return null;
        }
        return v;
    }

    /** Matches a cell against allowed values, ignoring case, spaces and hyphens. */
    String choice(String column, boolean required, Map<String, String> allowed) {
        String v = text(column, required, 200);
        if (v == null) {
            return null;
        }
        String key = v.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        String mapped = allowed.get(key);
        if (mapped == null) {
            error(column, "Use one of: " + String.join(", ", allowed.keySet()) + ".");
        }
        return mapped;
    }

    LocalDate date(String column, boolean required) {
        String v = text(column, required, 40);
        if (v == null) {
            return null;
        }
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            error(column, "Use a date like 2027-03-31.");
            return null;
        }
    }

    Integer integer(String column, boolean required, int min, int max) {
        String v = text(column, required, 20);
        if (v == null) {
            return null;
        }
        try {
            int n = new BigDecimal(v.replace(",", "")).intValueExact();
            if (n < min || n > max) {
                error(column, "Must be between " + min + " and " + max + ".");
                return null;
            }
            return n;
        } catch (NumberFormatException | ArithmeticException e) {
            error(column, "Must be a whole number.");
            return null;
        }
    }

    BigDecimal money(String column, boolean required) {
        String v = text(column, required, 30);
        if (v == null) {
            return null;
        }
        try {
            BigDecimal amount = new BigDecimal(v);
            if (amount.signum() < 0) {
                error(column, "Cannot be negative.");
                return null;
            }
            if (amount.scale() > 2) {
                error(column, "At most two decimal places.");
                return null;
            }
            return amount;
        } catch (NumberFormatException e) {
            error(column, "Numbers only, in INR, without commas or currency symbols.");
            return null;
        }
    }

    Boolean yesNo(String column, boolean required) {
        String v = text(column, required, 10);
        if (v == null) {
            return null;
        }
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "yes", "y", "true" -> true;
            case "no", "n", "false" -> false;
            default -> {
                error(column, "Use yes or no.");
                yield null;
            }
        };
    }

    String email(String column, boolean required) {
        String v = text(column, required, 254);
        if (v == null) {
            return null;
        }
        if (!EMAIL.matcher(v).matches()) {
            error(column, "Not a valid email address.");
            return null;
        }
        return v.toLowerCase(Locale.ROOT);
    }

    List<String> list(String column, boolean required) {
        String v = text(column, required, 2000);
        if (v == null) {
            return List.of();
        }
        List<String> items = Arrays.stream(v.split(";")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        if (items.stream().anyMatch(s -> s.length() > 80)) {
            error(column, "Each item must be at most 80 characters. Separate items with ;");
        }
        return items;
    }
}
