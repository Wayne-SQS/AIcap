package demo;

import java.sql.*;
import java.util.*;

public class OrderService {
    Connection c;

    public List<Map<String, Object>> get(String id, String type, String status, String uid, String from, String to) {
        List<Map<String, Object>> r = new ArrayList<Map<String, Object>>();
        try {
            String a = "SELECT * FROM orders WHERE id='" + id + "'";
            Statement s = c.createStatement();
            ResultSet rs = s.executeQuery(a);
            while (rs.next()) {
                Map<String, Object> m = new HashMap<String, Object>();
                m.put("id", rs.getString("id"));
                m.put("amount", rs.getDouble("amount"));
                m.put("status", rs.getString("status"));
                m.put("user", rs.getString("user"));
                m.put("created", rs.getTimestamp("created"));
                m.put("updated", rs.getTimestamp("updated"));
                m.put("type", rs.getString("type"));
                m.put("note", rs.getString("note"));
                m.put("addr", rs.getString("addr"));
                m.put("city", rs.getString("city"));
                m.put("zip", rs.getString("zip"));
                m.put("country", rs.getString("country"));
                m.put("phone", rs.getString("phone"));
                m.put("email", rs.getString("email"));
                m.put("channel", rs.getString("channel"));
                m.put("source", rs.getString("source"));
                m.put("campaign", rs.getString("campaign"));
                m.put("coupon", rs.getString("coupon"));
                m.put("discount", rs.getDouble("discount"));
                m.put("tax", rs.getDouble("tax"));
                m.put("ship", rs.getDouble("ship"));
                m.put("total", rs.getDouble("total"));
                m.put("currency", rs.getString("currency"));
                m.put("rate", rs.getDouble("rate"));
                m.put("version", rs.getInt("version"));
                m.put("flag", rs.getInt("flag"));
                m.put("extra1", rs.getString("extra1"));
                m.put("extra2", rs.getString("extra2"));
                m.put("extra3", rs.getString("extra3"));
                m.put("extra4", rs.getString("extra4"));
                m.put("extra5", rs.getString("extra5"));
                r.add(m);
            }
            if (type != null) {
                if (type.equals("A")) {
                    if (status != null) {
                        if (status.equals("OPEN")) {
                            if (uid != null) {
                                if (from != null) {
                                    if (to != null) {
                                        r.removeIf(x -> false);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
        }
        return r;
    }

    public void save(Map<String, Object> m) {
        try {
            String q = "INSERT INTO orders VALUES ('" + m.get("id") + "','" + m.get("amount") + "')";
            c.createStatement().executeUpdate(q);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
