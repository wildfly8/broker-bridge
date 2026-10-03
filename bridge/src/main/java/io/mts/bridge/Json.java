package io.mts.bridge;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Shared JSON mapper and log masking. */
public final class Json {

	public static final ObjectMapper MAPPER = new ObjectMapper()
			.setSerializationInclusion(JsonInclude.Include.NON_NULL)
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	/** IB account ids such as DU1234567 / U1234567 / DF123456. */
	private static final Pattern ACCOUNT_ID = Pattern.compile("\\b(D?[UF]\\d{5,})\\b");

	private Json() {}

	public static String write(Object value) {
		try {
			return MAPPER.writeValueAsString(value);
		} catch (Exception e) {
			throw new IllegalStateException("JSON write failed", e);
		}
	}

	/** DU1234567 → DU*****67. */
	public static String maskAccounts(String text) {
		if (text == null) {
			return null;
		}
		Matcher m = ACCOUNT_ID.matcher(text);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			String id = m.group(1);
			m.appendReplacement(sb, id.substring(0, 2) + "*".repeat(id.length() - 4) + id.substring(id.length() - 2));
		}
		m.appendTail(sb);
		return sb.toString();
	}
}
