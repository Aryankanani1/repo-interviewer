package com.aryankanani.repointerviewer.api.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The web page (task 5.2, DECISIONS.md D-034). Boot already serves {@code static/index.html} at {@code /}; this adds
 * the trusted documents (task 4.6) at {@code /documents/<Name>.graphql}, so the page loads the exact text of each
 * operation it sends instead of keeping copies that could drift from the list prod enforces.
 */
@Configuration(proxyBeanMethods = false)
class WebPageConfiguration implements WebMvcConfigurer {

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		registry.addResourceHandler("/documents/**").addResourceLocations("classpath:/graphql-documents/");
	}

}
