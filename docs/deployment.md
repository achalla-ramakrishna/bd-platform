# Running and deploying

## Run locally

Needs Java 25, Maven, Node 22 and a MySQL 8 server.

1. Create a database and user:

   ```sql
   CREATE DATABASE bd_platform CHARACTER SET utf8mb4;
   CREATE USER 'bd'@'localhost' IDENTIFIED BY 'bd';
   GRANT ALL ON bd_platform.* TO 'bd'@'localhost';
   ```

2. Start the back end with the `local` profile, which replaces Google sign-in
   with a form login (any `@example-gcc.test` address, password `local`;
   `admin@example-gcc.test` is made Admin):

   ```bash
   cd backend && SPRING_PROFILES_ACTIVE=local mvn spring-boot:run -DskipFrontend=true
   ```

3. Start the front end, which forwards API calls to port 8080:

   ```bash
   cd frontend && npm install && npm run dev
   ```

   Open http://localhost:5173.

Tests need a `bd_platform_test` database for the same user:
`cd backend && mvn verify` (also builds the React app into the jar).

## Deploy to Railway (ADR-0007)

One Railway service runs the app (API and React UI from one Docker image),
plus Railway's MySQL. Railway rebuilds on every push to the branch it watches.
This is the same setup as the CodeWalnut ATS.

### Quick start: preview with sample data (no Google setup)

1. **New Project → Deploy from GitHub repo →**
   `achalla-ramakrishna/bd-platform`. Railway uses `main`; to deploy a branch
   before it is merged, change it under app service **Settings → Source →
   Branch**.
2. In the same project: **+ New → Database → Add MySQL**.
3. Click the **app** service (not MySQL) → **Variables** → **Raw Editor**,
   paste these three lines, and set your own access code:

   ```
   MYSQL_URL=${{MySQL.MYSQL_URL}}
   SPRING_PROFILES_ACTIVE=preview
   PREVIEW_ACCESS_CODE=choose-a-long-code-here
   ```

   `MySQL` must match the database service's name on the canvas (type `${{`
   and Railway suggests it). The access code needs 12 or more characters; the
   app will not start without it.
4. App service → **Settings → Networking → Generate Domain**.
5. Wait for the deploy to turn green, open the domain, click **Sign in to the
   preview**, and sign in as `admin@example-gcc.test` with the access code.
   The Uploads page links to fictional sample files to try.

Preview accepts only fictional `@example-gcc.test` addresses, so nobody can
sign in as a real colleague, and every page shows a "sample data only"
banner. **Do not upload real client, contract or engineer data in preview.**

If a deploy fails: app service → **Deployments → View logs**; the last lines
say why. `https://<domain>/actuator/health` shows `{"status":"UP"}` when the
app is up.

### Later: real sign-in with Google (ADR-0004)

**Google Cloud Console**, signed in with a CodeWalnut Workspace admin account:

1. Create a project, for example "CodeWalnut BD Platform".
2. **APIs & Services → OAuth consent screen** → user type **Internal** (only
   CodeWalnut accounts can sign in; no Google review needed). Scopes:
   `openid`, `email`, `profile`.
3. **Credentials → Create credentials → OAuth client ID → Web application**.
   Authorised redirect URI:
   `https://<your-railway-domain>/login/oauth2/code/google`

**Railway variables** (app service): remove `SPRING_PROFILES_ACTIVE` and
`PREVIEW_ACCESS_CODE`, keep `MYSQL_URL`, and add:

```
ALLOWED_DOMAIN=codewalnut.com
BOOTSTRAP_ADMINS=you@codewalnut.com
GOOGLE_CLIENT_ID=<client id>
GOOGLE_CLIENT_SECRET=<client secret>
```

- `BOOTSTRAP_ADMINS`: comma-separated; they become Admin on first sign-in and
  give everyone else roles on the **Users** page.
- Use a fresh MySQL database (or empty the preview one) before loading real
  data, so no sample records remain.

Deploy logs show `Google sign-in: ENABLED for @codewalnut.com accounts.`
when it is set up, and `Google sign-in: DISABLED` in preview.

### Optional: AI features (ADR-0009, ADR-0010)

The same key switches on **Add from LinkedIn** profile reading, **account research** and **Find GCCs**.

Add one variable to the app service. If the ATS already has a key, reference it:

```
ANTHROPIC_API_KEY=${{agentic-ats-codewalnut.ANTHROPIC_API_KEY}}
```

or create a key at console.anthropic.com (API keys). Deploy logs then show
`AI profile reading: ENABLED` and `AI account research: ENABLED`. Without it,
**Add from LinkedIn** still works as a form filled in by hand, and research is
switched off.

### Optional: research upload by script (ADR-0010)

Lets a script (for example a Claude Code session) upload account research
without signing in. Add two variables to the app service:

```
RESEARCH_UPLOAD_TOKEN=<a random string of 32 or more characters>
RESEARCH_UPLOAD_AS=<the email of a user with a pipeline role>
```

The token works only for `POST /api/v1/research/uploads`, and the upload is
recorded as that user. Deploy logs show `Research upload token: ENABLED`.
Each entry's `summary` may carry `"fitRating": "STRONG" | "MEDIUM" | "WEAK"`,
which counts towards the account fit score (ADR-0012).
An entry may also carry `"accountFacts": {"yearEstablished", "headcountBand",
"employeeCount", "openRoles", "openRolesAsOf", "openRolesSource"}`; any field
given updates the account, and missing ones keep what is stored.
Delete the variable to switch it off; change it if it may have leaked.

### Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Variables changed but nothing happens | Railway stages variable edits ("Apply N changes") | Click **Deploy** |
| Log says `Preview mode needs PREVIEW_ACCESS_CODE…` | Access code missing or under 12 characters | Set it, Deploy |
| Deploy fails right after editing variables | `MYSQL_URL` deleted in the Raw Editor | Add `MYSQL_URL=${{MySQL.MYSQL_URL}}` back |
| Google: `redirect_uri_mismatch` | Redirect URI not registered exactly | Add `https://<domain>/login/oauth2/code/google`, no trailing slash |
| Google: `invalid_client` | Client ID mistyped or from another project | Copy it with Google's copy button |
| Signed in but see "Almost there" | No role yet | An Admin ticks a role on **Users** |

### Reference

- Instead of `MYSQL_URL` you can set `DATABASE_URL` (JDBC form),
  `DATABASE_USERNAME` and `DATABASE_PASSWORD`; `DATABASE_URL` wins if both are set.
- Flyway creates and upgrades the schema on each start.
- Railway sets `PORT`; the app listens on it. Health check: `/actuator/health`
  (in `railway.json`).

## Region and data protection

Railway has no India region; use its nearest region (Southeast Asia,
Singapore) for both services. ADR-0007 records why this is acceptable for now
and when to revisit it.
