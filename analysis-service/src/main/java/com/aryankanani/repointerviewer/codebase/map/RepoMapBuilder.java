package com.aryankanani.repointerviewer.codebase.map;

import static com.aryankanani.repointerviewer.codebase.scan.FileRole.BUILD;
import static com.aryankanani.repointerviewer.codebase.scan.FileRole.CONFIG;
import static com.aryankanani.repointerviewer.codebase.scan.FileRole.CONTROLLER;
import static com.aryankanani.repointerviewer.codebase.scan.FileRole.DOC;
import static com.aryankanani.repointerviewer.codebase.scan.FileRole.DTO;
import static com.aryankanani.repointerviewer.codebase.scan.FileRole.ENTITY;
import static com.aryankanani.repointerviewer.codebase.scan.FileRole.MESSAGING;
import static com.aryankanani.repointerviewer.codebase.scan.FileRole.MIGRATION;
import static com.aryankanani.repointerviewer.codebase.scan.FileRole.OTHER;
import static com.aryankanani.repointerviewer.codebase.scan.FileRole.REPOSITORY;
import static com.aryankanani.repointerviewer.codebase.scan.FileRole.SECURITY;
import static com.aryankanani.repointerviewer.codebase.scan.FileRole.SERVICE;
import static com.aryankanani.repointerviewer.codebase.scan.FileRole.TEST;

import com.aryankanani.repointerviewer.codebase.map.JavaOutline.TypeOutline;
import com.aryankanani.repointerviewer.codebase.scan.FileRole;
import com.aryankanani.repointerviewer.common.TokenEstimator;
import com.aryankanani.repointerviewer.common.config.AppProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Builds the repo map: a short outline of the repository (folders, then classes with their role, annotations
 * and public methods) for the summary and interview-plan prompts (LLD §6.9, DECISIONS.md D-006). The planner
 * may only point at files and symbols it sees here, so this decides what the interview can ask about.
 *
 * <p>It must fit in {@code app.repo-map.max-tokens}. When it does not, roles are shrunk one at a time, least
 * important first ({@link #SHRINK_ORDER}), in two steps each, until it fits:
 * <ol>
 * <li>{@link Detail#NAMES}: keep the type lines, drop the member lines. The paths stay visible, so the planner
 * can still point at them.</li>
 * <li>{@link Detail#COUNT}: replace the role's files in each folder with one line, {@code 12 more DTO files}.</li>
 * </ol>
 * If even the all-counts map is too big (only with an absurdly small budget), the last lines are cut and
 * {@value #TRUNCATED} is added, so the result is always within budget.
 */
@Component
public class RepoMapBuilder {

	/** Least important first. Controllers and services are what interviews are about, so they shrink last. */
	static final List<FileRole> SHRINK_ORDER = List.of(DOC, TEST, OTHER, DTO, MIGRATION, BUILD, REPOSITORY, CONFIG,
			MESSAGING, SECURITY, ENTITY, SERVICE, CONTROLLER);

	static final String TRUNCATED = "... (repo map truncated)";

	enum Detail {
		/** Type lines and member lines. */
		FULL,
		/** Type lines only. */
		NAMES,
		/** One count line per folder for the whole role. */
		COUNT
	}

	/**
	 * One file, prepared once: the map is rendered several times while shrinking.
	 *
	 * @param types the Java outline; empty for other files and for Java that does not parse
	 */
	private record Entry(String name, FileRole role, List<TypeOutline> types) {
	}

	private final int maxTokens;

	@Autowired
	RepoMapBuilder(AppProperties properties) {
		this(properties.repoMap().maxTokens());
	}

	RepoMapBuilder(int maxTokens) {
		this.maxTokens = maxTokens;
	}

	public String build(List<RepoFile> files) {
		Map<String, List<Entry>> folders = byFolder(files);
		Map<FileRole, Detail> detail = new EnumMap<>(FileRole.class);
		for (FileRole role : FileRole.values()) {
			detail.put(role, Detail.FULL);
		}
		List<String> lines = render(folders, detail);
		for (FileRole role : SHRINK_ORDER) {
			for (Detail smaller : List.of(Detail.NAMES, Detail.COUNT)) {
				if (fits(lines)) {
					return String.join("\n", lines);
				}
				detail.put(role, smaller);
				lines = render(folders, detail);
			}
		}
		return fits(lines) ? String.join("\n", lines) : truncate(lines);
	}

	/** Folders sorted by path, files sorted by name inside each. The root folder is {@code ""}. */
	private static Map<String, List<Entry>> byFolder(List<RepoFile> files) {
		Map<String, List<Entry>> folders = new TreeMap<>();
		for (RepoFile repoFile : files) {
			String path = repoFile.file().path();
			int slash = path.lastIndexOf('/');
			List<TypeOutline> types = repoFile.file().language().equals("java")
					? JavaOutline.of(repoFile.file(), repoFile.role())
					: List.of();
			folders.computeIfAbsent(path.substring(0, slash + 1), folder -> new ArrayList<>())
					.add(new Entry(path.substring(slash + 1), repoFile.role(), types));
		}
		folders.values().forEach(entries -> entries.sort(Comparator.comparing(Entry::name)));
		return folders;
	}

	private static List<String> render(Map<String, List<Entry>> folders, Map<FileRole, Detail> detail) {
		List<String> lines = new ArrayList<>();
		folders.forEach((folder, entries) -> {
			lines.add(folder.isEmpty() ? "./" : folder);
			Map<FileRole, Integer> counted = new EnumMap<>(FileRole.class);
			for (Entry entry : entries) {
				Detail level = detail.get(entry.role());
				if (level == Detail.COUNT) {
					counted.merge(entry.role(), 1, Integer::sum);
				}
				else if (entry.types().isEmpty()) {
					lines.add("  " + entry.name() + " [" + entry.role() + "]");
				}
				else {
					for (TypeOutline type : entry.types()) {
						lines.add("  " + type.line());
						if (level == Detail.FULL) {
							type.members().forEach(member -> lines.add("    " + member));
						}
					}
				}
			}
			// Most important role first, like the shrink order read backwards.
			SHRINK_ORDER.reversed().stream()
					.filter(counted::containsKey)
					.forEach(role -> lines.add("  " + counted.get(role) + " more " + role + " file"
							+ (counted.get(role) == 1 ? "" : "s")));
		});
		return lines;
	}

	private boolean fits(List<String> lines) {
		return TokenEstimator.estimate(String.join("\n", lines)) <= maxTokens;
	}

	/** Keeps whole lines from the top while they fit together with the {@link #TRUNCATED} marker. */
	private String truncate(List<String> lines) {
		List<String> kept = new ArrayList<>();
		for (String line : lines) {
			kept.add(line);
			kept.add(TRUNCATED);
			boolean fits = fits(kept);
			kept.removeLast();
			if (!fits) {
				kept.removeLast();
				break;
			}
		}
		kept.add(TRUNCATED);
		return fits(kept) ? String.join("\n", kept) : "";
	}

}
