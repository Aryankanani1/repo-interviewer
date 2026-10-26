---
name: explain
description: Explain a file, class, concept or design decision in this codebase the way Aryan would need to explain it in a job interview. Use when he asks "explain X", "why did we do X", or types /explain.
argument-hint: "[file, class, concept or decision]"
---

# Explain for interview readiness

Subject: $ARGUMENTS

1. Find the relevant code and docs (`docs/HLD.md`, `docs/LLD.md`, `docs/DECISIONS.md`). Read the actual code; do not explain from memory.
2. Explain in plain, simple English, in this order:
   - **What it is**, in 1–2 sentences.
   - **How it works here**, pointing to specific files and methods (with line numbers).
   - **Why we chose it**, and what we gave up (link the decision record if one exists).
   - **What breaks** if it is removed or done wrong.
3. Finish with **3 questions an interviewer would likely ask** about it, each with a 2–3 sentence model answer grounded in this code.
4. Keep the whole explanation under about 400 words unless Aryan asks for more depth.
