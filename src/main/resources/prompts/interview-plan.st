Plan a technical interview about this codebase.

Candidate level: {difficulty}
Target role: {targetRole}
Number of main questions: {questionCount}
Requested focus areas (empty means balanced): {focusAreas}
What the candidate says the project is for: {goal}

Architecture summary:
<repo_content>
{summary}
</repo_content>

<repo_map>
{repoMap}
</repo_map>

Choose exactly {questionCount} topics. Rules:
- Each topic points at specific code. Give targetPaths (file paths from the repo map) and targetSymbols (class or method names from the repo map).
- Cover different parts of the system. Do not put two topics on the same class.
- Order the topics from warm-up to hardest. The first topic lets the candidate explain the overall design in their own words.
- Prefer the riskAreas and notablePatterns from the summary: decisions the candidate made and their trade-offs, behavior under failure, concurrency and scale.
- Match the level. INTERN topics ask what and why. JUNIOR adds trade-offs and failure cases. MID adds scaling, alternatives and production concerns.
- Use only these categories: ARCHITECTURE, DESIGN_DECISION, DATA_MODEL, CONCURRENCY, FAILURE_HANDLING, SECURITY, TESTING, PERFORMANCE, SCALING, CODE_WALKTHROUGH.
- If focus areas were requested, at least half of the topics must use those categories.

For each topic give: title, category, targetPaths, targetSymbols, and rationale (one sentence on what a strong answer would show).
