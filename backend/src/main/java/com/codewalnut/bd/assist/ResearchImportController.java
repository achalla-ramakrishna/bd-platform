package com.codewalnut.bd.assist;

import java.util.List;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.codewalnut.bd.admin.CurrentUserProvider;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.Role;

/** Upload account research written outside the app (ADR-0010). Works without ANTHROPIC_API_KEY. */
@RestController
class ResearchImportController {

    private final ResearchImportService imports;
    private final CurrentUserProvider currentUser;

    ResearchImportController(ResearchImportService imports, CurrentUserProvider currentUser) {
        this.imports = imports;
        this.currentUser = currentUser;
    }

    @PostMapping("/api/v1/research/uploads")
    List<ResearchImportService.Saved> upload(@RequestBody ResearchImportService.Upload upload) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        return imports.save(upload, user);
    }
}
