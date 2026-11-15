package com.aryankanani.repointerviewer.api.scalar;

import graphql.scalars.ExtendedScalars;
import graphql.schema.idl.RuntimeWiring;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;
import org.springframework.stereotype.Component;

/**
 * Gives graphql-java an implementation for every custom scalar declared in schema.graphqls.
 * Without it the schema cannot be built and the application does not start.
 *
 * <p>A {@code RuntimeWiringConfigurer} component (not a {@code @Configuration} class) so that
 * {@code @GraphQlTest} slices pick it up too.
 */
@Component
class ScalarConfig implements RuntimeWiringConfigurer {

	@Override
	public void configure(RuntimeWiring.Builder builder) {
		builder.scalar(ExtendedScalars.DateTime);
	}

}
