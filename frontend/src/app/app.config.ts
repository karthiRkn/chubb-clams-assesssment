import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideNativeDateAdapter } from '@angular/material/core';
import { MAT_FORM_FIELD_DEFAULT_OPTIONS } from '@angular/material/form-field';
import { provideRouter, TitleStrategy, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { provideCharts, withDefaultRegisterables } from 'ng2-charts';
import { AppTitleStrategy } from './app-title.strategy';
import { routes } from './app.routes';
import { errorInterceptor } from './interceptors/error.interceptor';
import { jwtInterceptor } from './interceptors/jwt.interceptor';
import { loadingInterceptor } from './interceptors/loading.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    // withComponentInputBinding: route params and query params arrive as component inputs (e.g. `claimId = input.required<string>()`).
    provideRouter(
      routes,
      withComponentInputBinding(),
      withInMemoryScrolling({ scrollPositionRestoration: 'enabled' }),
    ),
    { provide: TitleStrategy, useExisting: AppTitleStrategy },
    // Order matters: loading wraps everything, then the token is attached, and errors are turned into toasts last.
    provideHttpClient(withInterceptors([loadingInterceptor, jwtInterceptor, errorInterceptor])),
    provideNativeDateAdapter(),
    provideCharts(withDefaultRegisterables()),
    { provide: MAT_FORM_FIELD_DEFAULT_OPTIONS, useValue: { appearance: 'outline', floatLabel: 'auto' } },
  ],
};
