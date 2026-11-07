import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type ImportBatch, type ImportReport } from '../lib/api';
import { formatDate, humanise } from '../lib/format';

/** The five templates, in the order they must be loaded (ADR-0006, docs/data-import). */
export const STEPS = [
  { entity: 'accounts', file: '1-accounts.csv', title: 'Accounts', text: 'Every client, past client and target centre. One row per centre.' },
  { entity: 'contacts', file: '2-contacts.csv', title: 'Contacts', text: 'People at those accounts. Needs the accounts loaded first.' },
  { entity: 'contracts', file: '3-contracts.csv', title: 'Contracts', text: 'MSAs and SOWs. MSAs can be in the same file as their SOWs.' },
  { entity: 'engineers', file: '4-engineers.csv', title: 'Engineers', text: 'Everyone, deployed or on the bench.' },
  { entity: 'deployments', file: '5-deployments.csv', title: 'Deployments', text: 'Who works where. Checks the one-client rule for junior and mid-level engineers.' },
] as const;

export function UploadsPage() {
  const history = useQuery({ queryKey: ['imports'], queryFn: () => api.get<ImportBatch[]>('/imports') });
  const [open, setOpen] = useState<string>('accounts');

  const loaded = new Set(history.data?.filter((b) => b.status === 'COMMITTED').map((b) => b.entityType));

  return (
    <>
      <h1>Upload starting data</h1>
      <p className="lead">
        Load your spreadsheets in this order. Each file is checked first and nothing is saved until every row passes.
        Uploading the same file again updates records instead of duplicating them.
      </p>
      <div className="steps">
        {STEPS.map((step, i) => (
          <UploadStep
            key={step.entity}
            index={i + 1}
            step={step}
            open={open === step.entity}
            loaded={loaded.has(step.entity)}
            onOpen={() => setOpen(step.entity)}
          />
        ))}
      </div>

      <h2>Upload history</h2>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>When</th>
              <th>Type</th>
              <th>File</th>
              <th>Result</th>
              <th className="num">Rows</th>
              <th className="num">Created</th>
              <th className="num">Updated</th>
              <th>By</th>
            </tr>
          </thead>
          <tbody>
            {history.data?.length === 0 && (
              <tr><td colSpan={8} className="muted">No uploads yet. Start with accounts.</td></tr>
            )}
            {history.data?.map((b) => (
              <tr key={b.id}>
                <td>{formatDate(b.uploadedAt)}</td>
                <td>{humanise(b.entityType)}</td>
                <td><code>{b.fileName}</code></td>
                <td>
                  {b.status === 'COMMITTED'
                    ? <span className="pill good">Loaded</span>
                    : <span className="pill bad">{b.failedCount} rows failed</span>}
                </td>
                <td className="num">{b.totalRows}</td>
                <td className="num">{b.createdCount}</td>
                <td className="num">{b.updatedCount}</td>
                <td>{b.uploadedBy}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}

function UploadStep({ index, step, open, loaded, onOpen }: {
  index: number;
  step: (typeof STEPS)[number];
  open: boolean;
  loaded: boolean;
  onOpen: () => void;
}) {
  const queryClient = useQueryClient();
  const [file, setFile] = useState<File | null>(null);
  const [report, setReport] = useState<ImportReport | null>(null);

  const validate = useMutation({
    mutationFn: (f: File) => api.upload<ImportReport>(`/imports/${step.entity}/validate`, f),
    onSuccess: setReport,
  });
  const commit = useMutation({
    mutationFn: (f: File) => api.upload<ImportReport>(`/imports/${step.entity}/commit`, f),
    onSuccess: (r) => {
      setReport(r);
      queryClient.invalidateQueries();
    },
  });
  const error = validate.error ?? commit.error;
  const busy = validate.isPending || commit.isPending;

  return (
    <section className={`step${open ? ' open' : ''}`} aria-labelledby={`step-${step.entity}`}>
      <div className="num">{index}</div>
      <div>
        <h3 id={`step-${step.entity}`}>
          {step.title} {loaded && <span className="pill good">Loaded</span>}
        </h3>
        <p>{step.text}</p>
        {!open ? (
          <button type="button" className="secondary" onClick={onOpen}>Upload {step.title.toLowerCase()}</button>
        ) : (
          <>
            <div className="actions">
              <a className="button secondary" href={`/templates/${step.file}`} download>Download template</a>
              <input
                id={`file-${step.entity}`}
                type="file"
                accept=".csv,.xlsx"
                aria-label={`${step.title} file`}
                onChange={(e) => {
                  setFile(e.target.files?.[0] ?? null);
                  setReport(null);
                }}
              />
              <button type="button" disabled={!file || busy} onClick={() => file && validate.mutate(file)}>
                {validate.isPending ? 'Checking…' : 'Check file'}
              </button>
              <button
                type="button"
                disabled={!file || busy || !report?.valid || report.committed}
                onClick={() => file && commit.mutate(file)}
              >
                {commit.isPending ? 'Loading…' : 'Load into the app'}
              </button>
            </div>
            {error && <div className="notice bad">{error.message}</div>}
            {report && <ReportView report={report} />}
          </>
        )}
      </div>
    </section>
  );
}

export function ReportView({ report }: { report: ImportReport }) {
  if (report.committed) {
    return (
      <div className="notice good">
        Loaded {report.totalRows} rows from {report.fileName}: {report.toCreate} created, {report.toUpdate} updated.
      </div>
    );
  }
  if (report.valid) {
    return (
      <div className="notice good">
        All {report.totalRows} rows passed. Loading will create {report.toCreate} and update {report.toUpdate} records.
      </div>
    );
  }
  return (
    <>
      <div className="notice bad">
        {report.errorCount} {report.errorCount === 1 ? 'problem' : 'problems'} found. Nothing has been saved. Fix these
        in your spreadsheet and check the file again.
      </div>
      <div className="table-wrap">
        <table>
          <thead>
            <tr><th className="num">Row</th><th>Column</th><th>Value</th><th>Problem</th></tr>
          </thead>
          <tbody>
            {report.errors.map((e, i) => (
              <tr key={i}>
                <td className="num">{e.row}</td>
                <td>{e.column ? <code>{e.column}</code> : '—'}</td>
                <td>{e.value ?? ''}</td>
                <td>{e.message}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {report.errorCount > report.errors.length && (
        <p className="muted">Showing the first {report.errors.length} problems.</p>
      )}
    </>
  );
}
