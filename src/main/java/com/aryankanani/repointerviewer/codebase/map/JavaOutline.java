package com.aryankanani.repointerviewer.codebase.map;

import com.aryankanani.repointerviewer.codebase.scan.FileRole;
import com.aryankanani.repointerviewer.codebase.scan.JavaParsing;
import com.aryankanani.repointerviewer.codebase.scan.RedactedFile;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.TypeParameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The outline of one Java file for the repo map (LLD §6.9): per top-level type, one type line and its member
 * lines.
 * <ul>
 * <li>Type line: {@code OrderController [CONTROLLER] @RestController @RequestMapping("/orders")}, then
 * {@code extends} / {@code implements}. A record shows its components: {@code record(BigDecimal amount)}; an enum
 * its constants.</li>
 * <li>Methods: public ones (every non-private one in an interface), with their annotations, because
 * {@code @PostMapping} and {@code @Transactional} are what an interviewer asks about. Constructors, plain
 * getters and setters, {@code equals}, {@code hashCode} and {@code toString} are left out: they add tokens and
 * say nothing new. Test classes also show their {@code @Test} methods, which JUnit 5 lets be package-private.</li>
 * <li>Fields: only for ENTITY, for data-model questions.</li>
 * <li>Nested types: one line, for example {@code enum Status { PAID, REFUNDED }}.</li>
 * </ul>
 * Annotations are printed as written, on one line, and cut at {@value #MAX_ANNOTATION_CHARS} characters (a long
 * {@code @Query}). Parameter annotations ({@code @Valid @RequestBody}) are left out.
 */
final class JavaOutline {

	static final int MAX_ANNOTATION_CHARS = 100;

	static final int MAX_ENUM_CONSTANTS = 20;

	/** Annotations that say nothing about the design. */
	private static final Set<String> SKIPPED_ANNOTATIONS = Set.of("Override", "SuppressWarnings");

	private static final Set<String> TEST_ANNOTATIONS = Set.of("Test", "ParameterizedTest", "RepeatedTest",
			"TestFactory", "TestTemplate");

	private static final Pattern GETTER = Pattern.compile("(get|is)[A-Z].*");

	private static final Pattern SETTER = Pattern.compile("set[A-Z].*");

	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	/**
	 * @param line the type line, without indentation
	 * @param members one line per shown member, without indentation
	 */
	record TypeOutline(String line, List<String> members) {
	}

	private JavaOutline() {
	}

	/** @return one outline per top-level type; empty when the file does not parse or declares no type */
	static List<TypeOutline> of(RedactedFile file, FileRole role) {
		Optional<CompilationUnit> unit = JavaParsing.parse(file.content());
		if (unit.isEmpty()) {
			return List.of();
		}
		return unit.get().getTypes().stream().map(type -> outline(type, role)).toList();
	}

	private static TypeOutline outline(TypeDeclaration<?> type, FileRole role) {
		String line = type.getNameAsString() + typeParameters(type) + " [" + role + "]"
				+ annotations(type.getAnnotations()) + shape(type);
		List<String> members = new ArrayList<>();
		for (BodyDeclaration<?> member : type.getMembers()) {
			if (member instanceof FieldDeclaration field && role == FileRole.ENTITY && !field.isStatic()) {
				for (VariableDeclarator variable : field.getVariables()) {
					members.add("- " + variable.getNameAsString() + ": " + variable.getType().asString()
							+ annotations(field.getAnnotations()));
				}
			}
			else if (member instanceof MethodDeclaration method && isShown(method, type, role)) {
				members.add(signature(method, type) + annotations(method.getAnnotations()));
			}
			else if (member instanceof TypeDeclaration<?> nested) {
				members.add(nestedLine(nested));
			}
		}
		return new TypeOutline(line, members);
	}

	/** What follows the annotations on the type line: supertypes, record components or enum constants. */
	private static String shape(TypeDeclaration<?> type) {
		return switch (type) {
			case ClassOrInterfaceDeclaration declaration -> supertypes(" extends ", declaration.getExtendedTypes())
					+ supertypes(" implements ", declaration.getImplementedTypes());
			case RecordDeclaration record -> " record" + parameters(record.getParameters())
					+ supertypes(" implements ", record.getImplementedTypes());
			case EnumDeclaration enumeration -> " enum " + constants(enumeration);
			case AnnotationDeclaration annotation -> " @interface";
			default -> "";
		};
	}

