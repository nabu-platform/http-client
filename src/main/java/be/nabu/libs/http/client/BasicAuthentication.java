package be.nabu.libs.http.client;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.Principal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.libs.authentication.api.principals.BasicPrincipal;
import be.nabu.libs.http.api.client.ClientAuthenticationHandler;
import be.nabu.utils.codec.TranscoderUtils;
import be.nabu.utils.codec.impl.Base64Encoder;
import be.nabu.utils.io.IOUtils;

/**
 * TODO: which encoding to use for basic authentication?
 */
public class BasicAuthentication implements ClientAuthenticationHandler {

	private Logger logger = LoggerFactory.getLogger(getClass());
	
	@Override
	public String authenticate(Principal principal, String challenge) {
		if (challenge == null || !challenge.trim().toLowerCase().startsWith("basic")) {
			return null;
		}
		// if the principal is missing we want a clean 401 from the server instead of an error here
		else if (principal == null) {
			logger.debug("Basic authentication is requested but no principal is present");
			return null;
		}
		// if the principal is wrong, we return null because it is possible that there are multiple authentication mechanisms
		else if (!(principal instanceof BasicPrincipal)) {
			logger.debug("Basic authentication is requested but the principal that is given is not of the type 'BasicPrincipal'");
			return null;
		}
		try {
			String password = ((BasicPrincipal) principal).getPassword();
			byte [] base64 = IOUtils.toBytes(TranscoderUtils.transcodeBytes(
				IOUtils.wrap((principal.getName() + ":" + (password == null ? "" : password)).getBytes("UTF-8"), true), 
				new Base64Encoder())
			);
			return "Basic " + new String(base64, "ASCII");
		}
		catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
		catch (IOException e) {
			logger.error("This should not happen", e);
		}
		return null;
	}

}
