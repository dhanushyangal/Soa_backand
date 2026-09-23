package com.bidding.common.feign;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.bidding.common.security.InternalTokenFilter;
import com.bidding.common.web.ApiException;

import feign.RequestInterceptor;
import feign.codec.ErrorDecoder;

public class FeignConfig {

	@Value("${bideasy.internal-token:}")
	private String internalToken;

	@Bean
	RequestInterceptor forwardingInterceptor() {
		return template -> {
			Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
			if (authentication instanceof JwtAuthenticationToken jwt) {
				template.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.getToken().getTokenValue());
			}
			if (internalToken != null && !internalToken.isBlank()) {
				template.header(InternalTokenFilter.HEADER, internalToken);
			}
		};
	}

	@Bean
	ErrorDecoder errorDecoder() {
		return (methodKey, response) -> {
			HttpStatus status = HttpStatus.resolve(response.status());
			if (status == null) {
				status = HttpStatus.INTERNAL_SERVER_ERROR;
			}
			String code = "upstream_error";
			String message = "Upstream service failed";
			if (response.body() != null) {
				try (InputStream body = response.body().asInputStream()) {
					String json = new String(body.readAllBytes(), StandardCharsets.UTF_8);
					code = jsonField(json, "code", code);
					message = jsonField(json, "message", message);
				} catch (IOException ignored) {
					// Fall through to a generic upstream error.
				}
			}
			return new ApiException(status, code, message);
		};
	}

	private static String jsonField(String json, String key, String fallback) {
		String needle = "\"" + key + "\"";
		int index = json.indexOf(needle);
		if (index < 0) {
			return fallback;
		}
		int colon = json.indexOf(':', index + needle.length());
		int start = json.indexOf('"', colon + 1);
		int end = start < 0 ? -1 : json.indexOf('"', start + 1);
		if (start < 0 || end < 0) {
			return fallback;
		}
		return json.substring(start + 1, end);
	}
}
