package com.codewalnut.bd.imports;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Row errors collected while validating one file. */
final class ImportErrors {

    record RowError(int row, String column, String value, String message) {}

    private static final int MAX_REPORTED = 500;

    private final List<RowError> errors = new ArrayList<>();
    private int total;

    void add(int row, String column, String value, String message) {
        total++;
        if (errors.size() < MAX_REPORTED) {
            errors.add(new RowError(row, column, value, message));
        }
    }

    boolean isEmpty() {
        return total == 0;
    }

    int total() {
        return total;
    }

    long failedRows() {
        return errors.stream().map(RowError::row).distinct().count();
    }

    List<RowError> sorted() {
        return errors.stream().sorted(Comparator.comparingInt(RowError::row)).toList();
    }
}
