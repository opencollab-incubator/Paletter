package org.geysermc.paletter.internal.block;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NbtMap;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates the Molang conditions of custom block permutations against one block state. Supports what permutation
 * conditions use in practice: {@code q.block_state('name')} (also {@code query.}), numbers, quoted strings,
 * {@code true}/{@code false}, {@code ! == != < > <= >= && ||} and parentheses. Anything else does not match.
 */
public final class StateConditions {
    private StateConditions() {
    }

    /**
     * @param states the {@code states} compound of a block state
     * @return true if the condition holds; false if it does not, or uses Molang this does not understand
     */
    public static boolean matches(@NonNull String condition, @NonNull NbtMap states) {
        try {
            Parser parser = new Parser(tokenize(condition), states);
            Object result = parser.or();
            return parser.atEnd() && truthy(result);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean truthy(Object value) {
        return value instanceof Double number && number != 0;
    }

    private static List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '\'') {
                int end = input.indexOf('\'', i + 1);
                if (end == -1) {
                    throw new IllegalArgumentException("unterminated string");
                }
                tokens.add(input.substring(i, end + 1));
                i = end + 1;
            } else if (Character.isLetterOrDigit(c) || c == '_' || c == '.') {
                int end = i;
                while (end < input.length() && (Character.isLetterOrDigit(input.charAt(end)) || input.charAt(end) == '_' || input.charAt(end) == '.')) {
                    end++;
                }
                tokens.add(input.substring(i, end));
                i = end;
            } else {
                String two = i + 1 < input.length() ? input.substring(i, i + 2) : "";
                if (two.equals("&&") || two.equals("||") || two.equals("==") || two.equals("!=") || two.equals("<=") || two.equals(">=")) {
                    tokens.add(two);
                    i += 2;
                } else if ("()!<>,".indexOf(c) != -1) {
                    tokens.add(String.valueOf(c));
                    i++;
                } else {
                    throw new IllegalArgumentException("unexpected " + c);
                }
            }
        }
        return tokens;
    }

    private static final class Parser {
        private final List<String> tokens;
        private final NbtMap states;
        private int position;

        private Parser(List<String> tokens, NbtMap states) {
            this.tokens = tokens;
            this.states = states;
        }

        boolean atEnd() {
            return position == tokens.size();
        }

        private boolean accept(String token) {
            if (position < tokens.size() && tokens.get(position).equals(token)) {
                position++;
                return true;
            }
            return false;
        }

        Object or() {
            Object left = and();
            while (accept("||")) {
                Object right = and();
                left = bool(truthy(left) || truthy(right));
            }
            return left;
        }

        private Object and() {
            Object left = equality();
            while (accept("&&")) {
                Object right = equality();
                left = bool(truthy(left) && truthy(right));
            }
            return left;
        }

        private Object equality() {
            Object left = relational();
            while (true) {
                if (accept("==")) {
                    left = bool(left.equals(relational()));
                } else if (accept("!=")) {
                    left = bool(!left.equals(relational()));
                } else {
                    return left;
                }
            }
        }

        private Object relational() {
            Object left = unary();
            while (true) {
                String operator = null;
                for (String candidate : new String[] {"<=", ">=", "<", ">"}) {
                    if (accept(candidate)) {
                        operator = candidate;
                        break;
                    }
                }
                if (operator == null) {
                    return left;
                }
                double a = (Double) left;
                double b = (Double) unary();
                left = bool(switch (operator) {
                    case "<=" -> a <= b;
                    case ">=" -> a >= b;
                    case "<" -> a < b;
                    default -> a > b;
                });
            }
        }

        private Object unary() {
            if (accept("!")) {
                return bool(!truthy(unary()));
            }
            return primary();
        }

        private Object primary() {
            if (position >= tokens.size()) {
                throw new IllegalArgumentException("unexpected end");
            }
            String token = tokens.get(position++);
            if (token.equals("(")) {
                Object inner = or();
                if (!accept(")")) {
                    throw new IllegalArgumentException("missing )");
                }
                return inner;
            }
            if (token.startsWith("'")) {
                return token.substring(1, token.length() - 1);
            }
            if (token.equals("true") || token.equals("false")) {
                return bool(token.equals("true"));
            }
            String lower = token.toLowerCase(java.util.Locale.ROOT);
            if (lower.equals("q.block_state") || lower.equals("query.block_state")) {
                if (!accept("(")) {
                    throw new IllegalArgumentException("missing (");
                }
                String name = tokens.get(position++);
                if (!name.startsWith("'") || !accept(")")) {
                    throw new IllegalArgumentException("bad block_state call");
                }
                Object value = states.get(name.substring(1, name.length() - 1));
                if (value instanceof Number number) {
                    return number.doubleValue();
                }
                if (value instanceof String string) {
                    return string;
                }
                throw new IllegalArgumentException("no such state");
            }
            return Double.valueOf(token);
        }

        private static Double bool(boolean value) {
            return value ? 1.0 : 0.0;
        }
    }
}
