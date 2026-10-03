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

public class Account {
    private String m_acct;
    private String m_amount;

    public String acct() { return m_acct; }
    public String amount() { return m_amount; }

    public void acct(String v) { m_acct = v; }
    public void amount(String v) { m_amount = v; }

    Account() { }

    Account(String acct, String amount) {
        m_acct = acct;
        m_amount = amount;
    }
    
    @Override public String toString() {
        return String.format("%s,%s", m_acct, m_amount != null ? m_amount : "");
    }
}
