package com.aryankanani.repointerviewer.codebase.chunk;

import com.aryankanani.repointerviewer.codebase.scan.JavaParsing;
import com.aryankanani.repointerviewer.codebase.scan.RedactedFile;
import com.aryankanani.repointerviewer.common.config.AppProperties;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.printer.DefaultPrettyPrinter;
import com.github.javaparser.printer.configuration.DefaultConfigurationOption;
import com.github.javaparser.printer.configuration.DefaultPrinterConfiguration;
import com.github.javaparser.printer.configuration.DefaultPrinterConfiguration.ConfigOption;
import com.github.javaparser.printer.configuration.PrinterConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Chunks a Java file (LLD §6.6), for every top-level and nested type:
 * <ol>
 * <li>One TYPE_HEADER: package, the first {@value #MAX_IMPORTS} imports, the type's annotations and
 * declaration, its fields, and the signatures of its methods (no bodies). Symbol {@code Order}, or
 * {@code Order.Status} for a nested type.</li>
 * <li>One METHOD chunk per method, constructor, compact constructor or initializer block, with its Javadoc and
 * annotations. Symbol {@code Order#markRefunded}; constructors use the type name; overloads add the parameter
 * types: {@code Order#Order(String,BigDecimal)}.</li>
 * <li>Methods shorter than {@code app.chunking.small-method-lines} (Javadoc not counted) are merged, one chunk
 * per run of adjacent small methods ({@code Order#small-methods}, {@code Order#small-methods-2}). A bigger
 * method or a nested type ends a run; fields and comments in between are included.</li>
 * <li>Anything longer than {@code app.chunking.max-chunk-lines} is cut into overlapping windows:
 * {@code #part2}, {@code #part3}, ...</li>
 * </ol>
 * METHOD chunks hold exactly their source lines (see {@link Chunk}). A file that does not parse, or declares no
 * type ({@code package-info.java}), goes to {@link TextChunker} instead.
 */
@Component
public class JavaChunker {

	static final int MAX_IMPORTS = 15;

	private final int maxChunkLines;

	private final int smallMethodLines;

	private final TextChunker textChunker;

	@Autowired
	JavaChunker(AppProperties properties, TextChunker textChunker) {
		this(properties.chunking().maxChunkLines(), properties.chunking().smallMethodLines(), textChunker);
	}

	JavaChunker(int maxChunkLines, int smallMethodLines, TextChunker textChunker) {
		this.maxChunkLines = maxChunkLines;
		this.smallMethodLines = smallMethodLines;
		this.textChunker = textChunker;
	}

	public List<Chunk> chunk(RedactedFile file) {
		Optional<CompilationUnit> unit = JavaParsing.parse(file.content());
		if (unit.isEmpty() || unit.get().getTypes().isEmpty()) {
			return textChunker.chunk(file);
		}
		FileChunks chunks = new FileChunks(file, unit.get());
		for (TypeDeclaration<?> type : unit.get().getTypes()) {
			chunks.addType(type, type.getNameAsString());
		}
		return chunks.result;
	}

	/** Comments and Javadoc are left out of the outline: method chunks carry them. */
	private static DefaultPrettyPrinter printerWithoutComments() {
		PrinterConfiguration configuration = new DefaultPrinterConfiguration()
				.removeOption(new DefaultConfigurationOption(ConfigOption.PRINT_COMMENTS))
				.removeOption(new DefaultConfigurationOption(ConfigOption.PRINT_JAVADOC));
		return new DefaultPrettyPrinter(configuration);
	}

	private static Range range(Node node) {
		return node.getRange().orElseThrow(() -> new IllegalStateException("A parsed node has no position"));
	}

	private static boolean isCode(BodyDeclaration<?> member) {
		return member instanceof CallableDeclaration<?> || member instanceof CompactConstructorDeclaration
				|| member instanceof InitializerDeclaration;
	}

	/** Method and constructor names that occur more than once in a type, so their symbols need parameter types. */
	private static Set<String> overloadedNames(TypeDeclaration<?> type) {
		Map<String, Long> counts = type.getMembers().stream()
				.filter(member -> member instanceof CallableDeclaration<?>)
				.map(member -> ((CallableDeclaration<?>) member).getNameAsString())
				.collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
		return counts.entrySet().stream()
				.filter(entry -> entry.getValue() > 1)
				.map(Map.Entry::getKey)
				.collect(Collectors.toSet());
	}

	private static String memberName(BodyDeclaration<?> member, TypeDeclaration<?> type, Set<String> overloaded) {
		if (member instanceof InitializerDeclaration initializer) {
			return initializer.isStatic() ? "static-initializer" : "initializer";
		}
		if (member instanceof CallableDeclaration<?> callable) {
			String name = callable.getNameAsString();
			if (!overloaded.contains(name)) {
				return name;
			}
			return name + callable.getParameters().stream()
					.map(parameter -> parameter.getType().asString() + (parameter.isVarArgs() ? "..." : ""))
					.collect(Collectors.joining(",", "(", ")"));
		}
		return type.getNameAsString();   // compact constructor of a record
	}

	/** The first line that opens a body, for example {@code public enum Status {}, plus a placeholder body. */
	private static String openingLine(String printed) {
		return printed.lines()
				.filter(line -> line.strip().endsWith("{"))
				.findFirst()
				.orElse(printed.lines().findFirst().orElse(""))
				.strip() + " ... }";
	}

	private static String indent(String text) {
		return text.lines().map(line -> line.isBlank() ? "" : "    " + line).collect(Collectors.joining("\n"));
	}

	/** Chunks of one file. Not shared: {@link #chunk} creates one per call. */
	private final class FileChunks {

		private final String path;

		private final SourceLines lines;

		private final DefaultPrettyPrinter printer = printerWithoutComments();

		private final String fileHeader;

		private final List<Chunk> result = new ArrayList<>();

		FileChunks(RedactedFile file, CompilationUnit unit) {
			this.path = file.path();
			this.lines = new SourceLines(file.content());
			this.fileHeader = fileHeader(unit);
		}

		void addType(TypeDeclaration<?> type, String symbol) {
			Range typeRange = range(type);
			result.add(Chunk.of(path, ChunkType.TYPE_HEADER, symbol, typeRange.begin.line, typeRange.end.line,
					header(type)));
			Set<String> overloaded = overloadedNames(type);
			SmallMethodRun run = new SmallMethodRun(symbol);
			for (BodyDeclaration<?> member : type.getMembers()) {
				if (member instanceof TypeDeclaration<?> nested) {
					run.close();
					addType(nested, symbol + "." + nested.getNameAsString());
				}
				else if (isCode(member) && isSmall(member)) {
					run.add(member);
				}
				else if (isCode(member)) {
					run.close();
					addRange(symbol + "#" + memberName(member, type, overloaded), startLine(member),
							range(member).end.line);
				}
				// Fields and other members do not end a run: their lines are simply part of it.
			}
			run.close();
		}

		/** Javadoc does not count: a getter with a long comment is still a small method. */
		private boolean isSmall(BodyDeclaration<?> member) {
			Range memberRange = range(member);
			return memberRange.end.line - memberRange.begin.line + 1 < smallMethodLines;
		}

		/** The first line of the member's Javadoc or comment if it has one, else of the member itself. */
		private int startLine(BodyDeclaration<?> member) {
			int memberStart = range(member).begin.line;
			return member.getComment()
					.flatMap(Node::getRange)
					.map(commentRange -> Math.min(commentRange.begin.line, memberStart))
					.orElse(memberStart);
		}

		private void addRange(String symbol, int start, int end) {
			List<LineWindows.Range> windows = LineWindows.split(start, end, maxChunkLines);
			for (int i = 0; i < windows.size(); i++) {
				LineWindows.Range window = windows.get(i);
				result.add(Chunk.of(path, ChunkType.METHOD, LineWindows.partSymbol(symbol, i), window.start(),
						window.end(), lines.slice(window.start(), window.end())));
			}
		}

		private String fileHeader(CompilationUnit unit) {
			StringBuilder header = new StringBuilder();
			unit.getPackageDeclaration().ifPresent(declaration ->
					header.append(printer.print(declaration).strip()).append("\n\n"));
			List<ImportDeclaration> imports = unit.getImports();
			imports.stream().limit(MAX_IMPORTS).forEach(declaration ->
					header.append(printer.print(declaration).strip()).append('\n'));
			if (imports.size() > MAX_IMPORTS) {
				header.append("// +").append(imports.size() - MAX_IMPORTS).append(" more imports\n");
			}
			if (!imports.isEmpty()) {
				header.append('\n');
			}
			return header.toString();
		}

		private String header(TypeDeclaration<?> type) {
			StringBuilder header = new StringBuilder(fileHeader).append(declaration(type)).append('\n');
			for (BodyDeclaration<?> member : type.getMembers()) {
				header.append(indent(outline(member))).append('\n');
			}
			return header.append('}').toString();
		}

		/** Annotations and declaration without members, up to and including {@code {} (enum constants kept). */
		private String declaration(TypeDeclaration<?> type) {
			TypeDeclaration<?> shell = type.clone();
			shell.getMembers().clear();
			String printed = printer.print(shell).stripTrailing();
			String opening = printed.substring(0, printed.lastIndexOf('}')).stripTrailing();
			boolean constantsBeforeMembers = type instanceof EnumDeclaration enumType
					&& !enumType.getEntries().isEmpty() && !enumType.getMembers().isEmpty();
			return constantsBeforeMembers ? opening + ";" : opening;
		}

		/** One member of the outline: a field as written, a signature, or the opening line of a nested block. */
		private String outline(BodyDeclaration<?> member) {
			if (member instanceof MethodDeclaration method) {
				MethodDeclaration signature = method.clone();
				signature.removeBody();
				return printer.print(signature).strip();
			}
			if (member instanceof ConstructorDeclaration constructor) {
				ConstructorDeclaration signature = constructor.clone();
				signature.setBody(new BlockStmt());
				String printed = printer.print(signature).stripTrailing();
				return printed.substring(0, printed.lastIndexOf('{')).stripTrailing() + ";";
			}
			if (member instanceof FieldDeclaration || member instanceof AnnotationMemberDeclaration) {
				return printer.print(member).strip();
			}
			return openingLine(printer.print(member));   // nested type, compact constructor, initializer block
		}

		/** Adjacent small methods of one type, emitted as a single METHOD chunk. */
		private final class SmallMethodRun {

			private final String typeSymbol;

			private final List<BodyDeclaration<?>> members = new ArrayList<>();

			private int runsClosed;

			SmallMethodRun(String typeSymbol) {
				this.typeSymbol = typeSymbol;
			}

			void add(BodyDeclaration<?> member) {
				members.add(member);
			}

			void close() {
				if (members.isEmpty()) {
					return;
				}
				runsClosed++;
				int start = members.stream().mapToInt(FileChunks.this::startLine).min().orElseThrow();
				int end = members.stream().mapToInt(member -> range(member).end.line).max().orElseThrow();
				addRange(typeSymbol + "#small-methods" + (runsClosed == 1 ? "" : "-" + runsClosed), start, end);
				members.clear();
			}

		}

	}

}
