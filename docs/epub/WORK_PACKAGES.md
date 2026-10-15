# Native EPUB work packages

| Package | Status | Scope / blocker |
| --- | --- | --- |
| P01-01 | Complete | Foundation, deterministic fixtures, automated checks, minified release, API 26/37 rendering/lifecycle/network evidence, resource cycling, comparative size/open-time observations, three-package coexistence, updater isolation, and existing Local image EPUB/CBZ/image-folder regression all pass. |
| P01-02 | Cleared; not started | Production content resolver, durable import/storage, shared title/item mapping, database schema and migrations. |
| P01-03 | Not started | Production reader UI/preferences, modes, ToC/current section, link history and accessibility. |
| P01-04 | Not started | Durable resume/furthest progress, completion/history integration and bookmarks. |
| P01-05 | Not started | Search, selection actions and book navigation refinements. |
| P01-06 | Not started | Hostile EPUB hardening, explicit archive/path/resource budgets and broader security validation. |
| P01-07 | Not started | Backup/restore, missing-file recovery, storage presentation and cleanup ownership. |
| P01-08 | Not started | Full regression, performance budgets, oldest-SDK evidence and production release gate. |

P01-01 branch: `codex/p01-01-epub-foundation`. Intended PR title: `feat(epub): establish native reader foundation and Readium feasibility`. No push, merge, publication, or guarded upstream release action is part of this package.
