import { Pipe, PipeTransform } from '@angular/core';

const formatters = new Map<string, Intl.NumberFormat>();

function formatterFor(currency: string): Intl.NumberFormat | null {
  const cached = formatters.get(currency);
  if (cached) {
    return cached;
  }
  try {
    // currencyDisplay "code": SGD, HKD and AUD all use "$", so the 3-letter code is the only unambiguous label.
    // Intl also knows each currency's own decimal places and puts a non-breaking space between code and amount.
    const formatter = new Intl.NumberFormat('en', { style: 'currency', currency, currencyDisplay: 'code' });
    formatters.set(currency, formatter);
    return formatter;
  } catch {
    return null;
  }
}

/** "SGD 2,400.50". An empty string for a missing amount; a plain "CODE 1,234.00" if the runtime does not know the code. */
export function formatMoney(value: number | null | undefined, currency: string | null | undefined): string {
  if (value === null || value === undefined || !currency) {
    return '';
  }
  const formatter = formatterFor(currency);
  return formatter
    ? formatter.format(value)
    : `${currency} ${value.toLocaleString('en', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

/** `{{ claim.claimedAmount | money: claim.currency }}` - money is always shown with its own currency code, never summed or mixed. */
@Pipe({ name: 'money' })
export class MoneyPipe implements PipeTransform {
  transform(value: number | null | undefined, currency: string | null | undefined): string {
    return formatMoney(value, currency);
  }
}
