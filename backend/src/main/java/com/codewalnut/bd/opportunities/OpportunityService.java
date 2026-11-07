package com.codewalnut.bd.opportunities;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.codewalnut.bd.accounts.AccountRegistry;
import com.codewalnut.bd.admin.UserDirectory;
import com.codewalnut.bd.contacts.ContactRegistry;
import com.codewalnut.bd.shared.AuditLog;
import com.codewalnut.bd.shared.ConflictException;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.NotFoundException;
import com.codewalnut.bd.shared.RuleViolationException;

/** Creating, editing and moving opportunities through the pipeline (FR-OPP-01 to FR-OPP-07). */
@Service
public class OpportunityService {

    static final Set<String> TYPES = Set.of("STAFF_AUGMENTATION", "PROJECT");
    static final Set<String> LOCATION_MODES = Set.of("ONSITE", "HYBRID", "REMOTE");
    static final Set<String> RATE_UNITS = Set.of("MONTH", "DAY", "HOUR");
    static final Set<String> PRICING = Set.of("FIXED_PRICE", "TIME_AND_MATERIALS");

    /** Request body for create and update. {@code version} is required on update. */
    public record Input(Long accountId, String name, String type, Long ownerId, BigDecimal valueInr, Integer probability,
                        LocalDate expectedCloseDate, Long decisionMakerContactId, String needSummary,
                        LocalDate targetStartDate, List<RequirementInput> requirements, String scopeSummary,
                        String pricingModel, BigDecimal estimateInr, String teamShape, String timeline,
                        Integer version) {}

    public record RequirementInput(String role, List<String> skills, String seniority, Integer headcount,
                                   String locationMode, String location, LocalDate startDate, Integer durationMonths,
                                   Integer allocationPercent, BigDecimal rateInr, String rateUnit) {}

    public record StageMove(String stage, String lossReasonCode) {}

    private final OpportunityRepository repo;
    private final AccountRegistry accounts;
    private final ContactRegistry contacts;
    private final UserDirectory users;
    private final AuditLog audit;

    OpportunityService(OpportunityRepository repo, AccountRegistry accounts, ContactRegistry contacts,
                       UserDirectory users, AuditLog audit) {
        this.repo = repo;
        this.accounts = accounts;
        this.contacts = contacts;
        this.users = users;
        this.audit = audit;
    }

    @Transactional
    public long create(Input in, CurrentUser user) {
        var stages = repo.stages();
        StageRules.Stage first = stages.values().iterator().next();
        Validated v = validate(in, user, first.defaultProbability());
        long id = repo.insert(v.data(), first.code());
        repo.replaceRequirements(id, v.requirements());
        repo.recordStage(id, null, first.code(), user.id());
        audit.record("opportunity", id, AuditLog.Action.CREATE, Map.of("name", v.data().name()), user.id());
        return id;
    }

    @Transactional
    public void update(long id, Input in, CurrentUser user) {
        var state = repo.load(id).orElseThrow(() -> new NotFoundException("Opportunity", id));
        if (in.version() == null) {
            throw new IllegalArgumentException("version is required when saving changes.");
        }
        Validated v = validate(in, user, 0);
        if (!repo.update(id, v.data(), in.version())) {
            throw new ConflictException("Someone else saved this opportunity after you opened it. Reload and try again.");
        }
        // Once won, requirement lines have gone to Delivery as deployment requests (BR-BD-12): keep them as they are.
        if (in.requirements() != null && !repo.hasDeploymentRequests(id)) {
            repo.replaceRequirements(id, v.requirements());
        }
        audit.record("opportunity", id, AuditLog.Action.UPDATE, Map.of("fromVersion", state.version()), user.id());
    }

    @Transactional
    public void moveStage(long id, StageMove move, CurrentUser user) {
        var state = repo.load(id).orElseThrow(() -> new NotFoundException("Opportunity", id));
        var stages = repo.stages();
        String code = move.stage() == null ? null : move.stage().toUpperCase(Locale.ROOT);
        StageRules.Stage target = stages.get(code);
        if (target == null) {
            throw new IllegalArgumentException("Unknown stage: " + move.stage());
        }
        String reason = move.lossReasonCode() == null ? null : move.lossReasonCode().toUpperCase(Locale.ROOT);
        List<String> problems = StageRules.check(state, target, stages, reason, repo.lossReasons());
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
        repo.moveStage(id, target.code(), target.defaultProbability(), "LOST".equals(target.outcome()) ? reason : null);
        repo.recordStage(id, state.stage(), target.code(), user.id());
        int requests = 0;
        if ("WON".equals(target.outcome()) && "STAFF_AUGMENTATION".equals(state.type())) {
            requests = repo.createDeploymentRequests(id);
        }
        audit.record("opportunity", id, AuditLog.Action.UPDATE,
            Map.of("stage", state.stage() + " -> " + target.code(), "deploymentRequests", requests), user.id());
    }

