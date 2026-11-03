/**
 * A cell that starts with = + - or @ would be run as a formula when the file is opened in a spreadsheet (CSV injection),
 * so those are prefixed with an apostrophe. Values come from the backend, never typed by the person exporting.
 */
function cell(value: string | number): string {
  let text = String(value);
  if (/^[=+\-@\t\r]/.test(text) && typeof value === 'string') {
    text = `'${text}`;
  }
  return /[",\n\r]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

export function toCsv(rows: readonly (readonly (string | number)[])[]): string {
  return rows.map((row) => row.map(cell).join(',')).join('\r\n');
}

/** Saves the rows as a CSV file in the browser (UTF-8 with a BOM so Excel reads non-ASCII names correctly). */
export function downloadCsv(filename: string, rows: readonly (readonly (string | number)[])[]): void {
  const blob = new Blob(['﻿', toCsv(rows)], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}
