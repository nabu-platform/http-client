package be.nabu.libs.http.client;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

import be.nabu.libs.authentication.api.principals.NTLMPrincipal;

public class NTLMPrincipalImpl implements NTLMPrincipal {

	private static final long serialVersionUID = 1L;
	
	private String hostName, password, name, domain;
	
	public NTLMPrincipalImpl(String domain, String username, String password) {
		this.domain = domain == null ? null : domain.toUpperCase(Locale.ENGLISH);
		this.name = username;
		this.password = password;
	}

	@Override
	public String getPassword() {
		return password;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public String getDomain() {
		return domain;
	}

	@Override
	public String getHostName() {
		try {
			if (hostName == null)
				hostName = InetAddress.getLocalHost().getHostName().toUpperCase(Locale.ENGLISH);
			return hostName;
		}
		catch(UnknownHostException e) {
			throw new RuntimeException(e);
		}
	}

}
