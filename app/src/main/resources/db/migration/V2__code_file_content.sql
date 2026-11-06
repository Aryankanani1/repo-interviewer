-- V2: the whole text of each analyzed file, already secret-redacted, so CodeRef snippets can show any cited lines
-- exactly. Before this, lines that only a TYPE_HEADER chunk covers (class annotations, fields) were stored nowhere
-- verbatim, and a ref on them had no snippet (first real run, task 1.15, RESULTS.md problem 3).
-- Same number of lines as the file in the repository, so cited line numbers match.
-- NULL for files analyzed before V2: their snippets still come from the chunks.
ALTER TABLE code_file ADD COLUMN content TEXT;
