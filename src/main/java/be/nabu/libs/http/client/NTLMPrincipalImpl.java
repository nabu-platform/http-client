/*
* Copyright (C) 2015 Alexander Verbruggen
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Lesser General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public License
* along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

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
