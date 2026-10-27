package com.aryankanani.repointerviewer.codebase.map;

import com.aryankanani.repointerviewer.codebase.scan.FileRole;
import com.aryankanani.repointerviewer.codebase.scan.RedactedFile;

/**
 * One file for {@link RepoMapBuilder}. It takes the redacted text, never the raw file: the map goes into prompts,
 * so it gets the same protection as the chunks.
 */
public record RepoFile(RedactedFile file, FileRole role) {
}
