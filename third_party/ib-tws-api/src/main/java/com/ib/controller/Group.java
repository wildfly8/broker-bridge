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

import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;
import java.util.stream.Collectors;

import com.ib.client.Types.Method;

public class Group {
    private String m_name;
    private Method m_defaultMethod;
    private List<Account> m_accounts = new ArrayList<Account>();
    private String m_defaultSize;
    private String m_riskCriteria;

    public String name()            { return m_name; }
    public Method defaultMethod()   { return m_defaultMethod; }
    public List<Account> accounts() { return m_accounts; }
    public String defaultSize()     { return m_defaultSize; }
    public String riskCriteria()    { return m_riskCriteria; }

    public void name( String v)              { m_name = v; }
    public void defaultMethod( Method v)     { m_defaultMethod = v; }
    public void addAccount( Account account) { m_accounts.add( account); }
    public void defaultSize(String v)        { m_defaultSize = v; }
    public void riskCriteria(String v)       { m_riskCriteria = v; }

    public String getAllAccounts() {
        return accounts().stream().map(Object::toString).collect(Collectors.joining(";"));
    }

    /** @param val is a string of accounts in format: acct1,amount1;acct2,amount2;... */
    public void setAllAccounts(String val) {
        m_accounts.clear();

        StringTokenizer st1 = new StringTokenizer(val, ";");
        while( st1.hasMoreTokens() ) {
            String account = st1.nextToken();
            StringTokenizer st2 = new StringTokenizer(account, ",");
            if (st2.hasMoreTokens()) {
                String acct = st2.nextToken();
                String amount = null;
                if (st2.hasMoreTokens()) {
                    amount = st2.nextToken();
                }
                m_accounts.add( new Account(acct, amount));
            }
        }
    }
}