	private static String nestedLine(TypeDeclaration<?> nested) {
		String name = nested.getNameAsString();
		return switch (nested) {
			case ClassOrInterfaceDeclaration declaration -> (declaration.isInterface() ? "interface " : "class ") + name;
			case RecordDeclaration record -> "record " + name + parameters(record.getParameters());
			case EnumDeclaration enumeration -> "enum " + name + " " + constants(enumeration);
			case AnnotationDeclaration annotation -> "@interface " + name;
			default -> name;
		};
	}

	private static boolean isShown(MethodDeclaration method, TypeDeclaration<?> type, FileRole role) {
		boolean testMethod = role == FileRole.TEST && method.getAnnotations().stream()
				.anyMatch(annotation -> TEST_ANNOTATIONS.contains(annotation.getName().getIdentifier()));
		return (isPublic(method, type) || testMethod) && !isAccessor(method) && !isObjectMethod(method);
	}

	/** Interface methods are public unless declared private. */
	private static boolean isPublic(MethodDeclaration method, TypeDeclaration<?> type) {
		boolean inInterface = type instanceof ClassOrInterfaceDeclaration declaration && declaration.isInterface();
		return inInterface ? !method.isPrivate() : method.isPublic();
	}

	private static boolean isAccessor(MethodDeclaration method) {
		String name = method.getNameAsString();
		int parameters = method.getParameters().size();
		return (GETTER.matcher(name).matches() && parameters == 0 && !method.getType().isVoidType())
				|| (SETTER.matcher(name).matches() && parameters == 1);
	}

	private static boolean isObjectMethod(MethodDeclaration method) {
		String name = method.getNameAsString();
		int parameters = method.getParameters().size();
		return (name.equals("equals") && parameters == 1)
				|| ((name.equals("hashCode") || name.equals("toString")) && parameters == 0);
	}

	/** {@code + refund(UUID orderId, RefundRequest request): Order}; no {@code +} when it is not public. */
	private static String signature(MethodDeclaration method, TypeDeclaration<?> type) {
		return (isPublic(method, type) ? "+ " : "") + method.getNameAsString() + parameters(method.getParameters())
				+ ": " + method.getType().asString();
	}

	/** {@code (UUID id, String... tags)}: types and names only, no annotations or {@code final}. */
	private static String parameters(NodeList<Parameter> parameters) {
		return parameters.stream()
				.map(parameter -> parameter.getType().asString() + (parameter.isVarArgs() ? "..." : "") + " "
						+ parameter.getNameAsString())
				.collect(Collectors.joining(", ", "(", ")"));
	}

	private static String typeParameters(TypeDeclaration<?> type) {
		NodeList<TypeParameter> parameters = switch (type) {
			case ClassOrInterfaceDeclaration declaration -> declaration.getTypeParameters();
			case RecordDeclaration record -> record.getTypeParameters();
			default -> new NodeList<>();
		};
		return parameters.isEmpty() ? ""
				: parameters.stream().map(TypeParameter::asString).collect(Collectors.joining(", ", "<", ">"));
	}

	private static String supertypes(String keyword, NodeList<ClassOrInterfaceType> types) {
		return types.isEmpty() ? ""
				: keyword + types.stream().map(ClassOrInterfaceType::asString).collect(Collectors.joining(", "));
	}

	/** {@code { PAID, REFUNDED }}, at most {@value #MAX_ENUM_CONSTANTS} names. */
	private static String constants(EnumDeclaration enumeration) {
		NodeList<EnumConstantDeclaration> entries = enumeration.getEntries();
		String shown = entries.stream()
				.limit(MAX_ENUM_CONSTANTS)
				.map(EnumConstantDeclaration::getNameAsString)
				.collect(Collectors.joining(", "));
		int more = entries.size() - MAX_ENUM_CONSTANTS;
		return "{ " + shown + (more > 0 ? ", +" + more + " more" : "") + " }";
	}

	/** Each annotation with a leading space, on one line, cut at {@value #MAX_ANNOTATION_CHARS} characters. */
	private static String annotations(NodeList<AnnotationExpr> annotations) {
		return annotations.stream()
				.filter(annotation -> !SKIPPED_ANNOTATIONS.contains(annotation.getName().getIdentifier()))
				.map(annotation -> " " + shorten(WHITESPACE.matcher(annotation.toString()).replaceAll(" ")))
				.collect(Collectors.joining());
	}

	private static String shorten(String text) {
		return text.length() <= MAX_ANNOTATION_CHARS ? text : text.substring(0, MAX_ANNOTATION_CHARS - 1) + "…";
	}

}