    private record Validated(OpportunityRepository.Data data, List<OpportunityRepository.Requirement> requirements) {}

    private Validated validate(Input in, CurrentUser user, int defaultProbability) {
        List<String> problems = new ArrayList<>();
        if (in.accountId() == null || !accounts.exists(in.accountId())) {
            problems.add("Choose an existing account.");
        }
        if (in.name() == null || in.name().isBlank()) {
            problems.add("Give the opportunity a name.");
        } else if (in.name().length() > 200) {
            problems.add("The name can be at most 200 characters.");
        }
        String type = in.type() == null ? null : in.type().toUpperCase(Locale.ROOT);
        if (!isIn(TYPES, type)) {
            problems.add("Type must be staff augmentation or project.");
        }
        long ownerId = in.ownerId() == null ? user.id() : in.ownerId();
        if (!users.isActive(ownerId)) {
            problems.add("The owner must be an active user.");
        }
        int probability = in.probability() == null ? defaultProbability : in.probability();
        if (probability < 0 || probability > 100) {
            problems.add("Probability must be between 0 and 100.");
        }
        if (in.valueInr() != null && in.valueInr().signum() < 0) {
            problems.add("Value cannot be negative.");
        }
        if (in.decisionMakerContactId() != null) {
            var contactAccount = contacts.accountOf(in.decisionMakerContactId());
            if (contactAccount.isEmpty() || !contactAccount.get().equals(in.accountId())) {
                problems.add("The decision maker must be a contact at this account.");
            }
        }
        String pricing = in.pricingModel() == null || in.pricingModel().isBlank() ? null : in.pricingModel().toUpperCase(Locale.ROOT);
        if (pricing != null && !isIn(PRICING, pricing)) {
            problems.add("Pricing model must be fixed price or time and materials.");
        }
        List<OpportunityRepository.Requirement> requirements = new ArrayList<>();
        if (in.requirements() != null) {
            if (!"STAFF_AUGMENTATION".equals(type) && !in.requirements().isEmpty()) {
                problems.add("Only staff augmentation opportunities have requirement lines.");
            }
            Set<String> seniorities = repo.seniorities();
            int line = 0;
            for (RequirementInput r : in.requirements()) {
                line++;
                String prefix = "Requirement " + line + ": ";
                String seniority = r.seniority() == null ? null : r.seniority().toUpperCase(Locale.ROOT);
                String mode = r.locationMode() == null ? null : r.locationMode().toUpperCase(Locale.ROOT);
                String unit = r.rateUnit() == null || r.rateUnit().isBlank() ? null : r.rateUnit().toUpperCase(Locale.ROOT);
                List<String> skills = r.skills() == null ? List.of()
                    : r.skills().stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
                if (r.role() == null || r.role().isBlank()) problems.add(prefix + "role is required.");
                if (skills.isEmpty()) problems.add(prefix + "add at least one skill.");
                if (!isIn(seniorities, seniority)) problems.add(prefix + "choose a seniority.");
                if (r.headcount() == null || r.headcount() < 1) problems.add(prefix + "headcount must be at least 1.");
                if (!isIn(LOCATION_MODES, mode)) problems.add(prefix + "choose onsite, hybrid or remote.");
                if (r.startDate() == null) problems.add(prefix + "start date is required.");
                if (r.durationMonths() != null && r.durationMonths() < 1) problems.add(prefix + "duration must be at least 1 month.");
                int allocation = r.allocationPercent() == null ? 100 : r.allocationPercent();
                if (allocation < 1 || allocation > 100) problems.add(prefix + "allocation must be between 1 and 100.");
                if (unit != null && !isIn(RATE_UNITS, unit)) problems.add(prefix + "rate unit must be month, day or hour.");
                if (r.rateInr() != null && unit == null) problems.add(prefix + "choose a rate unit for the rate.");
                if (r.rateInr() != null && r.rateInr().signum() < 0) problems.add(prefix + "rate cannot be negative.");
                requirements.add(new OpportunityRepository.Requirement(r.role(), skills, seniority,
                    r.headcount() == null ? 0 : r.headcount(), mode, r.location(), r.startDate(), r.durationMonths(),
                    allocation, r.rateInr(), unit));
            }
        }
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
        var data = new OpportunityRepository.Data(in.accountId(), in.name().trim(), type, ownerId, in.valueInr(),
            probability, in.expectedCloseDate(), in.decisionMakerContactId(), blankToNull(in.needSummary()),
            in.targetStartDate(), blankToNull(in.scopeSummary()), pricing, in.estimateInr(),
            blankToNull(in.teamShape()), blankToNull(in.timeline()));
        return new Validated(data, requirements);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Null-safe membership check; immutable sets reject {@code contains(null)}. */
    private static boolean isIn(Set<String> allowed, String value) {
        return value != null && allowed.contains(value);
    }
}
