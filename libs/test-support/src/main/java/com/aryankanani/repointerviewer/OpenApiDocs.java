package com.aryankanani.repointerviewer;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.HttpExchange;
import tools.jackson.databind.JsonNode;

/**
 * Compares a service's OpenAPI document (task 4.2), which springdoc generates from its controller, with the contract
 * interface the controller implements and the callers' HTTP client uses: every method of the interface must be there
 * with its HTTP method and path, and each parameter where its annotation puts it (path, query or request body).
 */
public final class OpenApiDocs {

	private OpenApiDocs() {
	}

	/** @return one line per method or parameter the document is missing; empty if it describes all of {@code api} */
	public static List<String> missing(JsonNode document, Class<?> api) {
		String prefix = AnnotatedElementUtils.findMergedAnnotation(api, HttpExchange.class).url();
		List<String> missing = new ArrayList<>();
		for (Method method : api.getMethods()) {
			HttpExchange exchange = AnnotatedElementUtils.findMergedAnnotation(method, HttpExchange.class);
			String path = prefix + exchange.url();
			String endpoint = api.getSimpleName() + "." + method.getName() + " (" + exchange.method() + " " + path + ")";
			JsonNode operation = document.path("paths").path(path).path(exchange.method().toLowerCase(Locale.ROOT));
			if (operation.isMissingNode()) {
				missing.add(endpoint);
				continue;
			}
			for (Parameter parameter : method.getParameters()) {
				if (parameter.isAnnotationPresent(RequestBody.class)) {
					if (operation.path("requestBody").isMissingNode()) {
						missing.add(endpoint + ": the request body");
					}
				}
				else if (!hasParameter(operation, parameter)) {
					missing.add(endpoint + ": " + location(parameter) + " parameter " + parameter.getName());
				}
			}
		}
		return missing;
	}

	private static boolean hasParameter(JsonNode operation, Parameter parameter) {
		for (JsonNode documented : operation.path("parameters")) {
			if (documented.path("name").asString().equals(parameter.getName())
					&& documented.path("in").asString().equals(location(parameter))) {
				return true;
			}
		}
		return false;
	}

	/** OpenAPI's "in" for a parameter of an {@code @HttpExchange} method that is not the body. */
	private static String location(Parameter parameter) {
		if (parameter.isAnnotationPresent(PathVariable.class)) {
			return "path";
		}
		if (parameter.isAnnotationPresent(RequestParam.class)) {
			return "query";
		}
		throw new IllegalArgumentException("Not a path, query or body parameter: " + parameter);
	}

}
