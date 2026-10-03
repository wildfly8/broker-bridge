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

public class TradeId {
	private String m_key;
	private String m_full;

	public String key() 		{ return m_key; }
	public String full() 		{ return m_full; }

	public TradeId( String id) {
		m_full = id;
		int i = id.lastIndexOf( '.');
		m_key = id.substring( i + 1);
	}
}
