package com.aryankanani.repointerviewer.codebase.store;

import com.aryankanani.repointerviewer.codebase.scan.FileRole;
import com.aryankanani.repointerviewer.codebase.scan.RedactedFile;
import com.aryankanani.repointerviewer.codebase.scan.ScannedFile;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * One file of an analyzed repository (table {@code code_file}). Its chunks live in {@code code_chunk} and are
 * written by {@link ChunkStore}, not through JPA.
 *
 * <p>{@code projectId} is a plain column, not a {@code @ManyToOne Project}: {@code codebase} may not depend on
 * {@code project} (LLD §2). The foreign key is still enforced by the database.
 *
 * <p>{@code content} is the whole file after {@code SecretRedactor} (V2): it is only ever set from a
 * {@link RedactedFile}, so raw text cannot reach it. It feeds {@code CodeRef} snippets; search uses the chunks.
 */
@Entity
@Table(name = "code_file")
public class CodeFile {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	private UUID projectId;

	private String path;

	private String language;

	@Enumerated(EnumType.STRING)
	private FileRole role;

	private int lineCount;

	private int sizeBytes;

	private String contentSha;

	/** Null for files analyzed before V2. */
	private String content;

	/** For JPA only. */
	protected CodeFile() {
	}

	public CodeFile(UUID projectId, ScannedFile file, FileRole role, RedactedFile redacted) {
		this.projectId = projectId;
		this.path = file.path();
		this.language = file.language();
		this.role = role;
		this.lineCount = file.lineCount();
		this.sizeBytes = file.sizeBytes();
		this.contentSha = file.contentSha();
		this.content = redacted.content();
	}

	public UUID getId() {
		return id;
	}

	public UUID getProjectId() {
		return projectId;
	}

	public String getPath() {
		return path;
	}

	public String getLanguage() {
		return language;
	}

	public FileRole getRole() {
		return role;
	}

	public int getLineCount() {
		return lineCount;
	}

	public int getSizeBytes() {
		return sizeBytes;
	}

	public String getContentSha() {
		return contentSha;
	}

	/** The redacted file text, or null for a file analyzed before V2. */
	public String getContent() {
		return content;
	}

}
