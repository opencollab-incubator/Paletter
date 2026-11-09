package fix.api;

public enum Mode {
    FAST,
    SLOW {
        @Override
        public int cost() {
            return 2;
        }
    };

    public int cost() {
        return 1;
    }
}
