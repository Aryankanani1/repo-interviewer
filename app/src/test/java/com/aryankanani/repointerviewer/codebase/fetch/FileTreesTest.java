package com.aryankanani.repointerviewer.codebase.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileTreesTest {

	@TempDir
	Path tempDir;

	@Test
	void deleteTreeRemovesNestedFilesIncludingReadOnlyOnes() throws IOException {
		Path root = tempDir.resolve("clone");
		Files.createDirectories(root.resolve(".git/objects/pack"));
		Path pack = Files.writeString(root.resolve(".git/objects/pack/pack-1.pack"), "pack");
		assertThat(pack.toFile().setReadOnly()).isTrue();   // JGit writes its pack files read-only
		Files.createDirectories(root.resolve("src/main/java"));
		Files.writeString(root.resolve("src/main/java/App.java"), "class App {}");

		FileTrees.deleteTree(root);

		assertThat(root).doesNotExist();
	}

	@Test
	void deleteTreeRemovesLinksButNeverWhatTheyPointTo() throws IOException {
		Path outside = Files.createDirectories(tempDir.resolve("outside"));
		Path precious = Files.writeString(outside.resolve("precious.txt"), "keep me");
		Path root = Files.createDirectories(tempDir.resolve("clone"));
		Files.createSymbolicLink(root.resolve("dir-link"), outside);
		Files.createSymbolicLink(root.resolve("file-link"), precious);

		FileTrees.deleteTree(root);

		assertThat(root).doesNotExist();
		assertThat(precious).hasContent("keep me");
	}

	@Test
	void deleteTreeOfAMissingPathDoesNothing() {
		assertThatCode(() -> FileTrees.deleteTree(tempDir.resolve("never-created"))).doesNotThrowAnyException();
	}

	@Test
	void workingTreeBytesCountsRegularFilesOnly() throws IOException {
		Path root = Files.createDirectories(tempDir.resolve("clone"));
		Files.writeString(root.resolve("README.md"), "x".repeat(100));
		Files.createDirectories(root.resolve("src"));
		Files.writeString(root.resolve("src/App.java"), "x".repeat(200));
		Files.createDirectories(root.resolve(".git/objects"));
		Files.writeString(root.resolve(".git/objects/pack-1.pack"), "x".repeat(5_000));   // not the working tree
		Path big = Files.writeString(tempDir.resolve("big-outside.bin"), "x".repeat(10_000));
		Files.createSymbolicLink(root.resolve("link-to-big"), big);   // a link is not followed

		assertThat(FileTrees.workingTreeBytes(root)).isEqualTo(300);
	}

}
