# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

## 5. Comment Hygiene (Publishable Library Code)

**This repo is published as an open-source KMP library. Comments must read
that way.** No internal artefacts. No session breadcrumbs. No private notes.

Never write into committed code:
- **Em dashes** (`—`, U+2014). Use `-`, `:`, `,`, or restructure the sentence.
  Applies to KDoc, inline comments, log/error message strings, and sample
  UI strings.
- **Device-specific testing numbers**: "on a Galaxy S24", "on a stock Pixel",
  "Pixel 6", etc. Phrase perf claims platform-neutrally ("on a typical
  mid-range Android device", "in the single-digit-millisecond range") or
  drop the number entirely if you cannot back it up portably.
- **References to `plans/`** (the dir is gitignored). No `plans/2026-XX-XX-…md`
  paths, no "Item F in the perf-parity plan", no "Phase 4f/5/6/7", no
  "X1 lands later". State the *what* in plain terms ("not yet implemented",
  "first cut", "boring fast path") without naming the planning doc.
- **Session/debugging breadcrumbs**: "see git history for the cold-paint
  investigation", "the user-flagged case", "we found that…", "earlier
  session". Future readers don't have that context; either inline the real
  reason (a hidden constraint, a bug being worked around) or remove it.
- **Roadmap promises that aren't already public** ("ships in v1.1",
  "lands in Phase 6"). Use "not yet implemented" instead, unless the
  versioning is already documented in README/CHANGELOG.

Default to **no comment**. Only add one when the WHY is non-obvious: a
hidden constraint, a subtle invariant, a workaround for a specific bug,
behaviour that would surprise a reader of the code on its own.

When you finish a task, scan the diff for these patterns before declaring
done. They leak in easily during iterative work.

## 6. Repo Hygiene

- `.Codex/` and `AGENTS.md` are gitignored. Do not commit either.
- `plans/` is gitignored and contains private design notes. Never reference
  `plans/*.md` paths from committed code or docs.
- `native/harfbuzz/` is the upstream HarfBuzz submodule. Never modify files
  under it; treat as read-only.

