package com.codewalnut.bd.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.codewalnut.bd.admin.UserDirectory;
import com.codewalnut.bd.shared.Role;

/**
 * Upload end to end against MySQL (ADR-0006, FR-EXI-01 to FR-EXI-05, BR-BD-14, FR-EXI-08).
 * All data here is fictional.
 */
@SpringBootTest
@ActiveProfiles("test")
class ImportFlowIntegrationTest {

    private static final String ADMIN = "admin@example-gcc.test";
    private static final String AE = "sales.person@example-gcc.test";

    @Autowired
    WebApplicationContext context;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    UserDirectory users;

    MockMvc mvc;

    static final String ACCOUNTS = """
        account_name,parent_company,parent_hq_country,city,website_domain,industry,headcount_band,year_established,tech_stack,relationship,owner_email,source,notes
        Alpha Bengaluru,Alpha Corp,USA,Bengaluru,https://www.alpha-gcc.test/,Retail,500-2000,2015,Java;React,client,admin@example-gcc.test,existing client,
        Alpha Hyderabad,Alpha Corp,USA,Hyderabad,alpha-gcc.test,Retail,<500,2021,Java,prospect,owner.one@example-gcc.test,referral,
        Beta Pune,Beta Group,Germany,Pune,beta-gcc.test,Automotive,2000-5000,,Python;AWS,prospect,owner.one@example-gcc.test,Nasscom report,
        """;

    static final String CONTRACTS = """
        contract_ref,account_website_domain,account_city,contract_type,parent_msa_ref,work_type,pricing_model,total_value,start_date,end_date,renewal_date,status,client_permission_to_name,notes
        SOW-A1,alpha-gcc.test,Bengaluru,sow,MSA-A,staff_augmentation,monthly_rate,1200000,2027-01-01,2027-12-31,,active,no,
        MSA-A,alpha-gcc.test,Bengaluru,msa,,,,,2026-01-01,,,active,yes,
        SOW-B1,beta-gcc.test,Pune,sow,,project,fixed_price,800000,2027-01-01,2027-06-30,,active,no,
        """;

