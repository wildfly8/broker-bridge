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

public enum Instrument {
    STK("STK"),
    BOND("BOND"),
    EFP("EFP"),
    FUT_EU("FUT.EU"),
    FUT_HK("FUT.HK"),
    FUT_NA("FUT.NA"),
    FUT_US("FUT.US"),
    IND_EU("IND.EU"),
    IND_HK("IND.HK"),
    IND_US("IND.US"),
    PMONITOR("PMONITOR"),
    PMONITORM("PMONITORM"),
    SLB_US("SLB.US"),
    STOCK_EU("STOCK.EU"),
    STOCK_HK("STOCK.HK"),
    STOCK_NA("STOCK.NA"),
    WAR_EU("WAR.EU");

    private final String code;

    Instrument(final String code) {
        this.code = code;
    }

    @Override
    public String toString() {
        return code;
    }
}
