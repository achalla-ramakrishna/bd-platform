package com.codewalnut.bd.shared;

import java.util.EnumSet;
import java.util.Set;

/** Application roles (business requirements, "Users and roles"). */
public enum Role {
    LEADERSHIP,
    BD_LEAD,
    ACCOUNT_EXECUTIVE,
    DELIVERY,
    SOLUTIONS,
    ADMIN;

    /** May see bill rates, contract values and engineer personal data (FR-EXI-08). */
    public static final Set<Role> RESTRICTED_VIEWERS = EnumSet.of(LEADERSHIP, BD_LEAD, DELIVERY, ADMIN);

    /** May create, edit and move opportunities and log activities. Delivery reads the pipeline. */
    public static final Set<Role> PIPELINE_EDITORS = EnumSet.of(LEADERSHIP, BD_LEAD, ACCOUNT_EXECUTIVE, SOLUTIONS, ADMIN);

    /** May edit the BD strategy page (backlog item 2). Everyone signed in can read it. */
    /** May work any GTN contact, hand contacts over and see the team's numbers (ADR-0021); owners work their own. */
    public static final Set<Role> NETWORK_MANAGERS = EnumSet.of(LEADERSHIP, BD_LEAD, ADMIN);

    public static final Set<Role> STRATEGY_EDITORS = EnumSet.of(LEADERSHIP, ADMIN);

    /** May approve, decline and progress improvement areas (backlog item 5). Everyone may suggest. */
    public static final Set<Role> IMPROVEMENT_APPROVERS = EnumSet.of(LEADERSHIP, ADMIN);

    /** May upload spreadsheets (ADR-0006). */
    public static final Set<Role> IMPORTERS = EnumSet.of(BD_LEAD, DELIVERY, ADMIN);
}