    static final String ENGINEERS = """
        employee_code,full_name,work_email,primary_role,seniority,skills,location,availability,available_from,consent_to_share_profile,notes
        E001,Engineer A,engineer.a@example-gcc.test,Frontend engineer,junior,React;TypeScript,Bengaluru,bench,,yes,
        E002,Engineer B,engineer.b@example-gcc.test,Backend engineer,senior,Java;Spring;AWS,Pune,bench,,yes,
        E003,Engineer C,engineer.c@example-gcc.test,Full-stack engineer,mid,Java;React,Hyderabad,notice,2027-02-01,no,
        """;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        com.codewalnut.bd.TestData.clear(jdbc);
        users.recordSignIn(ADMIN, "Admin Person");
        long ae = users.recordSignIn(AE, "Sales Person").id();
        users.setRoles(ae, EnumSet.of(Role.ACCOUNT_EXECUTIVE), ae);
    }

    private ResultActions upload(String who, String entity, String action, String fileName, byte[] content) throws Exception {
        return mvc.perform(multipart("/api/v1/imports/" + entity + "/" + action)
            .file(new MockMultipartFile("file", fileName, "text/csv", content))
            .with(user(who)).with(csrf()));
    }

    private ResultActions upload(String entity, String action, String csv) throws Exception {
        return upload(ADMIN, entity, action, entity + ".csv", csv.getBytes(StandardCharsets.UTF_8));
    }

    private void loadBaseData() throws Exception {
        upload("accounts", "commit", ACCOUNTS).andExpect(status().isOk());
        upload("contracts", "commit", CONTRACTS).andExpect(status().isOk());
        upload("engineers", "commit", ENGINEERS).andExpect(status().isOk());
    }

    @Test
    void validateSavesNothingAndCommitSavesEverything() throws Exception {
        upload("accounts", "validate", ACCOUNTS)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.valid").value(true))
            .andExpect(jsonPath("$.toCreate").value(3))
            .andExpect(jsonPath("$.committed").value(false));
        assertThat(count("account")).isZero();

        upload("accounts", "commit", ACCOUNTS)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.committed").value(true))
            .andExpect(jsonPath("$.toCreate").value(3));
        assertThat(count("account")).isEqualTo(3);
        assertThat(count("parent_company")).isEqualTo(2);
        // The owner named in the file is created as a user without roles.
        assertThat(users.findId("owner.one@example-gcc.test")).isPresent();
        assertThat(jdbc.sql("SELECT website_domain FROM account WHERE city = 'Bengaluru'").query(String.class).single())
            .isEqualTo("alpha-gcc.test");
    }

    @Test
    void uploadingTheSameFileTwiceUpdatesInsteadOfDuplicating() throws Exception {
        upload("accounts", "commit", ACCOUNTS).andExpect(status().isOk());
        upload("accounts", "commit", ACCOUNTS.replace("Retail,500-2000", "Retail and CPG,500-2000"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.toCreate").value(0))
            .andExpect(jsonPath("$.toUpdate").value(3));
        assertThat(count("account")).isEqualTo(3);
        assertThat(jdbc.sql("SELECT industry FROM account WHERE city = 'Bengaluru'").query(String.class).single())
            .isEqualTo("Retail and CPG");
    }

    @Test
    void aFileWithAnyBadRowSavesNothing() throws Exception {
        String bad = ACCOUNTS
            + "Gamma Chennai,Gamma,India,Chennai,gamma-gcc.test,IT,<500,,,customer,someone@gmail.com,web,\n"
            + "Alpha Again,Alpha Corp,USA,Bengaluru,alpha-gcc.test,Retail,,,,prospect,admin@example-gcc.test,web,\n";
        upload("accounts", "commit", bad)
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.committed").value(false))
            .andExpect(jsonPath("$.errors[?(@.column == 'relationship')].row").value(hasItem(5)))
            .andExpect(jsonPath("$.errors[?(@.column == 'owner_email')].message").value(hasItem(containsString("CodeWalnut address"))))
            .andExpect(jsonPath("$.errors[?(@.column == 'website_domain')].message").value(hasItem(containsString("Same domain and city as row 2"))));
        assertThat(count("account")).isZero();
        assertThat(jdbc.sql("SELECT status FROM import_batch").query(String.class).single()).isEqualTo("FAILED");
    }

    @Test
    void missingColumnsAreReportedOnTheHeaderRow() throws Exception {
        upload("accounts", "validate", "account_name,city\nX,Pune\n")
            .andExpect(jsonPath("$.valid").value(false))
            .andExpect(jsonPath("$.errors[0].row").value(1))
            .andExpect(jsonPath("$.errors[0].message").value(containsString("Missing columns")));
    }

    @Test
    void contractsLoadMsasBeforeSowsAndMarkClients() throws Exception {
        upload("accounts", "commit", ACCOUNTS).andExpect(status().isOk());
        upload("contracts", "commit", CONTRACTS).andExpect(status().isOk()).andExpect(jsonPath("$.toCreate").value(3));
        Long parent = jdbc.sql("SELECT parent_msa_id FROM contract WHERE contract_ref = 'SOW-A1'").query(Long.class).single();
        Long msa = jdbc.sql("SELECT id FROM contract WHERE contract_ref = 'MSA-A'").query(Long.class).single();
        assertThat(parent).isEqualTo(msa);
        // Beta Pune was a prospect; an active contract makes it a client.
        assertThat(jdbc.sql("SELECT relationship FROM account WHERE city = 'Pune'").query(String.class).single())
            .isEqualTo("CLIENT");
    }

    @Test
    void contactsNeedAnExistingAccount() throws Exception {
        upload("accounts", "commit", ACCOUNTS).andExpect(status().isOk());
        String contacts = """
            account_website_domain,account_city,full_name,title,persona,linkedin_url,email,phone,contact_city,source,consent_basis,notes
            alpha-gcc.test,Bengaluru,Person One,Head of Engineering,engineering_leader,https://www.linkedin.com/in/person-one-test,person.one@alpha-gcc.test,,Bengaluru,existing relationship,existing_relationship,
            unknown-gcc.test,Pune,Person Two,CTO,centre_head,,,,,event,business_contact,
            """;
        upload("contacts", "commit", contacts)
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.errors[0].row").value(3))
            .andExpect(jsonPath("$.errors[0].message").value(containsString("Upload it in 1-accounts first")));
        upload("contacts", "commit", contacts.lines().limit(2).reduce("", (a, b) -> a + b + "\n"))
            .andExpect(status().isOk());
        assertThat(count("contact")).isEqualTo(1);
    }

    @Test
    void seniorMaySplitAcrossTwoClientsButJuniorMayNot() throws Exception {
        loadBaseData();
        String deployments = """
            employee_code,account_website_domain,account_city,sow_ref,team_or_project,role,client_manager_name,allocation_percent,bill_rate,rate_unit,start_date,expected_end_date,status,notes
            E002,alpha-gcc.test,Bengaluru,SOW-A1,Payments,Senior backend engineer,Manager X,50,150000,month,2027-01-01,2027-12-31,active,
            E002,beta-gcc.test,Pune,SOW-B1,Platform,Backend engineer,Manager Y,50,4000,day,2027-01-01,2027-06-30,active,
            E001,alpha-gcc.test,Bengaluru,SOW-A1,Payments,Frontend engineer,,50,,,2027-01-01,2027-06-30,planned,
            E001,beta-gcc.test,Pune,,Platform,Frontend engineer,,50,,,2027-02-01,2027-06-30,planned,
            """;
        upload("deployments", "validate", deployments)
            .andExpect(jsonPath("$.valid").value(false))
            .andExpect(jsonPath("$.errorCount").value(1))
            .andExpect(jsonPath("$.errors[0].row").value(4))
            .andExpect(jsonPath("$.errors[0].message").value(containsString("E001")))
            .andExpect(jsonPath("$.errors[0].message").value(containsString("junior engineer may work for at most 1")));

        String fixed = deployments.lines().limit(4).reduce("", (a, b) -> a + b + "\n");
        upload("deployments", "commit", fixed).andExpect(status().isOk()).andExpect(jsonPath("$.toCreate").value(3));
    }

    @Test
    void allocationCheckIncludesDeploymentsAlreadyLoaded() throws Exception {
        loadBaseData();
        String first = """
            employee_code,account_website_domain,account_city,sow_ref,team_or_project,role,client_manager_name,allocation_percent,bill_rate,rate_unit,start_date,expected_end_date,status,notes
            E002,alpha-gcc.test,Bengaluru,SOW-A1,Payments,Senior backend engineer,,70,,,2027-01-01,2027-12-31,active,
            """;
        upload("deployments", "commit", first).andExpect(status().isOk());
        String second = """
            employee_code,account_website_domain,account_city,sow_ref,team_or_project,role,client_manager_name,allocation_percent,bill_rate,rate_unit,start_date,expected_end_date,status,notes
            E002,beta-gcc.test,Pune,SOW-B1,Platform,Backend engineer,,50,,,2027-03-01,2027-06-30,active,
            """;
        upload("deployments", "commit", second)
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.errors[0].message").value(containsString("120%")));
        // Re-uploading the first deployment at 50% replaces it, so the second then fits.
        upload("deployments", "commit", first.replace(",70,", ",50,")).andExpect(status().isOk())
            .andExpect(jsonPath("$.toUpdate").value(1));
        upload("deployments", "commit", second).andExpect(status().isOk());
    }

    @Test
    void deploymentsMustFitInsideTheirSow() throws Exception {
        loadBaseData();
        String deployments = """
            employee_code,account_website_domain,account_city,sow_ref,team_or_project,role,client_manager_name,allocation_percent,bill_rate,rate_unit,start_date,expected_end_date,status,notes
            E002,beta-gcc.test,Pune,SOW-B1,Platform,Backend engineer,,100,500000,,2027-01-01,2027-09-30,active,
            E002,beta-gcc.test,Pune,SOW-A1,Platform,Backend engineer,,100,,,2027-01-02,2027-03-30,active,
            """;
        upload("deployments", "validate", deployments)
            .andExpect(jsonPath("$.errors[?(@.column == 'expected_end_date')].message").value(hasItem(containsString("after the SOW ends"))))
            .andExpect(jsonPath("$.errors[?(@.column == 'rate_unit')].message").value(hasItem(containsString("Required with a bill rate"))))
            .andExpect(jsonPath("$.errors[?(@.column == 'sow_ref')].message").value(hasItem(containsString("different account"))));
    }

    @Test
    void xlsxFilesAreAccepted() throws Exception {
        byte[] xlsx;
        try (var wb = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("Engineers");
            String[] headers = ImportEntity.ENGINEERS.columns().toArray(String[]::new);
            var header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i].replace('_', ' ').toUpperCase());
            }
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("E900");
            row.createCell(1).setCellValue("Engineer Z");
            row.createCell(2).setCellValue("engineer.z@example-gcc.test");
            row.createCell(3).setCellValue("QA engineer");
            row.createCell(4).setCellValue("Lead");
            row.createCell(5).setCellValue("Playwright; Java");
            row.createCell(7).setCellValue("leave");
            var date = row.createCell(8);
            date.setCellValue(java.time.LocalDate.parse("2027-04-15"));
            var style = wb.createCellStyle();
            style.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("dd/mm/yyyy"));
            date.setCellStyle(style);
            row.createCell(9).setCellValue("Yes");
            wb.write(out);
            xlsx = out.toByteArray();
        }
        upload(ADMIN, "engineers", "commit", "engineers.xlsx", xlsx).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT available_from FROM engineer WHERE employee_code = 'E900'")
            .query(java.time.LocalDate.class).single()).isEqualTo("2027-04-15");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM engineer_skill").query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void ratesAreHiddenFromAccountExecutives() throws Exception {
        loadBaseData();
        upload("deployments", "commit", """
            employee_code,account_website_domain,account_city,sow_ref,team_or_project,role,client_manager_name,allocation_percent,bill_rate,rate_unit,start_date,expected_end_date,status,notes
            E002,alpha-gcc.test,Bengaluru,SOW-A1,Payments,Senior backend engineer,,100,150000,month,2027-01-01,2027-12-31,active,
            """).andExpect(status().isOk());

        mvc.perform(get("/api/v1/deployments").with(user(ADMIN)))
            .andExpect(jsonPath("$[0].billRateInr").value(150000.0));
        mvc.perform(get("/api/v1/deployments").with(user(AE)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].billRateInr").value(nullValue()));
        mvc.perform(get("/api/v1/contracts").with(user(AE)))
            .andExpect(jsonPath("$[0].totalValueInr").value(nullValue()));
    }

    @Test
    void accessRules() throws Exception {
        mvc.perform(get("/api/v1/accounts")).andExpect(status().isUnauthorized());
        // Signed in, but no role yet: only /me works.
        mvc.perform(get("/api/v1/me").with(user("new.joiner@example-gcc.test")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.roles").isEmpty());
        mvc.perform(get("/api/v1/accounts").with(user("new.joiner@example-gcc.test"))).andExpect(status().isForbidden());
        // Account executives can read but not upload.
        mvc.perform(get("/api/v1/accounts").with(user(AE))).andExpect(status().isOk());
        upload(AE, "accounts", "commit", "a.csv", ACCOUNTS.getBytes(StandardCharsets.UTF_8)).andExpect(status().isForbidden());
        // Uploads need the CSRF token.
        mvc.perform(multipart("/api/v1/imports/accounts/validate")
                .file(new MockMultipartFile("file", "a.csv", "text/csv", ACCOUNTS.getBytes(StandardCharsets.UTF_8)))
                .with(user(ADMIN)))
            .andExpect(status().isForbidden());
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }
}
