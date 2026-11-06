package com.aryankanani.repointerviewer.codebase.scan;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import java.util.Collections;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Picks a {@link FileRole} for a scanned file (LLD §6.5). The first matching rule wins: TEST, MIGRATION and
 * BUILD by path, CONFIG for {@code application*} files, then for Java the annotations and supertypes of the
 * file's main type (SECURITY, CONFIG, CONTROLLER, MESSAGING, SERVICE, REPOSITORY, ENTITY, DTO), DOC for
 * Markdown, OTHER for everything else.
 *
 * <p>Annotations are matched by simple name: {@code @org.springframework.stereotype.Service} counts as
 * {@code Service}. {@link JavaParsing} only builds the syntax tree; nothing is compiled or resolved, so the
 * imported classes do not have to exist.
 */
@Component
public class FileClassifier {

	private static final Set<String> MESSAGING_ANNOTATIONS = Set.of("KafkaListener", "RabbitListener",
			"EventListener", "TransactionalEventListener");

	public FileRole classify(ScannedFile file) {
		String path = file.path();
		if (FilePatterns.isTest(path)) {
			return FileRole.TEST;
		}
		if (FilePatterns.isMigration(path)) {
			return FileRole.MIGRATION;
		}
		if (FilePatterns.isBuildFile(path)) {
			return FileRole.BUILD;
		}
		if (FilePatterns.isApplicationConfig(path)) {
			return FileRole.CONFIG;
		}
		if (file.language().equals(FilePatterns.JAVA)) {
			return classifyJava(file);
		}
		return file.language().equals("markdown") ? FileRole.DOC : FileRole.OTHER;
	}

	private static FileRole classifyJava(ScannedFile file) {
		String fileName = file.path().substring(file.path().lastIndexOf('/') + 1);
		String typeName = fileName.substring(0, fileName.length() - ".java".length());
		Optional<TypeDeclaration<?>> type = mainType(file.content(), typeName);
		if (type.isEmpty()) {
			// It does not parse (or declares no type, like package-info.java): the file name is all we have.
			return isDtoName(typeName) ? FileRole.DTO : FileRole.OTHER;
		}
		return classifyType(type.get());
	}

	private static FileRole classifyType(TypeDeclaration<?> type) {
		String name = type.getNameAsString();
		Set<String> typeAnnotations = names(type.getAnnotations());
		Set<String> methodAnnotations = type.getMethods().stream()
				.flatMap(method -> names(method.getAnnotations()).stream())
				.collect(Collectors.toSet());

		// SECURITY before CONFIG: the usual SecurityConfig is a @Configuration class as well (LLD §6.5).
		if (hasSecurityFilterChainBean(type) || isAuthFilter(type)) {
			return FileRole.SECURITY;
		}
		if (containsAny(typeAnnotations, Set.of("Configuration", "ConfigurationProperties"))) {
			return FileRole.CONFIG;
		}
		if (containsAny(typeAnnotations, Set.of("RestController", "Controller"))
				|| typeAnnotations.stream().anyMatch(annotation -> annotation.endsWith("Mapping"))
				|| containsAny(methodAnnotations, Set.of("QueryMapping", "MutationMapping"))) {
			return FileRole.CONTROLLER;
		}
		if (containsAny(typeAnnotations, MESSAGING_ANNOTATIONS) || containsAny(methodAnnotations, MESSAGING_ANNOTATIONS)) {
			return FileRole.MESSAGING;
		}
		if (typeAnnotations.contains("Service")
				|| (typeAnnotations.contains("Component") && endsWithAny(name, "Service", "Handler", "UseCase"))) {
			return FileRole.SERVICE;
		}
		if (typeAnnotations.contains("Repository") || isSpringDataRepository(type)) {
			return FileRole.REPOSITORY;
		}
		if (containsAny(typeAnnotations, Set.of("Entity", "Embeddable", "MappedSuperclass"))) {
			return FileRole.ENTITY;
		}
		return isDtoName(name) ? FileRole.DTO : FileRole.OTHER;
	}

	/** The type named like the file, else the first type in it. Empty when the source does not parse. */
	private static Optional<TypeDeclaration<?>> mainType(String source, String typeName) {
		Optional<CompilationUnit> unit = JavaParsing.parse(source);
		if (unit.isEmpty()) {
			return Optional.empty();
		}
		NodeList<TypeDeclaration<?>> types = unit.get().getTypes();
		return types.stream()
				.filter(type -> type.getNameAsString().equals(typeName))
				.findFirst()
				.or(() -> types.stream().findFirst());
	}

	private static boolean hasSecurityFilterChainBean(TypeDeclaration<?> type) {
		return type.getMethods().stream()
				.anyMatch(method -> names(method.getAnnotations()).contains("Bean")
						&& simpleName(method.getType()).equals("SecurityFilterChain"));
	}

	/** A servlet filter that authenticates requests: extends OncePerRequestFilter, named after JWT or auth. */
	private static boolean isAuthFilter(TypeDeclaration<?> type) {
		String name = type.getNameAsString().toLowerCase(Locale.ROOT);
		return extendedTypes(type).contains("OncePerRequestFilter") && (name.contains("jwt") || name.contains("auth"));
	}

	/** An interface extending JpaRepository, CrudRepository, ... (anything whose name ends in Repository). */
	private static boolean isSpringDataRepository(TypeDeclaration<?> type) {
		return type instanceof ClassOrInterfaceDeclaration declaration && declaration.isInterface()
				&& extendedTypes(type).stream().anyMatch(name -> name.endsWith("Repository"));
	}

	private static boolean isDtoName(String name) {
		return endsWithAny(name, "Request", "Response", "Command", "Event")
				|| name.toLowerCase(Locale.ROOT).endsWith("dto");
	}

	private static Set<String> extendedTypes(TypeDeclaration<?> type) {
		if (type instanceof ClassOrInterfaceDeclaration declaration) {
			return declaration.getExtendedTypes().stream()
					.map(ClassOrInterfaceType::getNameAsString)
					.collect(Collectors.toSet());
		}
		return Set.of();
	}

	/** {@code @Service} and {@code @org.springframework.stereotype.Service} both give {@code Service}. */
	private static Set<String> names(NodeList<AnnotationExpr> annotations) {
		return annotations.stream()
				.map(annotation -> annotation.getName().getIdentifier())
				.collect(Collectors.toSet());
	}

	/** {@code SecurityFilterChain} for both the simple and the fully qualified spelling. */
	private static String simpleName(Type type) {
		return type.isClassOrInterfaceType() ? type.asClassOrInterfaceType().getNameAsString() : type.asString();
	}

	private static boolean containsAny(Set<String> names, Set<String> wanted) {
		return !Collections.disjoint(names, wanted);
	}

	private static boolean endsWithAny(String name, String... suffixes) {
		for (String suffix : suffixes) {
			if (name.endsWith(suffix)) {
				return true;
			}
		}
		return false;
	}

}
