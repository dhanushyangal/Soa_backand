package com.bidding.paymentservice.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bidding.common.security.CurrentUser;
import com.bidding.common.web.ApiException;
import com.bidding.paymentservice.domain.LedgerEntry;
import com.bidding.paymentservice.domain.Payment;
import com.bidding.paymentservice.domain.Profile;
import com.bidding.paymentservice.domain.Wallet;
import com.bidding.paymentservice.domain.WebhookEvent;
import com.bidding.paymentservice.ledger.LedgerMath;
import com.bidding.paymentservice.ledger.WalletState;
import com.bidding.paymentservice.repo.LedgerEntryRepository;
import com.bidding.paymentservice.repo.PaymentRepository;
import com.bidding.paymentservice.repo.ProfileRepository;
import com.bidding.paymentservice.repo.WalletRepository;
import com.bidding.paymentservice.repo.WebhookEventRepository;
import com.bidding.paymentservice.web.CheckoutResponse;
import com.bidding.paymentservice.web.HoldRequest;
import com.bidding.paymentservice.web.ReleaseRequest;
import com.bidding.paymentservice.web.SettleRequest;
import com.bidding.paymentservice.web.WalletResponse;
import com.dodopayments.api.client.DodoPaymentsClient;
import com.dodopayments.api.core.JsonValue;
import com.dodopayments.api.core.UnwrapWebhookParams;
import com.dodopayments.api.core.http.Headers;
import com.dodopayments.api.models.checkoutsessions.CheckoutSessionRequest;
import com.dodopayments.api.models.checkoutsessions.CheckoutSessionResponse;
import com.dodopayments.api.models.checkoutsessions.CheckoutSessionStatus;
import com.dodopayments.api.models.checkoutsessions.ProductItemReq;
import com.dodopayments.api.models.misc.Metadata;
import com.dodopayments.api.models.payments.IntentStatus;
import com.dodopayments.api.models.payments.NewCustomer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class WalletService {

	private final WalletRepository wallets;
	private final ProfileRepository profiles;
	private final LedgerEntryRepository ledger;
	private final PaymentRepository payments;
	private final WebhookEventRepository webhookEvents;
	private final DodoPaymentsClient dodo;
	private final ObjectMapper mapper;

	@Value("${bideasy.dodo-product-id:pdt_0No8GYiVeUpU21JfBYefp}")
	private String productId;

	@Value("${bideasy.dodo-return-url:http://localhost:3000/wallet}")
	private String returnUrl;

	public WalletService(
			WalletRepository wallets,
			ProfileRepository profiles,
			LedgerEntryRepository ledger,
			PaymentRepository payments,
			WebhookEventRepository webhookEvents,
			DodoPaymentsClient dodo,
			ObjectMapper mapper) {
		this.wallets = wallets;
		this.profiles = profiles;
		this.ledger = ledger;
		this.payments = payments;
		this.webhookEvents = webhookEvents;
		this.dodo = dodo;
		this.mapper = mapper;
	}

	@Transactional
	public Wallet ensureWallet(String clerkUserId) {
		return wallets.findByClerkUserId(clerkUserId).orElseGet(() -> {
			Profile profile = profiles.findByClerkUserId(clerkUserId)
					.orElseThrow(() -> ApiException.badRequest("profile_required", "Complete your profile before using the wallet"));
			Wallet wallet = new Wallet();
			wallet.setProfileId(profile.getId());
			wallet.setClerkUserId(clerkUserId);
			return wallets.save(wallet);
		});
	}

	@Transactional
	public WalletResponse myWallet() {
		Wallet wallet = ensureWallet(CurrentUser.clerkUserId());
		return new WalletResponse(wallet.getAvailableBalanceCents(), wallet.getHeldBalanceCents(), wallet.getCurrency());
	}

	@Transactional(readOnly = true)
	public List<LedgerEntry> myLedger() {
		return ledger.findByClerkUserIdOrderByCreatedAtDesc(CurrentUser.clerkUserId());
	}

	@Transactional
	public CheckoutResponse startTopUp(int amountCents) {
		String clerkUserId = CurrentUser.clerkUserId();
		Wallet wallet = ensureWallet(clerkUserId);
		Payment payment = new Payment();
		payment.setProfileId(wallet.getProfileId());
		payment.setClerkUserId(clerkUserId);
		payment.setAmountCents(amountCents);
		payment.setCurrency(wallet.getCurrency());
		payment.setMetadata("{\"clerkUserId\":\"" + clerkUserId + "\"}");
		payment = payments.saveAndFlush(payment);

		CheckoutSessionRequest.Builder builder = CheckoutSessionRequest.builder()
				.addProductCart(ProductItemReq.builder()
						.productId(productId)
						.quantity(1)
						.amount(amountCents)
						.build())
				.returnUrl(returnUrl)
				.metadata(Metadata.builder()
						.putAdditionalProperty("clerkUserId", JsonValue.from(clerkUserId))
						.putAdditionalProperty("paymentId", JsonValue.from(payment.getId().toString()))
						.build());
		String email = CurrentUser.email();
		if (email != null && !email.isBlank()) {
			builder.customer(NewCustomer.builder().email(email).name(CurrentUser.displayName()).build());
		}
		CheckoutSessionRequest request = builder.build();
		CheckoutSessionResponse session = dodo.checkoutSessions().create(request);
		payment.setDodoCheckoutSessionId(session.sessionId());
		payments.save(payment);
		return new CheckoutResponse(payment.getId(), session.checkoutUrl().orElse(""), session.sessionId());
	}

	@Transactional
	public WalletResponse syncOpenTopUps() {
		String clerkUserId = CurrentUser.clerkUserId();
		ensureWallet(clerkUserId);
		for (Payment local : payments.findByClerkUserIdAndStatus(clerkUserId, "open")) {
			confirmOpenPayment(local);
		}
		return myWallet();
	}

	@Transactional
	public void hold(HoldRequest request) {
		if (ledger.existsByIdempotencyKey(request.idempotencyKey())) {
			return;
		}
		Wallet wallet = lockWallet(request.clerkUserId());
		try {
			WalletState next = LedgerMath.hold(new WalletState(wallet.getAvailableBalanceCents(), wallet.getHeldBalanceCents()), request.amountCents());
			apply(wallet, next);
			ledger.save(LedgerEntry.of(
					wallet.getProfileId(),
					request.clerkUserId(),
					"hold",
					request.amountCents(),
					request.auctionId(),
					request.bidId(),
					null,
					request.idempotencyKey()));
		} catch (IllegalArgumentException ex) {
			throw new ApiException(HttpStatus.CONFLICT, ex.getMessage(), "Wallet cannot cover this bid");
		}
	}

	@Transactional
	public void release(ReleaseRequest request) {
		if (ledger.existsByIdempotencyKey(request.idempotencyKey())) {
			return;
		}
		LedgerEntry hold = ledger.findByIdempotencyKey("hold:" + request.bidId())
				.orElseThrow(() -> ApiException.notFound("hold_not_found", "No hold to release"));
		Wallet wallet = lockWallet(request.clerkUserId());
		WalletState next = LedgerMath.release(new WalletState(wallet.getAvailableBalanceCents(), wallet.getHeldBalanceCents()), hold.getAmountCents());
		apply(wallet, next);
		ledger.save(LedgerEntry.of(
				wallet.getProfileId(),
				request.clerkUserId(),
				"release",
				hold.getAmountCents(),
				request.auctionId(),
				request.bidId(),
				null,
				request.idempotencyKey()));
	}

	@Transactional
	public void settle(SettleRequest request) {
		List<LedgerEntry> holds = ledger.findByAuctionIdAndEntryType(request.auctionId(), "hold");
		for (LedgerEntry hold : holds) {
			boolean winner = request.winningBidId() != null && request.winningBidId().equals(hold.getBidId());
			String key = (winner ? "capture:" : "release:") + hold.getBidId();
			if (ledger.existsByIdempotencyKey(key)) {
				continue;
			}
			Wallet wallet = lockWallet(hold.getClerkUserId());
			WalletState current = new WalletState(wallet.getAvailableBalanceCents(), wallet.getHeldBalanceCents());
			WalletState next = winner
					? LedgerMath.capture(current, hold.getAmountCents())
					: LedgerMath.release(current, hold.getAmountCents());
			apply(wallet, next);
			ledger.save(LedgerEntry.of(
					wallet.getProfileId(),
					hold.getClerkUserId(),
					winner ? "capture" : "release",
					hold.getAmountCents(),
					request.auctionId(),
					hold.getBidId(),
					null,
					key));
		}
	}

	@Transactional
	public void handleWebhook(String rawBody, String webhookId, String signature, String timestamp) {
		Headers headers = Headers.builder()
				.put("webhook-id", webhookId == null ? "" : webhookId)
				.put("webhook-signature", signature == null ? "" : signature)
				.put("webhook-timestamp", timestamp == null ? "" : timestamp)
				.build();
		try {
			dodo.webhooks().unwrap(UnwrapWebhookParams.builder().body(rawBody).headers(headers).build());
		} catch (RuntimeException ex) {
			throw ApiException.unauthorized("Invalid webhook signature");
		}

		if (webhookId != null && webhookEvents.findByWebhookId(webhookId).isPresent()) {
			return;
		}

		JsonNode root;
		try {
			root = mapper.readTree(rawBody);
		} catch (Exception ex) {
			throw ApiException.badRequest("invalid_payload", "Webhook payload is not JSON");
		}
		String type = root.path("type").asText();
		WebhookEvent event = new WebhookEvent();
		event.setWebhookId(webhookId == null ? UUID.randomUUID().toString() : webhookId);
		event.setEventType(type);
		event.setPayload(rawBody);
		try {
			webhookEvents.saveAndFlush(event);
		} catch (DataIntegrityViolationException duplicate) {
			return;
		}

		try {
			if ("payment.succeeded".equals(type)) {
				creditFromPayment(root.path("data"));
			} else if ("payment.failed".equals(type)) {
				markFailed(root.path("data"));
			}
			event.setProcessed(true);
			event.setProcessedAt(Instant.now());
		} catch (RuntimeException ex) {
			event.setErrorMessage(ex.getMessage());
			webhookEvents.save(event);
			throw ex;
		}
		webhookEvents.save(event);
	}

	private void confirmOpenPayment(Payment local) {
		String sessionId = local.getDodoCheckoutSessionId();
		if (sessionId == null || sessionId.isBlank()) {
			return;
		}
		CheckoutSessionStatus session;
		try {
			session = dodo.checkoutSessions().retrieve(sessionId);
		} catch (RuntimeException ex) {
			return;
		}
		String dodoPaymentId = session.paymentId().orElse(null);
		IntentStatus status = session.paymentStatus().orElse(null);
		long amount = local.getAmountCents();
		String currency = local.getCurrency();
		if (dodoPaymentId != null) {
			try {
				com.dodopayments.api.models.payments.Payment dodoPay = dodo.payments().retrieve(dodoPaymentId);
				status = dodoPay.status().orElse(status);
				if (dodoPay.totalAmount() > 0) {
					amount = dodoPay.totalAmount();
				}
				currency = dodoPay.currency().asString();
			} catch (RuntimeException ignored) {
			}
		}
		if (status == null) {
			return;
		}
		if (IntentStatus.SUCCEEDED.equals(status)) {
			creditSucceeded(local, dodoPaymentId, amount, currency);
		} else if (IntentStatus.FAILED.equals(status) || IntentStatus.CANCELLED.equals(status)) {
			local.setStatus("failed");
			payments.save(local);
		}
	}

	private void creditFromPayment(JsonNode data) {
		String dodoPaymentId = firstText(data, "payment_id", "id");
		JsonNode metadata = data.path("metadata");
		String clerkUserId = firstText(metadata, "clerkUserId", "clerk_user_id");
		String paymentId = firstText(metadata, "paymentId", "payment_id");
		long amount = data.path("total_amount").asLong(data.path("amount").asLong(0));
		if (amount <= 0) {
			amount = data.path("settlement_amount").asLong(0);
		}
		Payment payment = paymentId == null ? new Payment() : payments.findById(UUID.fromString(paymentId)).orElse(new Payment());
		if (clerkUserId == null) {
			clerkUserId = payment.getClerkUserId();
		}
		if (clerkUserId == null) {
			throw ApiException.badRequest("missing_user", "Webhook is missing clerkUserId");
		}
		if (payment.getClerkUserId() == null) {
			payment.setClerkUserId(clerkUserId);
		}
		if (amount <= 0) {
			amount = payment.getAmountCents();
		}
		String currency = data.has("currency") ? data.path("currency").asText(payment.getCurrency()) : payment.getCurrency();
		creditSucceeded(payment, dodoPaymentId, amount, currency);
	}

	private void creditSucceeded(Payment payment, String dodoPaymentId, long amount, String currency) {
		if ("succeeded".equals(payment.getStatus())) {
			return;
		}
		if (dodoPaymentId != null && payments.findByDodoPaymentId(dodoPaymentId).filter(p -> "succeeded".equals(p.getStatus())).isPresent()) {
			payment.setStatus("succeeded");
			payment.setDodoPaymentId(dodoPaymentId);
			payments.save(payment);
			return;
		}
		String clerkUserId = payment.getClerkUserId();
		if (clerkUserId == null) {
			throw ApiException.badRequest("missing_user", "Payment is missing clerkUserId");
		}
		String key = dodoPaymentId == null
				? "top_up:session:" + payment.getDodoCheckoutSessionId()
				: "top_up:" + dodoPaymentId;
		Wallet wallet = ensureWallet(clerkUserId);
		if (ledger.existsByIdempotencyKey(key)) {
			payment.setProfileId(wallet.getProfileId());
			payment.setDodoPaymentId(dodoPaymentId);
			payment.setStatus("succeeded");
			if (amount > 0) {
				payment.setAmountCents(amount);
			}
			if (currency != null && !currency.isBlank()) {
				payment.setCurrency(currency);
			}
			payments.save(payment);
			return;
		}
		Wallet locked = lockWallet(clerkUserId);
		if (amount > 0) {
			WalletState next = LedgerMath.topUp(new WalletState(locked.getAvailableBalanceCents(), locked.getHeldBalanceCents()), amount);
			apply(locked, next);
			try {
				ledger.save(LedgerEntry.of(locked.getProfileId(), clerkUserId, "top_up", amount, null, null, dodoPaymentId, key));
			} catch (DataIntegrityViolationException duplicate) {
				return;
			}
		}
		payment.setProfileId(wallet.getProfileId());
		payment.setClerkUserId(clerkUserId);
		payment.setDodoPaymentId(dodoPaymentId);
		if (amount > 0) {
			payment.setAmountCents(amount);
		}
		payment.setStatus("succeeded");
		if (currency != null && !currency.isBlank()) {
			payment.setCurrency(currency);
		}
		payments.save(payment);
	}

	private void markFailed(JsonNode data) {
		String dodoPaymentId = firstText(data, "payment_id", "id");
		if (dodoPaymentId == null) {
			return;
		}
		payments.findByDodoPaymentId(dodoPaymentId).ifPresent(payment -> {
			payment.setStatus("failed");
			payments.save(payment);
		});
	}

	private Wallet lockWallet(String clerkUserId) {
		return wallets.findByClerkUserIdForUpdate(clerkUserId)
				.orElseThrow(() -> ApiException.notFound("wallet_not_found", "Wallet not found"));
	}

	private static void apply(Wallet wallet, WalletState state) {
		wallet.setAvailableBalanceCents(state.availableCents());
		wallet.setHeldBalanceCents(state.heldCents());
	}

	private static String firstText(JsonNode node, String... keys) {
		for (String key : keys) {
			JsonNode value = node.path(key);
			if (!value.isMissingNode() && !value.isNull() && !value.asText().isBlank()) {
				return value.asText();
			}
		}
		return null;
	}
}
