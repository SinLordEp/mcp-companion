package demo;

public class Circle implements Shape {
    private double radius;

    public Circle(double r) {
        radius = r;
    }

    public double area() {
        return 3 * radius * radius;
    }

    public void grow() {
        radius++;
    }
}
