import { formatMoney, MoneyPipe } from './money.pipe';

/** Intl puts a non-breaking space between the code and the amount; compare with a normal one for readability. */
const plain = (text: string) => text.replace(/ /g, ' ');

describe('formatMoney', () => {
  it('shows the 3-letter code, a space, and the amount', () => {
    expect(plain(formatMoney(2400.5, 'SGD'))).toBe('SGD 2,400.50');
    expect(plain(formatMoney(18000, 'HKD'))).toBe('HKD 18,000.00');
  });

  it('never mistakes one dollar for another (SGD, HKD and AUD all use "$")', () => {
    const labels = ['SGD', 'HKD', 'AUD'].map((code) => plain(formatMoney(1, code)));
    expect(new Set(labels).size).toBe(3);
    expect(labels.every((l) => !l.includes('$'))).toBe(true);
  });

  it("uses each currency's own number of decimals", () => {
    expect(plain(formatMoney(1500, 'JPY'))).toBe('JPY 1,500');
  });

  it('keeps the sign of a negative amount', () => {
    expect(plain(formatMoney(-5, 'SGD'))).toContain('-');
  });

  it('is empty when there is no amount or no currency', () => {
    expect(formatMoney(null, 'SGD')).toBe('');
    expect(formatMoney(undefined, 'SGD')).toBe('');
    expect(formatMoney(10, '')).toBe('');
    expect(formatMoney(10, null)).toBe('');
  });

  it('still shows something sensible for a code the runtime rejects', () => {
    expect(plain(formatMoney(1234, 'US'))).toBe('US 1,234.00');
  });

  it('is available as a pipe', () => {
    expect(plain(new MoneyPipe().transform(10, 'INR'))).toBe('INR 10.00');
  });
});
