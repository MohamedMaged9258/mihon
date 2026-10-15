# Native EPUB work packages

Package scopes follow section 8 of [`DEVELOPMENT_PLAN.md`](DEVELOPMENT_PLAN.md); this table only adds status.

| Package | Deliverable (from the plan) | Depends on | Status |
| --- | --- | --- | --- |
| P01-01 | Baseline, dependency integration and engine feasibility | None | In review: PR [#1](https://github.com/MohamedMaged9258/mihon/pull/1); see the acceptance verdict in [`TEST_MATRIX.md`](TEST_MATRIX.md) |
| P01-02 | Data model, identity and local publication/import services | P01-01 | Not started |
| P01-03 | Shared-library integration and EPUB reader routing | P01-02 | Not started |
| P01-04 | Durable Resume, lifecycle, history and completion | P01-03 | Not started |
| P01-05 | Complete reading controls, navigation and accessibility | P01-04 | Not started |
| P01-06 | Offline availability, storage and recovery | P01-05 | Not started |
| P01-07 | Backup, restore and upgrade compatibility | P01-06 | Not started |
| P01-08 | Regression testing, release packaging and user/developer docs | P01-07 | Not started |

P01-01 branch: `novels-p01-01-foundation`, targeting `novels` in [PR #1](https://github.com/MohamedMaged9258/mihon/pull/1) (`feat(epub): establish native reader foundation and Readium feasibility`). No merge, publication, or guarded upstream release action is part of this package.

The diagnostic close-and-open checkpoint file added in P01-01 is not the durable Resume repository; that remains P01-04 scope.
