---
description: Start a porting task — read the referenced old file(s) before writing any new code, per AGENTS.md §11
---
Before writing any code for this task:

1. Open `../reference/PORTING_NOTES.md` and find the row for the concept
   `$ARGUMENTS` refers to.
2. Open every old file listed in that row, under
   `../reference/NoteScribe-Android-stable/...`, and read it in full — not a
   summary, not a guess based on the filename.
3. Port the behavior faithfully into Rust/Kotlin+Compose idioms. The data
   structures and API can differ from the Java original; the observable
   behavior (wrap rules, scroll thresholds, cursor edge cases, etc.) must
   not, unless the task explicitly asks for a behavior change.
4. If `$ARGUMENTS` has no row in `PORTING_NOTES.md`, or the row's old files
   don't actually cover what the task needs, stop and ask which old file (if
   any) applies — do not invent behavior that wasn't in the old code.
