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

import java.text.SimpleDateFormat;
import java.util.Date;

import com.ib.client.Decimal;

public class Bar {
	private static final ThreadLocal<SimpleDateFormat> FORMAT_CACHE = ThreadLocal.withInitial(() -> new SimpleDateFormat( "yyyyMMdd HH:mm:ss"));

	private final long m_time;
	private final String m_timeStr;
	private final double m_high;
	private final double m_low;
	private final double m_open;
	private final double m_close;
	private final Decimal m_wap;
	private final Decimal m_volume;
	private final int m_count;

	public long time()		{ return m_time; }
	public String timeStr() { return m_timeStr; }
	public double high() 	{ return m_high; }
	public double low() 	{ return m_low; }
	public double open() 	{ return m_open; }
	public double close() 	{ return m_close; }
	public Decimal wap() 	{ return m_wap; }
	public Decimal volume() { return m_volume; }
	public int count() 		{ return m_count; }

	public Bar( long time, double high, double low, double open, double close, Decimal wap, Decimal volume, int count) {
		m_time = time;
		m_timeStr = null;
		m_high = high;
		m_low = low;
		m_open = open;
		m_close = close;
		m_wap = wap;
		m_volume = volume;
		m_count = count;
	}

	public Bar( String timeStr, double high, double low, double open, double close, Decimal wap, Decimal volume, int count) {
		m_time = Long.MAX_VALUE;
		m_timeStr = timeStr;
		m_high = high;
		m_low = low;
		m_open = open;
		m_close = close;
		m_wap = wap;
		m_volume = volume;
		m_count = count;
	}
	
	public String formattedTime() {
		return Formats.fmtDate( m_time * 1000);
	}

	/** Format for query. */
	public static String format( long ms) {
		return FORMAT_CACHE.get().format( new Date( ms) );
	}

	@Override public String toString() {
		return String.format( "%s %s %s %s %s", m_timeStr != null ? m_timeStr : formattedTime(), m_open, m_high, m_low, m_close);
	}
}
