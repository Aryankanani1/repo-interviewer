package com.aryankanani.repointerviewer.common.web;

import com.aryankanani.repointerviewer.common.error.DomainException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * For a {@code RestClient} that calls another service of this system (task 4.1): an error response that is a
 * {@link DomainProblems} problem detail throws the {@link DomainException} the other side threw. Any other error
 * response throws Spring's usual {@code HttpClientErrorException} or {@code HttpServerErrorException}.
 */
public final class DomainErrorHandler implements RestClient.ResponseSpec.ErrorHandler {

	/** The fields of a problem detail this handler reads; the others are ignored. */
	private record Problem(String code, String detail, String field) {
	}

	private final JsonMapper jsonMapper;

	public DomainErrorHandler(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	@Override
	public void handle(HttpRequest request, ClientHttpResponse response) throws IOException {
		byte[] body = response.getBody().readAllBytes();
		Problem problem = read(body);
		DomainException domain = problem == null ? null
				: DomainProblems.toException(problem.code(), problem.detail(), problem.field());
		if (domain != null) {
			throw domain;
		}
		HttpStatusCode status = response.getStatusCode();
		String statusText = response.getStatusText();
		if (status.is4xxClientError()) {
			throw HttpClientErrorException.create(status, statusText, response.getHeaders(), body,
					StandardCharsets.UTF_8);
		}
		throw HttpServerErrorException.create(status, statusText, response.getHeaders(), body, StandardCharsets.UTF_8);
	}

	private Problem read(byte[] body) {
		try {
			return body.length == 0 ? null : jsonMapper.readValue(body, Problem.class);
		}
		catch (JacksonException ex) {
			return null;   // not JSON (an HTML error page from a proxy): the usual exception below
		}
	}

}
