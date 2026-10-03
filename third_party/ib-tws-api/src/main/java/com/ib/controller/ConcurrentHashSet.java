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

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class ConcurrentHashSet<Key> extends AbstractSet<Key> {
    private static final Object OBJECT = new Object();

    private Map<Key, Object> m_map = new ConcurrentHashMap<>(16,0.75f,1); // use write concurrency level 1 (last param) to decrease memory consumption by ConcurrentHashMap

    /** return true if object was added as "first value" for this key */
    @Override
    public boolean add( Key key) {
        return m_map.put( key, OBJECT) == null; // null means there was no value for given key previously
    }

    @Override
    public boolean contains( Object key) {
        return m_map.containsKey( key);
    }

    @Override
    public Iterator<Key> iterator() {
        return m_map.keySet().iterator();
    }

    /** return true if key was indeed removed */
    @Override
    public boolean remove( Object key) {
        return m_map.remove( key) == OBJECT; // if value not null it was existing in the map
    }

    @Override
    public boolean isEmpty() {
        return m_map.isEmpty();
    }

    @Override
    public int size() {
        return m_map.size();
    }

    @Override
    public void clear() {
        m_map.clear();
    }
}
