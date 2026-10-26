---
name: next-task
description: Pick up the next unchecked task from docs/ROADMAP.md, plan it, implement it with tests, and close it out. Use when Aryan types /next-task or says "next task".
argument-hint: "[optional task id, e.g. 1.4]"
disable-model-invocation: true
---

# Next roadmap task

Target task: $ARGUMENTS (if empty, use the first unchecked task in the current phase of `docs/ROADMAP.md`).

1. **Orient.** Read the task in `docs/ROADMAP.md` and the LLD sections it points to. Check `git status` and the last few commits so you know where things stand. If an earlier task in the phase is unchecked, say so and ask whether to do that one first.
2. **Restate.** In 3–5 lines: the goal, the acceptance criteria, and any concept that is new for Aryan in this task.
3. **Plan.** List the files to create or change, the main classes and methods, the tests that prove each acceptance criterion, and any dependency or schema change (these need his OK). Wait for Aryan to approve or adjust the plan.
4. **[hands-on] tasks:** do not write the solution. Explain the concept, give a skeleton or hints only if he asks, then review his code against the acceptance criteria and LLD.
5. **Build** (other tasks): implement in small steps. Write the tests alongside the code. Follow `CLAUDE.md` and the path-specific rules.
6. **Verify.** Run `./mvnw verify`. Fix failures. Never mark a task done with failing tests.
7. **Close out.**
   - Tick the box in `docs/ROADMAP.md` and add a Status log row (date, task id, one-line note with any measured numbers).
   - Update `docs/LLD.md` or `docs/DECISIONS.md` if the design changed.
   - Reply with: what changed (short), 2–3 things Aryan should be able to explain in an interview about this task, and a suggested Conventional Commit message.
