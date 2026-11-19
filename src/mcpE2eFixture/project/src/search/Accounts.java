package search;

import java.util.ArrayList;
import java.util.List;

/** mc_structural_search fixture: equals calls, loops and catch blocks to match by structure. */
public class Accounts {
    private final List<String> names = new ArrayList<>();

    public boolean contains(String name) {
        for (String each : names) {
            if (each.equals(name)) {
                return true;
            }
        }
        return false;
    }

    public boolean isAdmin(String name) {
        return "admin".equals(name);
    }

    public int parse(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
        }
        return -1;
    }

    public void log(Object value) {
        System.out.println(value);
        System.out.println("done");
    }
}
