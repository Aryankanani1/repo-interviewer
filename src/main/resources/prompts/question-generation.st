Write one interview question for the topic below.

Candidate level: {difficulty}
Topic: {topicTitle}
Category: {category}
Why this topic: {rationale}

Code the question must be about:
{codeChunks}

Questions already asked in this interview (do not repeat them or ask about the same thing):
{previousQuestions}

Rules for the question:
- Refer to something concrete in the code above, such as a class, method, field, query, annotation or configuration value, and mention it by name.
- It must be answerable by the person who built this code in 2 to 5 minutes of speaking.
- Ask one thing. Do not list sub-questions.
- Prefer why, what-if and trade-off questions over "what does this method do".
- Do not paste large blocks of code into the question.

Also produce:
- codeRefs: 1 to 3 references, each with path, startLine and endLine. Use only paths and line ranges from the code_chunk headers above.
- rubric.mustMention: 2 to 4 points a correct answer has to include.
- rubric.goodToMention: 1 to 3 points that make an answer strong.
- rubric.redFlags: 1 to 3 misunderstandings that would show the candidate does not know this code.
- idealAnswerOutline: 3 to 5 short points of a strong answer, grounded in the code.
