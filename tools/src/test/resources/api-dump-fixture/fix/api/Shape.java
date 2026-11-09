package fix.api;

public sealed interface Shape permits Square, Circle {
    double area();

    String UNIT = "cm";
}
