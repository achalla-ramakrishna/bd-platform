import { useQuery } from '@tanstack/react-query';
import { NavLink, Navigate, Route, Routes } from 'react-router';
import { ApiError, api, type Me, type SignInInfo } from './lib/api';
import { AccountsPage } from './pages/AccountsPage';
import { AccountDetailPage } from './pages/AccountDetailPage';
import { EngineersPage } from './pages/EngineersPage';
import { DeploymentsPage } from './pages/DeploymentsPage';
import { UploadsPage } from './pages/UploadsPage';
import { UsersPage } from './pages/UsersPage';

export function App() {
  const me = useQuery({ queryKey: ['me'], queryFn: () => api.get<Me>('/me') });

  if (me.isPending) {
    return <div className="centre muted">Loading…</div>;
  }
  if (me.error instanceof ApiError && me.error.status === 401) {
    return <SignIn />;
  }
  if (me.error) {
    return <div className="centre"><div className="notice bad">Could not reach the server: {me.error.message}</div></div>;
  }
  const user = me.data;
  if (user.roles.length === 0) {
    return <NoRole user={user} />;
  }

  return (
    <div className="shell">
      <nav className="nav" aria-label="Main">
        <div className="brand">CodeWalnut <span>BD</span></div>
        <NavLink to="/accounts">Accounts</NavLink>
        <NavLink to="/deployments">Deployed teams</NavLink>
        <NavLink to="/engineers">Engineers</NavLink>
        {user.canImport && <NavLink to="/uploads">Uploads</NavLink>}
        {user.roles.includes('ADMIN') && <NavLink to="/users">Users</NavLink>}
        <div className="who">
          {user.name}
          <br />
          <form method="post" action="/logout" style={{ display: 'inline' }}>
            <input type="hidden" name="_csrf" value={csrfFromCookie()} />
            <button type="submit" className="secondary" style={{ padding: '2px 8px', marginTop: 8, fontSize: 12 }}>
              Sign out
            </button>
          </form>
        </div>
      </nav>
      <main>
        <Routes>
          <Route path="/" element={<Navigate to={user.canImport ? '/uploads' : '/accounts'} replace />} />
          <Route path="/accounts" element={<AccountsPage />} />
          <Route path="/accounts/:id" element={<AccountDetailPage me={user} />} />
          <Route path="/deployments" element={<DeploymentsPage me={user} />} />
          <Route path="/engineers" element={<EngineersPage />} />
          <Route path="/uploads" element={user.canImport ? <UploadsPage /> : <Navigate to="/accounts" replace />} />
          <Route path="/users" element={<UsersPage me={user} />} />
          <Route path="*" element={<p>Page not found.</p>} />
        </Routes>
      </main>
    </div>
  );
}

function csrfFromCookie(): string {
  const match = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='));
  return match ? decodeURIComponent(match.substring('XSRF-TOKEN='.length)) : '';
}

function SignIn() {
  const info = useQuery({ queryKey: ['sign-in'], queryFn: () => api.get<SignInInfo>('/public/sign-in') });
  return (
    <div className="centre">
      <div className="panel">
        <h1>CodeWalnut BD</h1>
        <p className="lead">Accounts, pipeline and deployed teams for CodeWalnut's business development.</p>
        {info.data && (
          <>
            <a className="button" href={info.data.url}>
              {info.data.devLogin ? 'Sign in (local development)' : 'Sign in with Google'}
            </a>
            <p className="muted" style={{ marginTop: 14 }}>
              Use your @{info.data.allowedDomain} account.
            </p>
          </>
        )}
      </div>
    </div>
  );
}

function NoRole({ user }: { user: Me }) {
  return (
    <div className="centre">
      <div className="panel">
        <h1>Almost there</h1>
        <p className="lead">
          You are signed in as {user.email}, but you have no role yet. Ask an Admin to give you one on the Users page.
        </p>
      </div>
    </div>
  );
}
