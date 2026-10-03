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

import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.Format;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

public class Formats {
	private static final Format FMT8 = new DecimalFormat( "#,##0.00000000");
	private static final Format FMT6 = new DecimalFormat( "#,##0.000000");
	private static final Format FMT2 = new DecimalFormat( "#,##0.00");
	private static final Format FMT0 = new DecimalFormat( "#,##0");
	private static final Format PCT = new DecimalFormat( "0.0%");
	private static final ThreadLocal<DateFormat> GMT_DATE_TIME_FORMAT_CACHE = ThreadLocal.withInitial(() -> {
		final DateFormat format = new SimpleDateFormat( "yyyyMMdd-HH:mm:ss");
		format.setTimeZone(TimeZone.getTimeZone("GMT"));
		return format;
	});
    private static final ThreadLocal<DateFormat> DATE_TIME_FORMAT_CACHE = ThreadLocal.withInitial(() -> new SimpleDateFormat( "yyyyMMdd-HH:mm:ss"));
	private static final ThreadLocal<DateFormat> TIME_FORMAT_CACHE = ThreadLocal.withInitial(() -> new SimpleDateFormat( "HH:mm:ss"));

	/** Format with 6 decimals. */
	public static String fmt6( double v) {
		return v == Double.MAX_VALUE ? null : FMT6.format( v);
	}
	
	/** Format with 8 decimals. */
	public static String fmt8( double v) {
		return v == Double.MAX_VALUE ? null : FMT8.format( v);
	}
	
	/** Format with two decimals. */
	public static String fmt( double v) {
		return v == Double.MAX_VALUE ? null : FMT2.format( v);
	}

	/** Format with two decimals; return null for zero. */
	public static String fmtNz( double v) {
		return v == Double.MAX_VALUE || v == 0 ? null : FMT2.format( v);
	}

	/** Format with no decimals. */
	public static String fmt0( double v) {
		return v == Double.MAX_VALUE ? null : FMT0.format( v);
	}

	/** Format as percent with one decimal. */
	public static String fmtPct( double v) {
		return v == Double.MAX_VALUE ? null : PCT.format( v);
	}

	/** Format date/time for display. */
	public static String fmtDate( long ms) {
		return DATE_TIME_FORMAT_CACHE.get().format( new Date( ms) );
	}

	public static String fmtDateGmt(long ms) {
		return GMT_DATE_TIME_FORMAT_CACHE.get().format( new Date( ms) );
	}

	/** Format time for display. */
	public static String fmtTime( long ms) {
		return TIME_FORMAT_CACHE.get().format( new Date( ms) );
	}
}
