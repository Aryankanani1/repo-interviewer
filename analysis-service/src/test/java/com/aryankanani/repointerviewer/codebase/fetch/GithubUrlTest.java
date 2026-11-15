package com.aryankanani.repointerviewer.codebase.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class GithubUrlTest {

	@ParameterizedTest
	@CsvSource({
			"https://github.com/acme/shop,                  acme, shop",
			"https://github.com/acme/shop/,                 acme, shop",
			"https://github.com/acme/shop.git,              acme, shop",
			"https://github.com/acme/shop.git/,             acme, shop",
			"HTTPS://GitHub.com/Acme/Shop,                  acme, shop",
			"'  https://github.com/acme/shop  ',            acme, shop",
			"https://github.com/spring-projects/spring-petclinic, spring-projects, spring-petclinic",
			"https://github.com/acme/my.repo_v2,            acme, my.repo_v2",
			"https://github.com/acme/acme.github.io,        acme, acme.github.io" })
	void acceptsAndNormalizesGithubRepositoryUrls(String raw, String owner, String repo) {
		GithubUrl url = GithubUrl.parse(raw);

		assertThat(url.owner()).isEqualTo(owner);
		assertThat(url.repo()).isEqualTo(repo);
		assertThat(url.normalized()).isEqualTo("https://github.com/" + owner + "/" + repo);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {
			"   ",
			"http://github.com/acme/shop",              // not https
			"https://gitlab.com/acme/shop",             // other host
			"https://github.com.evil.com/acme/shop",    // host that only starts with github.com
			"https://user@github.com/acme/shop",        // credentials in the URL
			"https://github.com:443/acme/shop",         // explicit port
			"https://github.com/acme",                  // no repository
			"https://github.com/acme/shop/tree/main",   // extra path
			"https://github.com/acme/shop?tab=readme",  // query string
			"https://github.com/acme/shop#readme",      // fragment
			"https://github.com/acme/sh op",            // space
			"https://github.com/acme/..",               // parent folder
			"https://github.com/acme/.",
			"git@github.com:acme/shop.git",             // SSH form
			"github.com/acme/shop" })                   // no scheme
	void rejectsAnythingElseAsInvalidUrlOnTheGithubUrlField(String raw) {
		assertThatThrownBy(() -> GithubUrl.parse(raw))
				.isInstanceOfSatisfying(DomainException.class, ex -> {
					assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_URL);
					assertThat(ex.field()).contains("githubUrl");
				});
	}

	@Test
	void rejectsPartsLongerThan100Characters() {
		assertThatThrownBy(() -> GithubUrl.parse("https://github.com/" + "a".repeat(101) + "/shop"))
				.isInstanceOf(DomainException.class);
	}

	@Test
	void cloneUriIsRebuiltFromTheValidatedParts() {
		assertThat(GithubUrl.parse("HTTPS://GitHub.com/Acme/Shop/").cloneUri())
				.isEqualTo("https://github.com/acme/shop.git");
	}

}
