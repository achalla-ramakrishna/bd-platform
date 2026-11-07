import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ROLE_LABELS, api, type Me, type Role, type UserView } from '../lib/api';
import { formatDate } from '../lib/format';

const ROLES = Object.keys(ROLE_LABELS) as Role[];

export function UsersPage({ me }: { me: Me }) {
  const queryClient = useQueryClient();
  const users = useQuery({ queryKey: ['users'], queryFn: () => api.get<UserView[]>('/admin/users') });
  const save = useMutation({
    mutationFn: ({ id, roles }: { id: number; roles: Role[] }) => api.put<UserView>(`/admin/users/${id}/roles`, roles),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['users'] }),
  });

  if (!me.roles.includes('ADMIN')) return <p>Only Admins can manage users.</p>;

  return (
    <>
      <h1>Users and roles</h1>
      <p className="lead">
        People appear here after they first sign in, or when an upload names them as an account owner. Tick the roles
        each person needs. Rates and contract values are visible only to Leadership, BD lead, Delivery and Admin.
      </p>
      {save.error && <div className="notice bad">{save.error.message}</div>}
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Person</th>
              {ROLES.map((r) => <th key={r}>{ROLE_LABELS[r]}</th>)}
              <th>Last sign-in</th>
            </tr>
          </thead>
          <tbody>
            {users.data?.map((u) => (
              <tr key={u.id}>
                <td>{u.name}<span className="sub">{u.email}</span></td>
                {ROLES.map((r) => (
                  <td key={r}>
                    <input
                      type="checkbox"
                      id={`role-${u.id}-${r}`}
                      aria-label={`${ROLE_LABELS[r]} for ${u.name}`}
                      checked={u.roles.includes(r)}
                      disabled={save.isPending || (u.id === me.id && r === 'ADMIN')}
                      onChange={(e) => {
                        const roles = e.target.checked ? [...u.roles, r] : u.roles.filter((x) => x !== r);
                        save.mutate({ id: u.id, roles });
                      }}
                    />
                  </td>
                ))}
                <td>{u.lastLoginAt ? formatDate(u.lastLoginAt) : <span className="muted">Never</span>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
