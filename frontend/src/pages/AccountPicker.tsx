import { AccountCombo } from './AccountCombo';

/** Choose an account: a searchable list with clients first, then all accounts (see AccountCombo). */
export function AccountPicker({ id, value, onChange, placeholder }: {
  id: string; value: number | null; valueName?: string | null; onChange: (id: number | null, name: string | null) => void;
  placeholder?: string;
}) {
  return <AccountCombo id={id} value={value} onChange={onChange} placeholder={placeholder} />;
}
