package com.intuit.ipp.interceptors;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.apache.http.HttpResponse;
import org.apache.http.ProtocolVersion;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.message.BasicStatusLine;
import org.testng.Assert;
import org.testng.Reporter;
import org.testng.annotations.Test;

import com.intuit.ipp.exception.FMSException;
import com.intuit.ipp.exception.ValidationException;

/**
 * End-to-end style test: simulated HTTP request/response through interceptors,
 * verifying response {@code intuit_tid} reaches {@link FMSException#getIntuit_tid()}.
 */
public class IntuitTidHttpRoundTripTest {

	private static final String REQUEST_URI =
			"https://sandbox-quickbooks.api.intuit.com/v3/company/9341454996143948/customer";
	private static final String REQUEST_TID = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
	private static final String RESPONSE_TID = "1-67890abcdef-1234567890abcdef";

	// Same shape as QBO JSON faults (see JSONSerializerTest.testDeserialize_Fault)
	private static final String RESPONSE_BODY =
			"{\"time\":\"2026-05-27T18:30:00.000-07:00\",\"Fault\":{\"Error\":[{\"Message\":\"Duplicate Name Exists Error\","
			+ "\"Detail\":\"The name supplied already exists. : Id=123\",\"code\":\"6240\",\"element\":\"DisplayName\"}],"
			+ "\"type\":\"Validation\"}}";

	@Test
	public void simulatedHttpRoundTrip_responseIntuitTidOnException() throws Exception {
		log("=== Simulated HTTP REQUEST (SDK outbound) ===");
		log("POST " + REQUEST_URI);
		log("intuit_tid (request header, client tracking): " + REQUEST_TID);
		log("Content-Type: application/json");
		log("Body: { DisplayName: \"Duplicate Customer Name\", ... }");
		log("");

		IntuitMessage message = new IntuitMessage();
		Map<String, String> requestHeaders = message.getRequestElements().getRequestHeaders();
		requestHeaders.put(RequestElements.HEADER_INTUIT_TID, REQUEST_TID);
		requestHeaders.put(RequestElements.HEADER_PARAM_CONTENT_TYPE, "application/json");
		requestHeaders.put(RequestElements.HEADER_PARAM_ACCEPT, "application/json");

		HttpResponse httpResponse = buildSimulatedErrorResponse();
		log("=== Simulated HTTP RESPONSE (QBO inbound) ===");
		log("HTTP/1.1 400 Bad Request");
		log("Content-Type: application/json");
		log("intuit_tid (response header, server): " + RESPONSE_TID);
		log("Body: " + RESPONSE_BODY.replace("\n", " "));
		log("");

		// Step 1: HTTPClientConnectionInterceptor — read headers + body (patched)
		HTTPClientConnectionInterceptor connectionInterceptor = new HTTPClientConnectionInterceptor();
		invokeSetResponseElements(connectionInterceptor, message, httpResponse);

		ResponseElements afterConnection = message.getResponseElements();
		Assert.assertEquals(afterConnection.getIntuitTid(), RESPONSE_TID,
				"Connection interceptor must store response intuit_tid on ResponseElements");
		log("After HTTPClientConnectionInterceptor:");
		log("  ResponseElements.intuitTid = " + afterConnection.getIntuitTid());
		log("");

		// Step 2: DecompressionInterceptor
		new DecompressionInterceptor().execute(message);
		log("After DecompressionInterceptor:");
		log("  decompressed body length = "
				+ (afterConnection.getDecompressedData() != null ? afterConnection.getDecompressedData().length() : 0));
		log("");

		// Step 3: DeserializeInterceptor
		new DeserializeInterceptor().execute(message);
		log("After DeserializeInterceptor:");
		log("  parsed IntuitResponse fault type = "
				+ (((com.intuit.ipp.data.IntuitResponse) afterConnection.getResponse()).getFault() != null
						? ((com.intuit.ipp.data.IntuitResponse) afterConnection.getResponse()).getFault().getType()
						: "none"));
		log("");

		// Step 4: HandleResponseInterceptor — throw with intuit_tid (patched)
		FMSException caught = null;
		try {
			new HandleResponseInterceptor().execute(message);
			Assert.fail("Expected FMSException for Validation fault body");
		} catch (ValidationException e) {
			caught = e;
		}

		log("=== SDK exception surfaced to application ===");
		log("Exception type: " + (caught != null ? caught.getClass().getSimpleName() : "null"));
		log("FMSException.getIntuit_tid(): " + caught.getIntuit_tid());
		log("Request header intuit_tid (not copied to exception): " + REQUEST_TID);
		log("");

		Assert.assertNotNull(caught);
		Assert.assertEquals(caught.getIntuit_tid(), RESPONSE_TID,
				"Exception must carry server response intuit_tid, not null");
		Assert.assertNotEquals(caught.getIntuit_tid(), REQUEST_TID,
				"Exception must use response TID, not client request TID");
		Assert.assertTrue(caught.getMessage().contains("6240") || caught.getMessage().contains("Duplicate"),
				"Exception should contain fault details");

		log("PASS: response intuit_tid propagated to FMSException");
	}

	@Test
	public void httpClientConnectionInterceptor_readsResponseHeaderOnly() throws Exception {
		IntuitMessage message = new IntuitMessage();
		HttpResponse httpResponse = buildSimulatedErrorResponse();

		invokeSetResponseElements(new HTTPClientConnectionInterceptor(), message, httpResponse);

		Assert.assertEquals(message.getResponseElements().getIntuitTid(), RESPONSE_TID);
		Assert.assertEquals(message.getResponseElements().getStatusCode(), 400);
		Assert.assertNotNull(message.getResponseElements().getResponseContent());
	}

	private static HttpResponse buildSimulatedErrorResponse() throws Exception {
		BasicStatusLine statusLine = new BasicStatusLine(new ProtocolVersion("HTTP", 1, 1), 400, "Bad Request");
		BasicHttpResponse response = new BasicHttpResponse(statusLine);
		response.addHeader(new BasicHeader(RequestElements.HEADER_INTUIT_TID, RESPONSE_TID));
		response.addHeader(new BasicHeader("Content-Type", "application/json"));
		response.setEntity(new StringEntity(RESPONSE_BODY, StandardCharsets.UTF_8));
		return response;
	}

	private static void invokeSetResponseElements(
			HTTPClientConnectionInterceptor interceptor,
			IntuitMessage message,
			HttpResponse httpResponse) throws Exception {
		Method method = HTTPClientConnectionInterceptor.class.getDeclaredMethod(
				"setResponseElements", IntuitMessage.class, HttpResponse.class);
		method.setAccessible(true);
		method.invoke(interceptor, message, httpResponse);
	}

	private static void log(String line) {
		Reporter.log(line, true);
		System.out.println(line);
	}
}
