package co.tumipay.orchestrator.application.validator;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import co.tumipay.orchestrator.domain.exception.ValidationException;
import co.tumipay.orchestrator.domain.model.Customer;
import co.tumipay.orchestrator.domain.model.DocumentType;
import co.tumipay.orchestrator.domain.model.Transaction;
import co.tumipay.orchestrator.infrastructure.security.SsrfGuard;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class TransactionDomainValidator {

	private final SsrfGuard ssrfGuard;

	private static final String FIELD_WEBHOOK_URL          = "webhook_url";
	private static final String FIELD_REDIRECT_URL         = "redirect_url";
	private static final String FIELD_DOCUMENT_TYPE        = "customer.document_type";
	private static final String FIELD_DOCUMENT_NUMBER      = "customer.document_number";
	private static final String FIELD_COUNTRY_CALLING_CODE = "customer.country_calling_code";
	private static final String FIELD_PHONE_NUMBER         = "customer.phone_number";

	private static final Pattern EMAIL_PATTERN = Pattern
			.compile("^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$");

	private static final Pattern URL_PATTERN = Pattern.compile("^https?://[^\\s/$.?#][^\\s]*$");

	private static final Pattern PHONE_PATTERN = Pattern.compile("^[0-9]{7,15}$");

	/** E.164-style calling code: + seguido de 1 a 4 dígitos (ej: +57, +1, +598). */
	private static final Pattern CALLING_CODE_PATTERN = Pattern.compile("^\\+[1-9][0-9]{0,3}$");

	// ISO 4217 — subset of most common currencies (extend as needed)
	private static final Set<String> VALID_CURRENCIES = Set.of("COP", "USD", "EUR", "GBP", "MXN", "BRL", "ARS", "PEN",
			"CLP", "UYU");

	// ISO 3166-1 Alpha-2 — subset for LATAM + major countries
	private static final Set<String> VALID_COUNTRIES = Set.of("CO", "US", "BR", "MX", "AR", "PE", "CL", "UY", "EC",
			"VE", "GB", "DE", "FR", "ES", "IT", "CA", "AU", "JP", "CN", "KR");

	@Value("${tumipay.orchestrator.transaction.max-amount-cents:10000000000}")
	private long maxAmountCents; 

	public void validate(Transaction transaction) {
		List<String> violations = new ArrayList<>();

		validateAmount(transaction.getAmount(), violations);
		validateCurrencyCode(transaction.getCurrencyCode(), violations);
		validateCountryCode(transaction.getCountryCode(), violations);
		validateUrlFormat(transaction.getWebhookUrl(), FIELD_WEBHOOK_URL, violations);
		validateUrlFormat(transaction.getRedirectUrl(), FIELD_REDIRECT_URL, violations);

		if (violations.stream().noneMatch(v -> v.contains(FIELD_WEBHOOK_URL))) {
			validateUrlSafety(transaction.getWebhookUrl(), FIELD_WEBHOOK_URL, violations);
		}
		if (violations.stream().noneMatch(v -> v.contains(FIELD_REDIRECT_URL))) {
			validateUrlSafety(transaction.getRedirectUrl(), FIELD_REDIRECT_URL, violations);
		}
		validateCustomer(transaction.getCustomer(), violations);

		if (!violations.isEmpty()) {
			throw new ValidationException(violations);
		}
	}

	private void validateAmount(Long amount, List<String> violations) {
		if (amount == null || amount <= 0) {
			violations.add("Field 'amount' must be a positive integer representing cents");
			return;
		}
		if (amount > maxAmountCents) {
			violations.add(String.format("Field 'amount' exceeds the maximum allowed value of %d cents. "
					+ "Contact support for high-value transaction limits.", maxAmountCents));
		}
	}

	private void validateCurrencyCode(String currencyCode, List<String> violations) {
		if (currencyCode == null || !VALID_CURRENCIES.contains(currencyCode.toUpperCase())) {
			violations.add("Field 'currency_code' must be a valid ISO 4217 code. Received: " + currencyCode);
		}
	}

	private void validateCountryCode(String countryCode, List<String> violations) {
		if (countryCode == null || !VALID_COUNTRIES.contains(countryCode.toUpperCase())) {
			violations.add("Field 'country_code' must be a valid ISO 3166-1 Alpha-2 code. Received: " + countryCode);
		}
	}

	private void validateUrlFormat(String url, String fieldName, List<String> violations) {
		if (url == null || !URL_PATTERN.matcher(url).matches()) {
			violations.add("Field '" + fieldName + "' must be a valid HTTP/HTTPS URL");
		}
	}

	private void validateUrlSafety(String url, String fieldName, List<String> violations) {
		String violation = ssrfGuard.validateSafety(url, fieldName);
		if (violation != null) {
			violations.add(violation);
		}
	}

	private void validateCustomer(Customer customer, List<String> violations) {
		if (customer == null) {
			violations.add("Field 'customer' is required");
			return;
		}

		String firstName = customer.getFirstName();
		if (firstName == null || firstName.isBlank()) {
			violations.add("Field 'customer.first_name' is required");
		}

		String lastName = customer.getLastName();
		if (lastName == null || lastName.isBlank()) {
			violations.add("Field 'customer.last_name' is required");
		}

		String email = customer.getEmail();
		if (email != null && !email.isBlank() && !EMAIL_PATTERN.matcher(email).matches()) {
			violations.add("Field 'customer.email' has an invalid format");
		}

		String callingCode  = customer.getCountryCallingCode();
		String phoneNumber  = customer.getPhoneNumber();
		boolean hasCallingCode  = callingCode  != null && !callingCode.isBlank();
		boolean hasPhoneNumber  = phoneNumber  != null && !phoneNumber.isBlank();

		if (hasCallingCode != hasPhoneNumber) {
			violations.add("Fields '" + FIELD_COUNTRY_CALLING_CODE + "' and '" + FIELD_PHONE_NUMBER
					+ "' must be provided together");
		} else {
			if (hasCallingCode && !CALLING_CODE_PATTERN.matcher(callingCode).matches()) {
				violations.add("Field '" + FIELD_COUNTRY_CALLING_CODE
						+ "' must be a valid calling code (e.g. +57, +1, +598)");
			}
			if (hasPhoneNumber && !PHONE_PATTERN.matcher(phoneNumber).matches()) {
				violations.add("Field '" + FIELD_PHONE_NUMBER + "' must contain only digits (7–15)");
			}
		}

		String documentType   = customer.getDocumentType();
		String documentNumber = customer.getDocumentNumber();
		boolean hasDocumentType   = documentType   != null && !documentType.isBlank();
		boolean hasDocumentNumber = documentNumber != null && !documentNumber.isBlank();

		if (hasDocumentType != hasDocumentNumber) {
			violations.add("Fields '" + FIELD_DOCUMENT_TYPE + "' and '" + FIELD_DOCUMENT_NUMBER
					+ "' must be provided together");
		} else if (hasDocumentType && DocumentType.fromCode(documentType).isEmpty()) {
			violations.add("Field '" + FIELD_DOCUMENT_TYPE + "' is not a recognized document type. "
					+ "Valid values: CC, CE, NIT, PP, TI, RUT, DNI, NUIP");
		}
	}
}
