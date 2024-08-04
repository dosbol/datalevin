package datalevin.utl;

import java.util.*;

public class LRUCache {
    int capacity;
    Map<Object, Object> map;

    public LRUCache(int capacity) {
        this.capacity = capacity;
        map = Collections.synchronizedMap(new LinkedHashMap<Object, Object>(capacity,
                                                                            0.75f,
                                                                            true) {
                protected boolean	removeEldestEntry(Map.Entry<Object, Object> oldest) {
                    return size() > capacity;
                }
            });
    }

    public Object get(Object key) {
        return map.get(key);
    }

    public void put(Object key, Object value) {
        map.put(key, value);
    }

    public void clear() {
        map.clear();
    }
}
