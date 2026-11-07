package com.codewalnut.bd.engagement;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.codewalnut.bd.admin.CurrentUserProvider;
import com.codewalnut.bd.reporting.ActivityQueries;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.Role;

@RestController
@RequestMapping("/api/v1/activities")
class ActivityController {

    private final ActivityService activities;
    private final ActivityQueries queries;
    private final CurrentUserProvider currentUser;

    ActivityController(ActivityService activities, ActivityQueries queries, CurrentUserProvider currentUser) {
        this.activities = activities;
        this.queries = queries;
        this.currentUser = currentUser;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ActivityQueries.Activity log(@RequestBody ActivityService.Input input) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        return queries.get(activities.log(input, user));
    }
}
