import type { ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { StrategyPage } from './pages/StrategyPage';
import { ConversationsPage } from './pages/ConversationsPage';
import { ConversationPage } from './pages/ConversationPage';
import { ConnectionsPage } from './pages/ConnectionsPage';
import { ConnectionPage } from './pages/ConnectionPage';
import { ImprovementPage, ImprovementsPage } from './pages/ImprovementsPage';
import { EventPage, EventsPage } from './pages/EventsPage';
import { HeatmapPage } from './pages/HeatmapPage';
import { InterviewPage, InterviewsPage } from './pages/InterviewsPage';
import { NavLink, Navigate, Route, Routes } from 'react-router';
import { ApiError, api, type Me, type SignInInfo } from './lib/api';
import { AccountsPage } from './pages/AccountsPage';
import { AccountDetailPage } from './pages/AccountDetailPage';
import { EngineersPage } from './pages/EngineersPage';
import { DeploymentsPage } from './pages/DeploymentsPage';
import { UploadsPage } from './pages/UploadsPage';
import { UsersPage } from './pages/UsersPage';
import { PipelinePage } from './pages/PipelinePage';
import { OpportunityPage } from './pages/OpportunityPage';
import { FindGccsPage } from './pages/FindGccsPage';

export function App() {
  const me = useQuery({ queryKey: ['me'], queryFn: () => api.get<Me>('/me') });
  const signIn = useSignInInfo();

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
        <NavGroup label="Plan">
          <NavLink to="/strategy">Strategy</NavLink>
          <NavLink to="/heatmap">Heatmap</NavLink>
        </NavGroup>
        <NavGroup label="Network">
          <NavLink to="/connections">Connections</NavLink>
          <NavLink to="/conversations">Conversations</NavLink>
          <NavLink to="/events">Conferences</NavLink>
          <NavLink to="/interviews">Interviews</NavLink>
        </NavGroup>
        <NavGroup label="Sales">
          <NavLink to="/accounts">Accounts</NavLink>
          <NavLink to="/find-gccs">Find GCCs</NavLink>
          <NavLink to="/pipeline">Pipeline</NavLink>
        </NavGroup>
        <NavGroup label="Delivery">
          <NavLink to="/deployments">Deployed teams</NavLink>
          <NavLink to="/engineers">Engineers</NavLink>
        </NavGroup>
        <NavGroup label="Platform">
          <NavLink to="/improvements">Improvement areas</NavLink>
          {user.canImport && <NavLink to="/uploads">Uploads</NavLink>}
          {user.roles.includes('ADMIN') && <NavLink to="/users">Users</NavLink>}
        </NavGroup>
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
        {signIn.data?.preview && (
          <div className="notice warn" role="status">
            Preview with sample data only. Do not upload real client, contract or engineer data until Google sign-in is
            set up.
          </div>
        )}
        <Routes>
          <Route path="/" element={<Navigate to="/pipeline" replace />} />
          <Route path="/strategy" element={<StrategyPage />} />
          <Route path="/pipeline" element={<PipelinePage me={user} />} />
          <Route path="/opportunities/new" element={<OpportunityPage me={user} />} />
          <Route path="/opportunities/:id" element={<OpportunityPage me={user} />} />
          <Route path="/accounts" element={<AccountsPage me={user} />} />
          <Route path="/interviews" element={<InterviewsPage me={user} />} />
          <Route path="/interviews/:id" element={<InterviewPage me={user} />} />
          <Route path="/heatmap" element={<HeatmapPage />} />
          <Route path="/events" element={<EventsPage me={user} />} />
          <Route path="/events/:id" element={<EventPage me={user} />} />
          <Route path="/improvements" element={<ImprovementsPage />} />
          <Route path="/improvements/:id" element={<ImprovementPage />} />
          <Route path="/connections" element={<ConnectionsPage me={user} />} />
          <Route path="/connections/:id" element={<ConnectionPage me={user} />} />
          <Route path="/conversations" element={<ConversationsPage me={user} />} />
          <Route path="/conversations/:id" element={<ConversationPage me={user} />} />
          <Route path="/find-gccs" element={<FindGccsPage me={user} />} />
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

export function useSignInInfo() {
  return useQuery({ queryKey: ['sign-in'], queryFn: () => api.get<SignInInfo>('/public/sign-in'), staleTime: Infinity });
}

function SignIn() {
  const info = useSignInInfo();
  return (
    <div className="centre">
      <div className="panel">
        <h1>CodeWalnut BD</h1>
        <p className="lead">Accounts, pipeline and deployed teams for CodeWalnut's business development.</p>
        {info.data && (
          <>
            <a className="button" href={info.data.url}>
              {info.data.preview ? 'Sign in to the preview' : info.data.devLogin ? 'Sign in (local development)' : 'Sign in with Google'}
            </a>
            <p className="muted" style={{ marginTop: 14 }}>
              {info.data.preview
                ? 'Use admin@example-gcc.test, or any other @example-gcc.test address, with the access code you were given.'
                : `Use your @${info.data.allowedDomain} account.`}
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

/** A labelled group of menu links. */
function NavGroup({ label, children }: { label: string; children: ReactNode }) {
  const id = `nav-${label.toLowerCase()}`;
  return (
    <div className="nav-group" role="group" aria-labelledby={id}>
      <div className="nav-heading" id={id}>{label}</div>
      {children}
    </div>
  );
}
