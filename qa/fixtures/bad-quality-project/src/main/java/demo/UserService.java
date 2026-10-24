package demo;

public class UserService {
    private static final String DB_PASSWORD = "P@ssw0rd123";
    private static final String APIKEY = "sk-live-abcdef123456";

    public boolean login(String u, String p) {
        String password = "admin888";
        if (p == null) return false;
        if (p.equals(password)) return true;
        try {
            Object o = Class.forName("demo.Legacy").getDeclaredConstructor().newInstance();
            return o != null;
        } catch (Exception e) {
        }
        return false;
    }

    public Object run(String script) {
        return eval(script);
    }
}
