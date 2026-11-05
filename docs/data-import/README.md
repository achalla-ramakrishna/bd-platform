# Uploading starting data

Status: **draft for review**. How CodeWalnut's current business gets into the
application (FR-EXI-01 to FR-EXI-07, ADR-0006).

## Steps

1. Copy the five templates in [templates/](templates/) into a private Google
   Sheet or Excel workbook. **Do not fill them in inside this repository.**
2. Fill one sheet per template, following the column rules below.
3. When the application is ready, upload them in order: 1 accounts, 2 contacts,
   3 contracts, 4 engineers, 5 deployments.
4. Fix any rows the validation report lists, and upload again. A file loads
   only when every row passes.

Uploading the same file again is safe: rows that match an existing record by
its key update it; nothing is duplicated.

## General rules

- Dates: `YYYY-MM-DD`, for example `2027-03-31`.
- Lists inside one cell (skills, tech stack): separate with `;`, for example
  `Java;React;AWS`.
- Picklist columns accept only the values listed below (case does not matter).
- Money: numbers only, no commas or currency symbols; currency in its own
  column as a three-letter code (`INR`, `USD`, `EUR`, `GBP`).
- An account is identified by `website_domain` + `city` everywhere
  (BR-BD-01). Use the domain only: `example.com`, not `https://www.example.com/`.

## 1-accounts.csv

One row per centre (a parent with centres in two cities has two rows). Include
current clients, past clients and target accounts.

| Column | Required | Rule |
|---|---|---|
| account_name | Yes | Centre name as you refer to it |
| parent_company | Yes | Parent company's name |
| parent_hq_country | No | Country name |
| city | Yes | Bengaluru, Hyderabad, Pune, Chennai, Delhi-NCR, Mumbai, or other city name |
| website_domain | Yes | Parent or centre domain |
| industry | No | Free text for now; becomes a picklist after the first load |
| headcount_band | No | `<500`, `500-2000`, `2000-5000`, `5000+` |
| year_established | No | Four-digit year |
| tech_stack | No | `;`-separated list |
| relationship | Yes | `client`, `past_client`, `prospect` |
| owner_email | Yes | CodeWalnut email of the account owner |
| source | Yes | Where the record came from, for example `existing client`, `referral`, `Nasscom report` |
| notes | No | |

## 2-contacts.csv

| Column | Required | Rule |
|---|---|---|
| account_website_domain, account_city | Yes | Must match an account |
| full_name | Yes | |
| title | No | Job title |
| persona | Yes | `centre_head`, `engineering_leader`, `product_leader`, `talent_acquisition`, `vendor_management`, `other` |
| linkedin_url | No | Profile URL, copied by hand (no scraping, ADR-0005) |
| email, phone | No | Business contact details only |
| contact_city | No | |
| source | Yes | For example `existing relationship`, `event`, `referral`, `LinkedIn` |
| consent_basis | Yes | `existing_relationship`, `business_contact`, `consented` |
| notes | No | |

## 3-contracts.csv

Load MSAs before the SOWs that sit under them (same file is fine: MSA rows
first).

| Column | Required | Rule |
|---|---|---|
| contract_ref | Yes | Your reference; unique |
| account_website_domain, account_city | Yes | Must match an account |
| contract_type | Yes | `msa`, `sow` |
| parent_msa_ref | For SOWs, if an MSA exists | `contract_ref` of the MSA |
| work_type | For SOWs | `staff_augmentation`, `project` |
| pricing_model | For SOWs | `monthly_rate`, `time_and_materials`, `fixed_price` |
| currency | With a value | `INR`, `USD`, `EUR`, `GBP` |
| total_value | No | Number |
| start_date | Yes | |
| end_date | For SOWs | |
| renewal_date | No | |
| status | Yes | `draft`, `active`, `ended` |
| client_permission_to_name | Yes | `yes`, `no` (BR-BD-11) |
| notes | No | |

## 4-engineers.csv

All engineers, whether deployed or on the bench.

| Column | Required | Rule |
|---|---|---|
| employee_code | Yes | Unique |
| full_name | Yes | |
| work_email | Yes | CodeWalnut email |
| primary_role | Yes | For example `Frontend engineer`, `Backend engineer`, `Full-stack engineer`, `QA engineer`, `Data engineer`, `Engineering manager` |
| seniority | Yes | `junior`, `mid`, `senior`, `lead`, `principal` |
| skills | Yes | `;`-separated list |
| location | No | City |
| availability | Yes | `deployed`, `bench`, `notice`, `leave` |
| available_from | For notice or leave | Date |
| consent_to_share_profile | Yes | `yes`, `no` (BR-BD-10) |
| notes | No | |

## 5-deployments.csv

One row per engineer per client assignment, current and recently ended.

| Column | Required | Rule |
|---|---|---|
| employee_code | Yes | Must match an engineer |
| account_website_domain, account_city | Yes | Must match an account |
| sow_ref | No | `contract_ref` of the SOW, if one exists |
| team_or_project | No | Client team or project name |
| role | Yes | Role at the client |
| client_manager_name | No | |
| bill_rate | No | Number |
| rate_unit | With a rate | `month`, `day`, `hour` |
| currency | With a rate | `INR`, `USD`, `EUR`, `GBP` |
| start_date | Yes | |
| expected_end_date | No | Drives end-date alerts |
| status | Yes | `planned`, `active`, `ended` |
| notes | No | |

## Data protection

These files hold client names, contract values, bill rates and personal data.
Keep them in CodeWalnut's private Google Drive, share them only with the people
loading them, and never commit them to this repository (the `.gitignore` blocks
spreadsheet files outside `templates/`).
