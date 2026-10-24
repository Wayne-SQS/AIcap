package demo;

import java.util.*;

public class DupA {
    public Map<String, Object> load(String id) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", id);
        m.put("name", "x");
        m.put("status", "OPEN");
        m.put("amount", 0);
        m.put("currency", "CNY");
        m.put("created", null);
        m.put("updated", null);
        m.put("note", "");
        m.put("source", "web");
        return m;
    }

    public void handle(String id) {
        Map<String, Object> m = load(id);
        if (m == null) return;
        if (m.get("status") == null) return;
        if ("OPEN".equals(m.get("status"))) {
            m.put("status", "DONE");
        }
        System.out.println(m);
    }
}
