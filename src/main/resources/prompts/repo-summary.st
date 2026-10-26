Summarize the architecture of the repository below so an interviewer can ask grounded questions about it.

Repository: {repoName}
What the candidate says the project is for: {goal}
Domain: {domain}

<repo_map>
{repoMap}
</repo_map>

<repo_content>
{buildFiles}

{readme}
</repo_content>

Produce:
- oneLiner: what the system does, in one sentence.
- architectureStyle: for example layered monolith, modular monolith, microservices, or library. Describe what the code shows, not what the README claims.
- techStack: frameworks, databases, messaging and infrastructure the code actually uses.
- modules: up to 8 modules or packages, each with its responsibility and 1 to 4 key class names.
- notablePatterns: design patterns or techniques visible in the code, such as idempotency keys, an outbox, caching, retries or a JWT filter. Name the class where each one appears.
- riskAreas: places a real interviewer would probe, such as concurrency, consistency, security, failure handling, scaling or testing gaps. Name the class or file for each.

Only name classes and files that appear in the repo map.
