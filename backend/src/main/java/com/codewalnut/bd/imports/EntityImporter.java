package com.codewalnut.bd.imports;

import java.util.List;

/** Validates the rows of one template and plans the writes (ADR-0006). */
interface EntityImporter {

    ImportEntity entity();

    /** Checks every row, recording errors. The returned plan is applied only when there are no errors. */
    ImportPlan prepare(List<SpreadsheetReader.SheetRow> rows, ImportErrors errors);
}
