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

One Railway project with two environments, `staging` and `production`. In
each environment:

1. **Add a MySQL service** (Railway's MySQL template). Turn on its backups.
2. **Add the application service** from this GitHub repository. Railway reads
   `railway.json` and builds the `Dockerfile`. Staging deploys from `main`;
   production is promoted by hand.
3. **Set these variables** on the application service:

   | Variable | Value |
   |---|---|
   | `DATABASE_URL` | `jdbc:mysql://${{MySQL.MYSQLHOST}}:${{MySQL.MYSQLPORT}}/${{MySQL.MYSQLDATABASE}}` |
   | `DATABASE_USERNAME` | `${{MySQL.MYSQLUSER}}` |
   | `DATABASE_PASSWORD` | `${{MySQL.MYSQLPASSWORD}}` |
   | `GOOGLE_CLIENT_ID` | From the Google Cloud OAuth client (below) |
   | `GOOGLE_CLIENT_SECRET` | From the same client |
   | `ALLOWED_DOMAIN` | `codewalnut.com` |
   | `BOOTSTRAP_ADMINS` | Comma-separated emails to make Admin on first sign-in |

   Railway sets `PORT`; the application reads it.
4. **Generate a public domain** for the application service, for example
   `bd-platform-production.up.railway.app`, or attach a CodeWalnut subdomain.

### Google sign-in (ADR-0004)

In Google Cloud Console, under CodeWalnut's Workspace organisation:

1. Create a project, for example `codewalnut-bd-platform`.
2. OAuth consent screen: user type **Internal** (only CodeWalnut accounts).
3. Credentials → Create OAuth client ID → Web application. Authorised redirect
   URI: `https://<your-domain>/login/oauth2/code/google`, one per environment.
4. Put the client ID and secret into the Railway variables above.

### First sign-in

The people in `BOOTSTRAP_ADMINS` sign in first and become Admins. Everyone
else signs in, then an Admin gives them roles on the Users page. Upload data
on the Uploads page in the order shown.

## Region and data protection

Railway has no India region; use its nearest region (Southeast Asia,
Singapore) for both services. ADR-0007 records why this is acceptable for now
and when to revisit it.
