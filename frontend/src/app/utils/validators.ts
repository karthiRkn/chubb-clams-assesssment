import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

/** Rejects a number with more than `places` decimals (the backend stores money with 2). Empty values pass; use `required` for those. */
export function maxDecimals(places: number): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const value = control.value;
    if (value === null || value === undefined || value === '') {
      return null;
    }
    const text = String(value);
    const dot = text.indexOf('.');
    return dot >= 0 && text.length - dot - 1 > places ? { maxDecimals: { places } } : null;
  };
}

/** BCrypt silently cuts at 72 bytes, so the backend refuses longer passwords. Counted in UTF-8 bytes, not characters. */
export function maxBytes(limit: number): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null =>
    new TextEncoder().encode(String(control.value ?? '')).length > limit ? { maxBytes: { limit } } : null;
}

/** `Validators.required` accepts "   "; the backend does not. Flags text that is only whitespace. */
export const notBlank: ValidatorFn = (control: AbstractControl): ValidationErrors | null =>
  typeof control.value === 'string' && control.value.length > 0 && control.value.trim().length === 0
    ? { blank: true }
    : null;
