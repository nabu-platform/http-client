package be.nabu.libs.http.client;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import be.nabu.libs.http.api.client.AuthenticationHandler;

/**
 * Supports basic & digest
 */
public class SPIAuthenticationHandler implements AuthenticationHandler {

	private List<AuthenticationHandler> handlers;

	private List<AuthenticationHandler> getHandlers() {
		if (handlers == null) {
			synchronized(this) {
				if (handlers == null) {
					List<AuthenticationHandler> handlers = new ArrayList<AuthenticationHandler>();
					for (AuthenticationHandler handler : ServiceLoader.load(AuthenticationHandler.class)) {
						handlers.add(handler);
					}
					this.handlers = handlers;
				}
			}
		}
		return handlers;
	}

	@Override
	public String authenticate(Principal principal, String challenge) {
		for (AuthenticationHandler handler : getHandlers()) {
			String response = handler.authenticate(principal, challenge);
			if (response != null)
				return response;
		}
		return null;
	}
}
