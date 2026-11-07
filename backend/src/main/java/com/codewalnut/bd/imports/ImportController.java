package com.codewalnut.bd.imports;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.codewalnut.bd.admin.CurrentUserProvider;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.Role;

@RestController
@RequestMapping("/api/v1/imports")
class ImportController {

    record Batch(long id, String entityType, String fileName, String status, int totalRows, int createdCount,
                 int updatedCount, int failedCount, String uploadedBy, Instant uploadedAt) {}

    private final ImportService imports;
    private final CurrentUserProvider currentUser;
    private final JdbcClient jdbc;

    ImportController(ImportService imports, CurrentUserProvider currentUser, JdbcClient jdbc) {
        this.imports = imports;
        this.currentUser = currentUser;
        this.jdbc = jdbc;
    }

    @PostMapping("/{entityType}/validate")
    ImportService.Report validate(@PathVariable String entityType, @RequestParam("file") MultipartFile file)
        throws IOException {
        currentUser.get().require(Role.IMPORTERS);
        return imports.validate(ImportEntity.fromPath(entityType), file.getOriginalFilename(), file.getInputStream());
    }

    @PostMapping("/{entityType}/commit")
    ResponseEntity<ImportService.Report> commit(@PathVariable String entityType,
                                                @RequestParam("file") MultipartFile file) throws IOException {
        CurrentUser user = currentUser.get();
        user.require(Role.IMPORTERS);
        var report = imports.commit(ImportEntity.fromPath(entityType), file.getOriginalFilename(),
            file.getInputStream(), user);
        return ResponseEntity.status(report.committed() ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_CONTENT).body(report);
    }

    @GetMapping
    List<Batch> list() {
        return jdbc.sql("""
                SELECT b.id, b.entity_type, b.file_name, b.status, b.total_rows, b.created_count, b.updated_count,
                    b.failed_count, u.full_name, b.uploaded_at
                FROM import_batch b JOIN app_user u ON u.id = b.uploaded_by
                ORDER BY b.uploaded_at DESC, b.id DESC LIMIT 200
                """)
            .query((rs, n) -> new Batch(rs.getLong(1), rs.getString(2).toLowerCase(), rs.getString(3), rs.getString(4),
                rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getString(9),
                rs.getTimestamp(10).toInstant()))
            .list();
    }
}
