# LxDay Admin

This directory contains the Vue 3 operations console for LxDay. It is not a generic template: the pages are backed by the Go API and cover dashboard data, users, pair bindings, albums, app releases, notifications, storage, runtime settings, active listen-together rooms, audit logs, and network logs.

The production image builds this app and embeds `dist` into the server binary. `dist` is generated output and must not be committed.

## Stack

- Vue 3 + TypeScript + Vite
- Element Plus + Tailwind CSS
- Pinia + persisted state
- ESLint, Prettier, Stylelint and `vue-tsc`

## Requirements

- Node.js >= 20.19
- npm (the committed `package-lock.json` is the dependency lockfile)

## Commands

```bash
npm ci                 # install exactly from package-lock.json
npm run dev            # local development
npm run build          # type-check and production build
npm run lint           # ESLint
```

The API base path is configured by the Vite environment used by the deployment. In production the embedded SPA and API share the same origin, which avoids a second public frontend service.

## Security notes

- The admin access token is kept in `sessionStorage` and is cleared when the browser session ends.
- Authentication and authorization are enforced by the server; route guards only improve navigation UX.
- Destructive actions use the shared confirmation components and still require server-side permission checks.
- The console uses a restrained neumorphic surface: raised cards for navigation and inset fields/buttons for state changes. Mobile operation buttons keep a 44px minimum hit area and remain usable without hover.
- Do not place `JWT_SECRET`, SMTP credentials, storage credentials, `APP_KEY`, or any access token in source, `.env` committed files, screenshots, or documentation.

For repository-wide development and release checks, read [../docs/DEVELOPMENT.md](../docs/DEVELOPMENT.md) and [../AGENTS.md](../AGENTS.md).
