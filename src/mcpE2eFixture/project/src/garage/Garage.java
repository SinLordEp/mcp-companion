package garage;

import hier.Car;

/** mc_pull_up e2e: Java code that uses Car's members. */
public class Garage {
    public String park(Car car) {
        return car.describe() + " in slot " + Car.WHEELS;
    }
}
