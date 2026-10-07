# PROGRESS.md — <APP NAME>

_Work items, stories and blockers live on the Sidequest board. This file keeps what outlives tickets._

## Baseline
- <YYYY-MM-DD> @ <SHA>: build <ok | failing>, <N> unit tests, <M> failing (pre-existing, not ours):
  - <TestClass.method>: <one-line reason if known>

## Decision log (never delete; one line each)
- <YYYY-MM-DD>: chose <X> over <Y> because <reason>. Revisit if <condition>. (<SQ-n / story>)

## Audit status
| Area | Last run | Result | How |
|---|---|---|---|
| Plan audit | — | — | `/plan-audit` |
| Bug hunt | — | — | `/bug-hunt` |
| Security | — | — | `/claude-security` |
| UI / design | — | — | `/ui-overhaul` phase review |
| Lint | — | — | `./gradlew :app:lintDebug -q` |
| Device QA | — | — | android-emulator-qa skill |

## Environment notes
- <quirks discovered, e.g. "emulator needs -gpu swiftshader_indirect on this machine">
