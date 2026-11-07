package com.codewalnut.bd.imports;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.codewalnut.bd.shared.CurrentUser;

/**
 * Two-stage upload (ADR-0006): validate saves nothing; commit validates again and saves every row in
 * one transaction, or nothing. Commits and failed commit attempts are recorded as import batches.
 */
@Service
class ImportService {

    record Report(String entityType, String fileName, int totalRows, boolean valid, int toCreate, int toUpdate,
                  boolean committed, Long batchId, int errorCount, List<ImportErrors.RowError> errors) {}

    private record Prepared(SpreadsheetReader.Sheet0 sheet, ImportErrors errors, ImportPlan plan) {}

    private final Map<ImportEntity, EntityImporter> importers = new EnumMap<>(ImportEntity.class);
    private final JdbcClient jdbc;

    ImportService(List<EntityImporter> importers, JdbcClient jdbc) {
        importers.forEach(i -> this.importers.put(i.entity(), i));
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Report validate(ImportEntity entity, String fileName, InputStream file) throws IOException {
        Prepared p = prepare(entity, fileName, file);
        return report(entity, fileName, p, false, null);
    }

    @Transactional
    public Report commit(ImportEntity entity, String fileName, InputStream file, CurrentUser user) throws IOException {
        Prepared p = prepare(entity, fileName, file);
        if (!p.errors().isEmpty()) {
            long batchId = recordBatch(entity, fileName, "FAILED", p, user.id());
            return report(entity, fileName, p, false, batchId);
        }
        p.plan().applier().apply(user.id());
        long batchId = recordBatch(entity, fileName, "COMMITTED", p, user.id());
        return report(entity, fileName, p, true, batchId);
    }

    private Prepared prepare(ImportEntity entity, String fileName, InputStream file) throws IOException {
        SpreadsheetReader.Sheet0 sheet = SpreadsheetReader.read(fileName, file);
        ImportErrors errors = new ImportErrors();
        List<String> missing = entity.columns().stream().filter(c -> !sheet.headers().contains(c)).toList();
        if (!missing.isEmpty()) {
            errors.add(1, null, null, "Missing columns: " + String.join(", ", missing)
                + ". Use the " + entity.pathName() + " template.");
            return new Prepared(sheet, errors, ImportPlan.empty());
        }
        if (sheet.rows().isEmpty()) {
            errors.add(2, null, null, "The file has no data rows.");
            return new Prepared(sheet, errors, ImportPlan.empty());
        }
        ImportPlan plan = importers.get(entity).prepare(sheet.rows(), errors);
        return new Prepared(sheet, errors, plan);
    }

    private static Report report(ImportEntity entity, String fileName, Prepared p, boolean committed, Long batchId) {
        boolean valid = p.errors().isEmpty();
        return new Report(entity.pathName(), fileName, p.sheet().rows().size(), valid,
            valid ? p.plan().toCreate() : 0, valid ? p.plan().toUpdate() : 0, committed, batchId,
            p.errors().total(), p.errors().sorted());
    }

    private long recordBatch(ImportEntity entity, String fileName, String status, Prepared p, long userId) {
        var keys = new GeneratedKeyHolder();
        boolean committed = "COMMITTED".equals(status);
        jdbc.sql("""
                INSERT INTO import_batch (entity_type, file_name, status, total_rows, created_count, updated_count,
                    failed_count, uploaded_by)
                VALUES (:entity, :file, :status, :total, :created, :updated, :failed, :user)
                """)
            .param("entity", entity.name()).param("file", truncate(fileName, 255)).param("status", status)
            .param("total", p.sheet().rows().size())
            .param("created", committed ? p.plan().toCreate() : 0)
            .param("updated", committed ? p.plan().toUpdate() : 0)
            .param("failed", (int) p.errors().failedRows())
            .param("user", userId)
            .update(keys);
        long batchId = keys.getKey().longValue();
        for (var e : p.errors().sorted()) {
            jdbc.sql("INSERT INTO import_row_error (batch_id, row_no, column_name, message) VALUES (:b, :r, :c, :m)")
                .param("b", batchId).param("r", e.row()).param("c", e.column()).param("m", truncate(e.message(), 500))
                .update();
        }
        return batchId;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
