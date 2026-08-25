# AI Usage Policy

**No AI-authored changes are allowed in this codebase.** AI must never write, edit, or delete
source code, configuration, build files, database migrations, or documentation here — nor create
that content for a human to paste in. The role of AI in this repository is **read-only**: inspect
the codebase and report findings as issues.

## What counts as a "change" (all forbidden)

- Editing, creating, or deleting any file in the repo (code, config, the Gradle build script,
  YAML, Markdown, tests, migrations — anything).
- Producing a patch, diff, or code block **intended to be applied** to the codebase, whether
  written to disk or pasted into chat.
- Any Git write operation: `commit`, `push`, `branch`, `merge`, `rebase`, tag, or opening a
  pull request.

Describing *how* a human might fix something, in prose, inside an issue is fine (see below).
Handing over ready-to-apply code is not.

## What is allowed (read-only)

- Reading, searching, and navigating the code.
- Running **non-mutating** commands to understand or reproduce behavior — `./gradlew build`,
  `./gradlew test`, static analysis, `git log` / `git diff` / `git status`. These may write to
  generated, untracked locations (`build/`, `.gradle/`, the Gradle user home); they must never
  modify a file that Git tracks, push to a remote, or call out to an external service.
- Filing issues in the remote repository (the only permitted output).

## Rejected requests

If a user points an AI at this codebase and asks it to **make changes** — write code, apply a
fix, refactor, scaffold, or edit any file — the AI must **decline**, cite this policy, and offer
the only supported alternative: filing an issue instead.

## Permitted requests — reporting only

The AI may help only by **finding issues and filing them in the remote repository**. Every issue
carries exactly one of these labels:

| Label | For | Must cite |
|---|---|---|
| `violation` | The codebase breaks a rule in [`CONTRIBUTING.md`](./CONTRIBUTING.md) | The quoted rule + the exact location breaking it |
| `bug` | A defect the AI **reproduced** | Command run, expected vs. actual, captured output |
| `feature` | Behavior already specified in [`README.md`](./README.md) or [`docs/DESIGN.md`](./docs/DESIGN.md) but not implemented | The `path:line` where that behavior is specified |
| `documentation` | A doc contradicts the code, or contradicts another doc | Both `path:line` sides of the contradiction |
| `chore` | Repo, build, or structural groundwork with no user-visible behavior change | The concrete state that needs correcting |

Each finding is filed as a **separate** issue. If a finding fits no label, do not invent one and
do not stretch an existing one — report it to the human in conversation instead.

## Scope: one issue = one pull request

This is the rule that matters most. **An issue must describe a single change that one person can
deliver in one pull request a reviewer can read in one sitting.** Anything larger is a plan, not
an issue, and this repository does not track plans in the issue tracker — the README roadmap
already does that.

### Reject the issue if any of these are true

Do not file it. Split it into the specific changes it contains, or drop it.

1. **The title names a component instead of a change.** "Scanner engine", "Violation lifecycle",
   "Observability", "REST API" are subsystems. "Reject a reference whose `service` has no
   configured datasource" is a change.
2. **The title joins deliverables** with `and`, `+`, `/`, or a comma list — "model + persistence",
   "metrics + health", "parsing & validation". Each side is its own issue.
3. **The title describes a phase of work** — anything starting `V2:`, `V3:`, `Scaffold …`,
   `Epic:`, `Implement …` where the object is a whole subsystem.
4. **It restates a roadmap bullet or a whole section of `docs/DESIGN.md`.** Pointing at a design
   section is evidence *for* an issue; it is not the issue.
5. **The acceptance criteria cannot be written as five or fewer mechanically checkable
   statements.** If you cannot say precisely what a reviewer would run or read to call it done,
   the issue is too big or too vague — either way, do not file it.
6. **Delivering it requires a design decision the docs have not already made.** Do not file an
   issue whose first step is "decide how X should work". Raise that question with the owner in
   conversation instead. This bars *unspecified* work, not *large* work — if `README.md` or
   `docs/DESIGN.md` already pins down the behavior, file it, however far into the build it sits.
7. **The title could equally describe three different pull requests.** Read it back before
   filing; if it is a category rather than a task, it fails.

### Volume and completeness

**There is no cap on how many issues a run files. The cap is on how big each one is.** Covering
the declared V1 scope in forty tight issues is correct; covering it in five wide ones is not.
Splitting an epic and then filing only the first two pieces is the same failure as filing the
epic — it just hides the gap instead of naming it.

When the run's purpose is to build out the backlog, walk the V1 scope at `README.md:100` and the
components at `docs/DESIGN.md:39-51`, and file **every** unimplemented behavior those documents
already specify. Leaving specified V1 behavior unfiled because it did not fit a self-imposed
limit is a failed run.

What stays capped is speculation: do not file behavior that no line of `README.md` or
`docs/DESIGN.md` specifies.

## Required issue format

**Title** — imperative, one deliverable, at most 70 characters, naming the concrete artifact.

- Good: `Reject contract references whose service has no configured datasource`
- Bad: `Integrity contract: model + YAML parsing & validation`

**Body** — exactly these four sections, in this order, and nothing else:

```
### What
One or two sentences. The single change, in the imperative.

### Evidence
`path:line` references, and for a bug the command plus its captured output.

### Acceptance criteria
- [ ] At most five statements, each one a reviewer can mechanically verify.

### Out of scope
Adjacent work this issue does not cover, each with the issue that covers it, or "not filed".
```

The **Out of scope** section is mandatory and must not be empty. It is what stops an issue from
quietly growing into an epic while it is being worked.

## Rules for filed issues

- **Evidence, not speculation.** Cite concrete `path:line` references. Never invent or infer a
  finding you cannot point at. If you are not confident, do not file it.
- **No vague language.** An issue body must not contain `etc.`, `and so on`, `as appropriate`,
  `as needed`, `robust`, `proper`, or `improve`. If a word can be deleted without changing what
  a reviewer would check, delete it.
- **State the file's Git status when it matters.** If a finding is in a file that is untracked,
  say so in **Evidence** — a reader on the remote cannot open a path that was never pushed, and
  the working tree is not the branch.
- **`violation` issues** must quote the specific `CONTRIBUTING.md` rule being broken and the exact
  location that breaks it.
- **`bug` issues** must be genuinely **reproducible**: include the steps, expected vs. actual
  behavior, and the observed evidence (test output, stack trace). A hunch is not a bug.
- **`feature` issues** must trace back to the README's problem statement and stay inside its
  declared V1 scope, or explicitly note that they fall in the "out of scope / later" section and
  why they're still worth recording. A `feature` issue is one behavior, never a subsystem.
- **No duplicates.** Search existing issues first; if a matching one exists, do not open another.
- **Remediation goes in the issue body as prose only** — a suggested approach a human can act on,
  never an attached patch or apply-ready code.
- **Never suggest code comments.** This codebase forbids inline code comments (see
  [`CONTRIBUTING.md`](./CONTRIBUTING.md) → "No inline code comments"). Any snippet or remediation
  the AI describes must obey that rule: do not propose adding `//` or `/* */` comments — put the
  explanation in the issue or PR description instead. JavaDoc, `#` comments in config/`.properties`
  files, and the single permitted `// why:` note on an intentional controller `try/catch` remain
  the only exceptions.

## The only exception

The **repository owner** may explicitly direct the AI to edit a **documentation or meta file**
(`README.md`, `CONTRIBUTING.md`, this `AGENTS.md`) — for example to fix wording. This exception
covers docs only. It never extends to source code, configuration, build files, or migrations,
and it must be an explicit, in-the-moment instruction from the owner — not inferred from context.
