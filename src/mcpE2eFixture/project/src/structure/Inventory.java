package structure;

import java.util.ArrayList;
import java.util.List;

/** A class with nested types and members of every kind, for mc_file_structure. */
public class Inventory {
    public static final int MAX_ITEMS = 100;
    private final List<Item> items = new ArrayList<>();
    protected String owner;

    public Inventory(String owner) {
        this.owner = owner;
    }

    public void add(Item item) {
        if (items.size() < MAX_ITEMS) {
            items.add(item);
        }
    }

    public int count() {
        return items.size();
    }

    private static int weight(List<Item> list) {
        int total = 0;
        for (Item item : list) {
            total += item.weight();
        }
        return total;
    }

    /** An entry of the inventory. */
    public record Item(String name, int weight) {
    }

    /** Where an item is kept. */
    public enum Place {
        SHELF,
        BOX;

        boolean movable() {
            return this == BOX;
        }
    }

    interface Listener {
        void changed(Inventory inventory);
    }

    abstract static class Report {
        abstract String render();
    }
}
