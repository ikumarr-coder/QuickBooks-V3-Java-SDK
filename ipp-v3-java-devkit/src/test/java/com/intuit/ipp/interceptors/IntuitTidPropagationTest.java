package com.intuit.ipp.interceptors;

import java.util.ArrayList;
import java.util.List;

import org.apache.http.ProtocolVersion;
import org.apache.http.StatusLine;
import org.apache.http.message.BasicStatusLine;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.intuit.ipp.data.Error;
import com.intuit.ipp.data.Fault;
import com.intuit.ipp.data.IntuitResponse;
import com.intuit.ipp.exception.BadRequestException;
import com.intuit.ipp.exception.FMSException;
import com.intuit.ipp.exception.ValidationException;

/**
 * Validates that response {@code intuit_tid} is copied onto thrown exceptions.
 */
public class IntuitTidPropagationTest {

	private static final String RESPONSE_TID = "1-test-intuit-tid-abc";

	@Test
	public void copyIntuitTidTo_setsFieldOnException() {
		ResponseElements elements = new ResponseElements();
		elements.setIntuitTid(RESPONSE_TID);
		FMSException ex = new FMSException("test");
		elements.copyIntuitTidTo(ex);
		Assert.assertEquals(ex.getIntuit_tid(), RESPONSE_TID);
	}

	@Test
	public void copyIntuitTidTo_skipsWhenTidNull() {
		ResponseElements elements = new ResponseElements();
		FMSException ex = new FMSException("test");
		elements.copyIntuitTidTo(ex);
		Assert.assertNull(ex.getIntuit_tid());
	}

	@Test
	public void handleResponseInterceptor_validationFault_includesIntuitTid() throws Exception {
		IntuitMessage message = buildMessageWithValidationFault();
		message.getResponseElements().setIntuitTid(RESPONSE_TID);

		HandleResponseInterceptor interceptor = new HandleResponseInterceptor();
		try {
			interceptor.execute(message);
			Assert.fail("Expected ValidationException");
		} catch (ValidationException e) {
			Assert.assertEquals(e.getIntuit_tid(), RESPONSE_TID);
		}
	}

	@Test
	public void handleResponseInterceptor_http400_includesIntuitTid() throws Exception {
		IntuitMessage message = new IntuitMessage();
		ResponseElements elements = message.getResponseElements();
		elements.setIntuitTid(RESPONSE_TID);
		StatusLine statusLine = new BasicStatusLine(new ProtocolVersion("HTTP", 1, 1), 400, "Bad Request");
		elements.setStatusLine(statusLine);
		elements.setStatusCode(400);

		HandleResponseInterceptor interceptor = new HandleResponseInterceptor();
		try {
			interceptor.execute(message);
			Assert.fail("Expected BadRequestException");
		} catch (BadRequestException e) {
			Assert.assertEquals(e.getIntuit_tid(), RESPONSE_TID);
		}
	}

	private static IntuitMessage buildMessageWithValidationFault() {
		IntuitMessage message = new IntuitMessage();
		IntuitResponse response = new IntuitResponse();
		Fault fault = new Fault();
		fault.setType("Validation");
		List<Error> errors = new ArrayList<>();
		Error error = new Error();
		error.setCode("6240");
		error.setMessage("Duplicate Name Exists Error");
		errors.add(error);
		fault.setError(errors);
		response.setFault(fault);
		message.getResponseElements().setResponse(response);
		return message;
	}
}
