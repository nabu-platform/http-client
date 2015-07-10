package be.nabu.libs.http.client;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.Principal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.libs.http.api.BasicPrincipal;
import be.nabu.libs.http.api.client.AuthenticationHandler;
import be.nabu.utils.codec.TranscoderUtils;
import be.nabu.utils.codec.impl.Base64Encoder;
import be.nabu.utils.io.IOUtils;

/**
 * TODO: which encoding to use for basic authentication?
 */
public class BasicAuthentication implements AuthenticationHandler {

	private Logger logger = LoggerFactory.getLogger(getClass());
	
	@Override
	public String authenticate(Principal principal, String challenge) {
		if (challenge == null || !challenge.trim().toLowerCase().startsWith("basic"))
			return null;
		if (!(principal instanceof BasicPrincipal))
			throw new SecurityException("The authentication is basic but the principal does is not of type BasicPrincipal");
		try {
			byte [] base64 = IOUtils.toBytes(TranscoderUtils.transcodeBytes(
				IOUtils.wrap((principal.getName() + ":" + ((BasicPrincipal) principal).getPassword()).getBytes("UTF-8"), true), 
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
