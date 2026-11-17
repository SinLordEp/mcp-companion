package demo;

public class Square implements Shape {
    double side = 1;

    public double area() {
        return side * side;
    }

    public double scale(double f) {
        return side * f;
    }

    public double scale(double f, double g) {
        return side * f * g;
    }
}
