---
name: quiz-me
description: Interview Aryan about code he recently built in this repo, one question at a time, and grade his answers against the code. Use when he types /quiz-me or asks to be quizzed on recent work.
argument-hint: "[optional: area, file or task id to focus on]"
disable-model-invocation: true
---

# Quiz me on recent work

Focus: $ARGUMENTS (if empty, use the most recent commits).

This is the product, done by hand: a grounded interview about his own code.

1. Run `git log --oneline -8` and `git diff --stat HEAD~3..HEAD` (if there are fewer commits, use what exists) to find what changed recently. Read the changed code.
2. Ask **3 questions, one at a time**. Wait for his answer before the next one. Each question must name a specific class, method, query or config value from the code, and ask why, what-if, or a trade-off. No textbook questions.
3. After each answer, grade it 0–10 against the code: what he got right, what he missed, anything that contradicts the code. Then give a 3–4 point model answer. Be honest and specific; no flattery.
4. If an answer is partly right, ask one short follow-up on the gap before moving on.
5. At the end, list the 2–3 topics he should review, with the file to read for each.
