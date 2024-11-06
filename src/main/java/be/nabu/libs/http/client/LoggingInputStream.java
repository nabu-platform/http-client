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

import java.io.IOException;
import java.io.InputStream;

public class LoggingInputStream extends InputStream {

	private InputStream input;

	public LoggingInputStream(InputStream input) {
		this.input = input;
	}
	
	@Override
	public int read() throws IOException {
		int read = input.read();
		System.out.print((char) read);
		return read;
	}

	@Override
	public int read(byte[] b) throws IOException {
		int read = input.read(b);
		System.out.print(new String(b));
		return read;
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		int read = input.read(b, off, len);
		System.out.print(new String(b, off, len));
		return read;
	}

	@Override
	public long skip(long n) throws IOException {
		return input.skip(n);
	}

	@Override
	public int available() throws IOException {
		return input.available();
	}

	@Override
	public void close() throws IOException {
		input.close();
	}

	@Override
	public synchronized void mark(int readlimit) {
		input.mark(readlimit);
	}

	@Override
	public synchronized void reset() throws IOException {
		input.reset();
	}

	@Override
	public boolean markSupported() {
		return input.markSupported();
	}

}
