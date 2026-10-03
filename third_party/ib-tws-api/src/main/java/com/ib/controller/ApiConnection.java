/*
 * Java TWS API Client
 *
 * Copyright (C) 2013-2026  Interactive Brokers LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.ib.controller;


import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.ib.client.Contract;
import com.ib.client.EClientErrors;
import com.ib.client.EClientSocket;
import com.ib.client.EJavaSignal;
import com.ib.client.EMessage;
import com.ib.client.EWrapper;
import com.ib.client.Order;

// NOTE: TWS 936 SERVER_VERSION is 67.

public class ApiConnection extends EClientSocket {
	public interface ILogger {
		void log(String valueOf);
	}

	public static final char EOL = 0;
	public static final char LOG_EOL = '_';

	private final ILogger m_inLogger;
	private final ILogger m_outLogger;
	private static final EJavaSignal m_signal = new EJavaSignal();

	public ApiConnection(EWrapper wrapper, ILogger inLogger, ILogger outLogger) {
		super( wrapper, m_signal);
		m_inLogger = inLogger;
		m_outLogger = outLogger;
	}

	@Override
	protected void sendMsg(EMessage msg) throws IOException {
		// TODO Auto-generated method stub
		super.sendMsg(msg);
		
		byte[] buf = msg.getRawData();

		if (m_outLogger != null) {
			m_outLogger.log(new String(buf, 0, buf.length, StandardCharsets.UTF_8));
		}
	}

	@Override
	public int readInt() throws IOException {
		int c = super.readInt();

		if (m_inLogger != null) {
			m_inLogger.log( String.valueOf( (char)c) );
		}

		return c;
	}

	@Override
	public int read(byte[] buf, int off, int len) throws IOException {
		int n = super.read(buf, off, len);

		if (m_inLogger != null) {
			m_inLogger.log(new String(buf, 0, n, StandardCharsets.UTF_8));
		}

		return n;
	}

	public synchronized void placeOrder(Contract contract, Order order) {
		// not connected?
		if( !isConnected() ) {
            notConnected();
			return;
		}

		// ApiController requires TWS 932 or higher; this limitation could be removed if needed
		if (serverVersion() < 66) {
            error( EClientErrors.NO_VALID_ID, EClientErrors.UPDATE_TWS, "ApiController requires TWS build 932 or higher to place orders.");
            return;
		}

	    placeOrder(order.orderId(), contract, order);
	} 
}
