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

import com.ib.client.Contract;
import com.ib.client.Decimal;


public class Position {
	private Contract m_contract;
	private String m_account;
	private Decimal m_position;
	private double m_marketPrice;
	private double m_marketValue;
	private double m_averageCost;
	private double m_unrealPnl;
	private double m_realPnl;

	public Contract contract()      { return m_contract; }
	public int conid()				{ return m_contract.conid(); }
	public double averageCost() 	{ return m_averageCost;}
	public double marketPrice() 	{ return m_marketPrice;}
	public double marketValue() 	{ return m_marketValue;}
	public double realPnl() 		{ return m_realPnl;}
	public double unrealPnl() 		{ return m_unrealPnl;}
	public Decimal position() 		{ return m_position;}
	public String account() 		{ return m_account;}

	public Position( Contract contract, String account, Decimal position, double marketPrice, double marketValue, double averageCost, double unrealPnl, double realPnl) {
		m_contract = contract;
		m_account = account;
		m_position = position;
		m_marketPrice = marketPrice;
		m_marketValue =marketValue;
		m_averageCost = averageCost;
		m_unrealPnl = unrealPnl;
		m_realPnl = realPnl;
	}
}
