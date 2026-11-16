package demo;

import sib.Triangle;

public class Main {
    public static double total() {
        Shape c = new Circle(2);
        Square s = new Square();
        Shape t = new Triangle();
        return c.area() + s.scale(2) + t.area();
    }
}
