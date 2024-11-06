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
import java.io.OutputStream;

public class LoggingOutputStream extends OutputStream {

	private OutputStream target;

	public LoggingOutputStream(OutputStream target) {
		this.target = target;
	}
	
	@Override
	public void write(int b) throws IOException {
		System.out.print((char) b);
		target.write(b);
	}

	@Override
	public void write(byte[] b) throws IOException {
		System.out.print(new String(b));
		target.write(b);
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		System.out.print(new String(b, off, len));
		target.write(b, off, len);
	}

	@Override
	public void flush() throws IOException {
		target.flush();
	}

	@Override
	public void close() throws IOException {
		target.close();
	}

}
