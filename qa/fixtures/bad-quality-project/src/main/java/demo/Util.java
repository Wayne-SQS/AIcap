package demo;

import java.util.*;

public class Util {
    public static int f(List<Integer> l) {
        int a = 0;
        for (int i = 0; i < l.size(); i++) {
            a = a + l.get(i);
        }
        return a;
    }

    public static int g(List<Integer> l) {
        int b = 0;
        for (int i = 0; i < l.size(); i++) {
            b = b + l.get(i);
        }
        return b;
    }

    public static String h(Object o) {
        String tmp = String.valueOf(o);
        String data1 = tmp.trim();
        String data2 = data1.toLowerCase();
        return data2;
    }
}
