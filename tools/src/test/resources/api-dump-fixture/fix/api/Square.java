package fix.api;

public record Square(double side, java.util.List<String> labels) implements Shape {
    public Square {
    }

    @Override
    public double area() {
        return side * side;
    }

    public static Square unit() {
        return new Square(1, java.util.List.of());
    }
}
