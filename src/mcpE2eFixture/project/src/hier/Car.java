package hier;

/**
 * mc_pull_up e2e: describe() pulls up cleanly; faster() uses the private field speed (a conflict unless speed moves
 * along); WHEELS is used from Java (garage.Garage), SEATS from Kotlin (garage/GarageReport.kt).
 */
public class Car extends Vehicle implements Named {
    public static final int WHEELS = 4;
    public static final int SEATS = 5;
    private int speed;

    public String describe() {
        return name + " with " + WHEELS + " wheels";
    }

    public int faster() {
        return speed + 10;
    }
}
