import { HttpParams } from '@angular/common/http';

/** Builds query parameters, leaving out anything that is undefined, null or an empty string (`false` and `0` are kept). */
export function toHttpParams(values: object): HttpParams {
  let params = new HttpParams();
  for (const [key, value] of Object.entries(values)) {
    if (value !== undefined && value !== null && value !== '') {
      params = params.set(key, String(value));
    }
  }
  return params;
}
