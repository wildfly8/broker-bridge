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

import com.ib.client.Types;

public enum MarketValueTag {
    AccountOrGroup("AccountOrGroup"),
    RealCurrency("RealCurrency"),
    IssuerOptionValue("IssuerOption"),
    NetLiquidationByCurrency("Net Liq"),
    CashBalance("CashBalance"),
    Cryptocurrency("Cryptocurrency"),
    TotalCashBalance("TotalCashBalance"),
    AccruedCash("AccruedCash"),
    StockMarketValue("Stocks"),
    OptionMarketValue("Options"),
    FutureOptionValue("Futures"),
    FuturesPNL("FuturesPNL"),
    UnrealizedPnL("UnrealizedPnL"),
    RealizedPnL("RealizedPnL"),
    ExchangeRate("ExchangeRate"),
    FundValue("Fund"),
    NetDividend("NetDividend"),
    MutualFundValue("MutualFund"),
    MoneyMarketFundValue("MoneyMarketFund"),
    CorporateBondValue("CorporateBond"),
    TBondValue("TBond"),
    TBillValue("TBill"),
    WarrantValue("Warrant"),
    FxCashBalance("FxCashBalance");

    private final String description;

    MarketValueTag(final String description) {
        this.description = description;
    }

    public static MarketValueTag get(int i) {
        return Types.getEnum(i, values());
    }

    @Override
    public String toString() {
        return description;
    }
}
