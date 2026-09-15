package com.example.duoplus_probe.sim;

import java.util.ArrayList;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;
import java.util.Set;

/** Small immutable DTO helpers. Never retains callers' mutable collections. */
final class Values {
    private Values() {}

    /** Only these private types may bypass freezing; foreign unmodifiable views can still alias a mutable source. */
    private static final class FrozenMap extends AbstractMap<String, Object> {
        private final Map<String, Object> entries;
        FrozenMap(Map<String, Object> ownedEntries) {
            entries=Collections.unmodifiableMap(ownedEntries);
        }
        @Override public Set<Map.Entry<String,Object>> entrySet(){return entries.entrySet();}
        @Override public Object get(Object key){return entries.get(key);}
        @Override public boolean containsKey(Object key){return entries.containsKey(key);}
        @Override public int size(){return entries.size();}
    }

    private static final class FrozenList<E> extends AbstractList<E> implements RandomAccess {
        private final Object[] items;
        FrozenList(List<? extends E> ownedItems){items=ownedItems.toArray();}
        @SuppressWarnings("unchecked") @Override public E get(int index){return (E)items[index];}
        @Override public int size(){return items.length;}
    }

    static Map<String, Object> map(Object... pairs) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], freeze(pairs[i + 1]));
        return new FrozenMap(result);
    }

    @SuppressWarnings("unchecked")
    static Object freeze(Object value) {
        if(value instanceof FrozenMap || value instanceof FrozenList)return value;
        if (value instanceof Map) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet())
                copy.put((String) entry.getKey(), freeze(entry.getValue()));
            return new FrozenMap(copy);
        }
        if (value instanceof List) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (List<?>) value) copy.add(freeze(item));
            return new FrozenList<>(copy);
        }
        return value;
    }

    static List<Double> vector(double... values) {
        List<Double> result = new ArrayList<>();
        for (double value : values) result.add(value);
        return new FrozenList<>(result);
    }
}
