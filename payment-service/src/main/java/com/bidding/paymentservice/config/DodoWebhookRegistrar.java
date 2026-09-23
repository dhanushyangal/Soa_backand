package com.bidding.paymentservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import com.dodopayments.api.client.DodoPaymentsClient;
import com.dodopayments.api.models.webhookevents.WebhookEventType;
import com.dodopayments.api.models.webhooks.WebhookCreateParams;

import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!test")
public class DodoWebhookRegistrar {

	@Value("${DODO_WEBHOOK_URL:}")
	private String webhookUrl;

	@Bean
	ApplicationRunner registerWebhook(DodoPaymentsClient client) {
		return args -> {
			if (!StringUtils.hasText(webhookUrl)) {
				return;
			}
			try {
				client.webhooks().create(WebhookCreateParams.builder()
						.url(webhookUrl)
						.description("BidEasy wallet credits")
						.addFilterType(WebhookEventType.PAYMENT_SUCCEEDED)
						.addFilterType(WebhookEventType.PAYMENT_FAILED)
						.build());
			} catch (RuntimeException ignored) {
				// Endpoint may already exist from a previous boot.
			}
		};
	}
}
