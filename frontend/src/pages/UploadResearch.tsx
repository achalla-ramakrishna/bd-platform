import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ApiError, api } from '../lib/api';

type Saved = { accountId: number; websiteDomain: string; city: string };

/**
 * Upload research written outside the app (a JSON file), matched to accounts by website and city.
 * Works without ANTHROPIC_API_KEY (ADR-0010).
 */
export function UploadResearch() {
  const queryClient = useQueryClient();
  const [readError, setReadError] = useState<string>();
  const upload = useMutation({
    mutationFn: (body: object) => api.post<Saved[]>('/research/uploads', body),
    onSuccess: (saved) => {
      saved.forEach((s) => queryClient.invalidateQueries({ queryKey: ['research', s.accountId] }));
      queryClient.invalidateQueries({ queryKey: ['accounts'] });
    },
  });

  async function onFile(file: File | undefined) {
    setReadError(undefined);
    upload.reset();
    if (!file) return;
    try {
      upload.mutate(JSON.parse(await file.text()));
    } catch {
      setReadError('That file is not valid JSON. Use the research file exactly as you received it.');
    }
  }

  const problems = upload.error instanceof ApiError && upload.error.problems.length ? upload.error.problems : null;
  return (
    <div className="research-upload">
      <label className="button secondary">
        {upload.isPending ? 'Uploading…' : 'Upload research'}
        <input type="file" accept=".json,application/json" hidden disabled={upload.isPending}
          onChange={(e) => { void onFile(e.target.files?.[0]); e.target.value = ''; }} />
      </label>
      {readError && <div className="notice bad" role="alert">{readError}</div>}
      {problems && <div className="notice bad" role="alert"><p>Nothing was saved:</p><ul>{problems.map((p) => <li key={p}>{p}</li>)}</ul></div>}
      {upload.error && !problems && <div className="notice bad" role="alert">{upload.error.message}</div>}
      {upload.data && (
        <div className="notice good" role="status">
          Research saved for {upload.data.length} account{upload.data.length === 1 ? '' : 's'}: {upload.data.map((s) => s.websiteDomain).join(', ')}. Open an account to read it.
        </div>
      )}
    </div>
  );
}
