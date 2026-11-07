import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ReportView } from './UploadsPage';

describe('ReportView', () => {
  it('lists each problem with its row and says nothing was saved', () => {
    render(
      <ReportView
        report={{
          entityType: 'deployments', fileName: 'd.csv', totalRows: 3, valid: false, toCreate: 0, toUpdate: 0,
          committed: false, errorCount: 1,
          errors: [{ row: 4, column: 'allocation_percent', message: 'E001: a junior engineer may work for at most 1' }],
        }}
      />,
    );
    expect(screen.getByText(/Nothing has been saved/)).toBeTruthy();
    expect(screen.getByText('4')).toBeTruthy();
    expect(screen.getByText(/junior engineer may work for at most 1/)).toBeTruthy();
  });

  it('confirms what a valid file will do', () => {
    render(
      <ReportView
        report={{
          entityType: 'accounts', fileName: 'a.csv', totalRows: 3, valid: true, toCreate: 2, toUpdate: 1,
          committed: false, errorCount: 0, errors: [],
        }}
      />,
    );
    expect(screen.getByText(/will create 2 and update 1/)).toBeTruthy();
  });
});
