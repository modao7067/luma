package io.github.modao7067.luma.gui.ui;

import java.util.Set;

public final class CodeTokens {
    public static final int STRING = 1, COMMENT = 2, NUMBER = 3, KEYWORD = 4,
            ADDED = 5, REMOVED = 6, HUNK = 7, JSON_KEY = 8,
            COMMAND = 9, FLAG = 10, VARIABLE = 11, OPERATOR = 12;
    public static final int DISPLAY_SPAN_LIMIT = 1024;
    private static final int MAX_JSON_DEPTH = 256;
    private static final Set<String> KEYWORDS = Set.of("fun", "val", "var", "class", "return",
            "if", "else", "for", "while", "import", "package", "public", "private", "static",
            "void", "def", "from", "const", "let", "function", "async", "await", "true",
            "false", "null", "None", "try", "catch", "throw", "interface", "data", "suspend");
    private static final Set<String> SHELL_OPEN_KEYWORDS = Set.of(
            "if", "then", "else", "elif", "do", "while", "until", "!", "{", "}", "fi", "done", "esac");
    private static final Set<String> SHELL_WORDLIST_KEYWORDS = Set.of("for", "select", "case");
    private static final Set<String> SHELL_PRECOMMANDS = Set.of(
            "sudo", "env", "nohup", "command", "builtin", "exec", "time", "nice", "xargs");
    private static final Set<String> CONFIG_KEYWORDS = Set.of(
            "true", "false", "null", "yes", "no", "on", "off", "True", "False", "None");

    private CodeTokens() {}
    public interface Sink { void token(int start, int end, int kind); }

    private static final class JsonCursor {
        final String text;
        final Runnable checkpoint;
        final Sink sink;
        final boolean extensions;
        final int limit;
        int at;
        int count;

        JsonCursor(String text, boolean extensions, int limit, Sink sink, Runnable checkpoint) {
            this.text = text;
            this.extensions = extensions;
            this.limit = limit;
            this.sink = sink;
            this.checkpoint = checkpoint;
        }

        boolean has() { return at < text.length(); }
        char get() { return text.charAt(at); }
        void step() { if ((++at & 4095) == 0) checkpoint.run(); }
        void emit(int start, int kind) {
            if (count < limit && at > start) {
                sink.token(start, at, kind);
                count++;
            }
        }

        boolean ignored() {
            while (has()) {
                char ch = get();
                if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n') {
                    step();
                    continue;
                }
                if (ch != '/' || !extensions) return true;
                if (at + 1 >= text.length()) return false;
                char next = text.charAt(at + 1);
                int start = at;
                if (next == '/') {
                    step();
                    step();
                    while (has() && get() != '\n' && get() != '\r') step();
                } else if (next == '*') {
                    step();
                    step();
                    boolean closed = false;
                    while (has()) {
                        if (get() == '*' && at + 1 < text.length() && text.charAt(at + 1) == '/') {
                            step();
                            step();
                            closed = true;
                            break;
                        }
                        step();
                    }
                    if (!closed) return false;
                } else return false;
                emit(start, COMMENT);
            }
            return true;
        }

        boolean string(boolean key) {
            int start = at;
            step();
            while (has()) {
                char ch = get();
                if (ch == '"') {
                    step();
                    if (key) emit(start, JSON_KEY);
                    return true;
                }
                if (ch < 0x20) return false;
                if (ch != '\\') {
                    step();
                    continue;
                }
                step();
                if (!has()) return false;
                char escaped = get();
                if (escaped == 'u') {
                    step();
                    for (int i = 0; i < 4; i++) {
                        if (!has() || Character.digit(get(), 16) < 0) return false;
                        step();
                    }
                } else {
                    if ("\"\\/bfnrt".indexOf(escaped) < 0) return false;
                    step();
                }
            }
            return false;
        }

        boolean number() {
            int start = at;
            if (get() == '-') {
                step();
                if (!has()) return false;
            }
            if (get() == '0') {
                step();
                if (has() && Character.isDigit(get())) return false;
            } else {
                if (get() < '1' || get() > '9') return false;
                while (has() && Character.isDigit(get())) step();
            }
            if (has() && get() == '.') {
                step();
                if (!has() || !Character.isDigit(get())) return false;
                while (has() && Character.isDigit(get())) step();
            }
            if (has() && (get() == 'e' || get() == 'E')) {
                step();
                if (has() && (get() == '+' || get() == '-')) step();
                if (!has() || !Character.isDigit(get())) return false;
                while (has() && Character.isDigit(get())) step();
            }
            emit(start, NUMBER);
            return true;
        }

        boolean literal(String value) {
            int start = at;
            for (int i = 0; i < value.length(); i++) {
                if (!has() || get() != value.charAt(i)) return false;
                step();
            }
            emit(start, KEYWORD);
            return true;
        }

        boolean object(int depth) {
            if (depth > MAX_JSON_DEPTH) return false;
            step();
            if (!ignored()) return false;
            if (has() && get() == '}') {
                step();
                return true;
            }
            while (has()) {
                if (get() != '"' || !string(true) || !ignored() || !has() || get() != ':') return false;
                step();
                if (!ignored() || !has() || !value(depth + 1) || !ignored()) return false;
                if (!has()) return false;
                if (get() == '}') {
                    step();
                    return true;
                }
                if (get() != ',') return false;
                step();
                if (!ignored() || !has()) return false;
                if (get() == '}') {
                    if (!extensions) return false;
                    step();
                    return true;
                }
            }
            return false;
        }

        boolean array(int depth) {
            if (depth > MAX_JSON_DEPTH) return false;
            step();
            if (!ignored()) return false;
            if (has() && get() == ']') {
                step();
                return true;
            }
            while (has()) {
                if (!value(depth + 1) || !ignored() || !has()) return false;
                if (get() == ']') {
                    step();
                    return true;
                }
                if (get() != ',') return false;
                step();
                if (!ignored() || !has()) return false;
                if (get() == ']') {
                    if (!extensions) return false;
                    step();
                    return true;
                }
            }
            return false;
        }

        boolean value(int depth) {
            char ch = get();
            if (ch == '{') return object(depth);
            if (ch == '[') return array(depth);
            if (ch == '"') return string(false);
            if (ch == '-' || Character.isDigit(ch)) return number();
            if (ch == 't') return literal("true");
            if (ch == 'f') return literal("false");
            if (ch == 'n') return literal("null");
            return false;
        }
    }

    public static boolean scanJson(String text, boolean extensions, int limit, Sink sink, Runnable checkpoint) {
        checkpoint.run();
        JsonCursor cursor = new JsonCursor(text, extensions, limit, sink, checkpoint);
        return cursor.ignored() && cursor.has() && cursor.value(0) && cursor.ignored() && !cursor.has();
    }

    private static boolean shellBoundary(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r'
                || c == ';' || c == '&' || c == '|' || c == '(' || c == ')' || c == '<' || c == '>';
    }

    private static boolean shellSpecial(char c) {
        return shellBoundary(c) || c == '\'' || c == '"' || c == '`' || c == '$';
    }

    private static boolean identStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean identPart(char c) {
        return identStart(c) || (c >= '0' && c <= '9');
    }

    private static boolean isNumericWord(String text, int start, int end) {
        if (end <= start) return false;
        int i = start;
        if (end - start > 2 && text.charAt(i) == '0' && (text.charAt(i + 1) == 'x' || text.charAt(i + 1) == 'X')) {
            for (i += 2; i < end; i++) {
                if (Character.digit(text.charAt(i), 16) < 0) return false;
            }
            return true;
        }
        boolean dot = false;
        for (; i < end; i++) {
            char ch = text.charAt(i);
            if (ch == '.' && !dot && i > start && i + 1 < end) {
                dot = true;
            } else if (ch < '0' || ch > '9') {
                return false;
            }
        }
        return true;
    }

    public static void scanShell(String text, int limit, Sink sink, Runnable checkpoint) {
        checkpoint.run();
        Cursor c = new Cursor(text, checkpoint);
        int count = 0;
        boolean expectCommand = true;
        boolean inPrecommand = false;
        boolean inAssignmentValue = false;
        boolean expectWordListKeyword = false;
        boolean afterFunctionKeyword = false;
        String heredocEnd = null;
        boolean heredocStripTabs = false;

        while (c.has() && count < limit) {
            char ch = c.get();
            if (ch == ' ' || ch == '\t') {
                inAssignmentValue = false;
                c.step();
                continue;
            }
            if (ch == '\r' || ch == '\n') {
                if (ch == '\r') c.step();
                if (c.has() && c.get() == '\n') c.step();
                inAssignmentValue = false;
                expectCommand = true;
                inPrecommand = false;
                expectWordListKeyword = false;
                afterFunctionKeyword = false;
                if (heredocEnd != null && c.has()) {
                    int bodyStart = c.at;
                    while (c.has()) {
                        int lineStart = c.at;
                        int cmpStart = lineStart;
                        if (heredocStripTabs) {
                            while (cmpStart < text.length() && text.charAt(cmpStart) == '\t') cmpStart++;
                        }
                        while (c.has() && c.get() != '\n' && c.get() != '\r') c.step();
                        int lineEnd = c.at;
                        boolean matched = (lineEnd - cmpStart == heredocEnd.length())
                                && text.regionMatches(cmpStart, heredocEnd, 0, heredocEnd.length());
                        if (c.has() && c.get() == '\r') c.step();
                        if (c.has() && c.get() == '\n') c.step();
                        if (matched) break;
                    }
                    if (c.at > bodyStart && count < limit) {
                        sink.token(bodyStart, c.at, STRING);
                        count++;
                    }
                    heredocEnd = null;
                    heredocStripTabs = false;
                }
                continue;
            }
            if (ch == '\\' && c.at + 1 < text.length()) {
                char next = text.charAt(c.at + 1);
                if (next == '\n') {
                    c.step();
                    c.step();
                    continue;
                }
                if (next == '\r' && c.at + 2 < text.length() && text.charAt(c.at + 2) == '\n') {
                    c.step();
                    c.step();
                    c.step();
                    continue;
                }
            }
            boolean boundary = c.at == 0 || shellBoundary(text.charAt(c.at - 1));
            if (ch == '#' && boundary && !inAssignmentValue) {
                int start = c.at;
                while (c.has() && c.get() != '\n' && c.get() != '\r') c.step();
                sink.token(start, c.at, COMMENT);
                count++;
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                int start = c.at;
                c.step();
                while (c.has()) {
                    char next = c.get();
                    c.step();
                    if (ch != '\'' && next == '\\' && c.has()) {
                        c.step();
                    } else if (next == ch) {
                        break;
                    }
                }
                sink.token(start, c.at, STRING);
                count++;
                if (!inAssignmentValue) {
                    expectCommand = false;
                    inPrecommand = false;
                }
                continue;
            }
            if (ch == '$') {
                int start = c.at;
                c.step();
                if (!c.has()) continue;
                char next = c.get();
                if (next == '\'' || next == '"') {
                    c.step();
                    while (c.has()) {
                        char q = c.get();
                        c.step();
                        if (q == '\\' && c.has()) c.step();
                        else if (q == next) break;
                    }
                    sink.token(start, c.at, STRING);
                    count++;
                    if (!inAssignmentValue) {
                        expectCommand = false;
                        inPrecommand = false;
                    }
                    continue;
                }
                if (next == '(' && c.at + 1 < text.length() && text.charAt(c.at + 1) == '(') {
                    c.step();
                    c.step();
                    int depth = 2;
                    while (c.has() && depth > 0) {
                        char d = c.get();
                        c.step();
                        if (d == '(') depth++;
                        else if (d == ')') depth--;
                    }
                    sink.token(start, c.at, VARIABLE);
                    count++;
                    if (!inAssignmentValue) {
                        expectCommand = false;
                        inPrecommand = false;
                    }
                    continue;
                }
                if (next == '(') {
                    c.step();
                    sink.token(start, c.at, OPERATOR);
                    count++;
                    expectCommand = true;
                    inPrecommand = false;
                    inAssignmentValue = false;
                    continue;
                }
                if (next == '{') {
                    c.step();
                    int depth = 1;
                    while (c.has() && depth > 0) {
                        char b = c.get();
                        c.step();
                        if (b == '\\' && c.has()) c.step();
                        else if (b == '{') depth++;
                        else if (b == '}') depth--;
                    }
                    sink.token(start, c.at, VARIABLE);
                    count++;
                    if (!inAssignmentValue) {
                        expectCommand = false;
                        inPrecommand = false;
                    }
                    continue;
                }
                if (identStart(next)) {
                    while (c.has() && identPart(c.get())) c.step();
                } else if ("0123456789?#$!@*-_".indexOf(next) >= 0) {
                    c.step();
                }
                if (c.at > start + 1) {
                    sink.token(start, c.at, VARIABLE);
                    count++;
                    if (!inAssignmentValue) {
                        expectCommand = false;
                        inPrecommand = false;
                    }
                }
                continue;
            }
            if (boundary && ch >= '0' && ch <= '9' && c.at + 1 < text.length()) {
                char peek = text.charAt(c.at + 1);
                if (peek == '>' || peek == '<') {
                    int start = c.at;
                    c.step();
                    c.step();
                    if (c.has() && (c.get() == peek || c.get() == '&' || c.get() == '|')) {
                        char r = c.get();
                        c.step();
                        if (r == '&' && c.has() && (c.get() == '-' || Character.isDigit(c.get()))) {
                            while (c.has() && Character.isDigit(c.get())) c.step();
                            if (c.has() && c.get() == '-') c.step();
                        }
                    }
                    sink.token(start, c.at, OPERATOR);
                    count++;
                    inAssignmentValue = false;
                    continue;
                }
            }
            if (ch == '|' || ch == '&' || ch == ';' || ch == '(' || ch == ')' || ch == '<' || ch == '>') {
                int start = c.at;
                inAssignmentValue = false;
                if (ch == '<' && c.at + 1 < text.length() && text.charAt(c.at + 1) == '<') {
                    c.step();
                    c.step();
                    if (c.has() && c.get() == '<') {
                        c.step();
                        sink.token(start, c.at, OPERATOR);
                        count++;
                        continue;
                    }
                    boolean stripTabs = false;
                    if (c.has() && c.get() == '-') {
                        stripTabs = true;
                        c.step();
                    }
                    sink.token(start, c.at, OPERATOR);
                    count++;
                    while (c.has() && (c.get() == ' ' || c.get() == '\t')) c.step();
                    if (c.has() && c.get() != '\n' && c.get() != '\r' && !shellBoundary(c.get())) {
                        int tagStart = c.at;
                        StringBuilder delim = new StringBuilder();
                        char q = c.get();
                        if (q == '\'' || q == '"') {
                            c.step();
                            while (c.has() && c.get() != q && c.get() != '\n' && c.get() != '\r') {
                                delim.append(c.get());
                                c.step();
                            }
                            if (c.has() && c.get() == q) c.step();
                        } else {
                            if (q == '\\') c.step();
                            while (c.has() && !shellBoundary(c.get())) {
                                delim.append(c.get());
                                c.step();
                            }
                        }
                        if (delim.length() > 0) {
                            heredocEnd = delim.toString();
                            heredocStripTabs = stripTabs;
                            if (count < limit) {
                                sink.token(tagStart, c.at, KEYWORD);
                                count++;
                            }
                        }
                    }
                    continue;
                }
                c.step();
                if (ch == '|' && c.has() && (c.get() == '|' || c.get() == '&')) c.step();
                else if (ch == '&' && c.has()) {
                    if (c.get() == '&') c.step();
                    else if (c.get() == '>') {
                        c.step();
                        if (c.has() && c.get() == '>') c.step();
                        sink.token(start, c.at, OPERATOR);
                        count++;
                        continue;
                    }
                } else if (ch == ';' && c.has() && (c.get() == ';' || c.get() == '&')) {
                    c.step();
                    if (c.has() && c.get() == '&') c.step();
                } else if ((ch == '>' || ch == '<') && c.has()) {
                    if (c.get() == ch || c.get() == '&' || c.get() == '|') {
                        char r = c.get();
                        c.step();
                        if (r == '&' && c.has() && (c.get() == '-' || Character.isDigit(c.get()))) {
                            while (c.has() && Character.isDigit(c.get())) c.step();
                            if (c.has() && c.get() == '-') c.step();
                        }
                    }
                    sink.token(start, c.at, OPERATOR);
                    count++;
                    continue;
                }
                sink.token(start, c.at, OPERATOR);
                count++;
                expectCommand = true;
                inPrecommand = false;
                if (ch == ';') expectWordListKeyword = false;
                continue;
            }

            int start = c.at;
            if ((expectCommand || inPrecommand) && !inAssignmentValue && identStart(ch)) {
                int scan = c.at + 1;
                while (scan < text.length() && identPart(text.charAt(scan))) scan++;
                boolean plusEq = scan + 1 < text.length() && text.charAt(scan) == '+' && text.charAt(scan + 1) == '=';
                boolean eq = scan < text.length() && text.charAt(scan) == '=';
                if (eq || plusEq) {
                    while (c.at < scan) c.step();
                    sink.token(start, c.at, VARIABLE);
                    count++;
                    int opStart = c.at;
                    c.step();
                    if (plusEq) c.step();
                    if (count < limit) {
                        sink.token(opStart, c.at, OPERATOR);
                        count++;
                    }
                    inAssignmentValue = true;
                    continue;
                }
            }

            if (ch == '-' && boundary && !inAssignmentValue && (!expectCommand || inPrecommand)
                    && c.at + 1 < text.length() && !shellSpecial(text.charAt(c.at + 1)) && text.charAt(c.at + 1) != '=') {
                c.step();
                while (c.has() && !shellSpecial(c.get()) && c.get() != '=') {
                    if (c.get() == '\\' && c.at + 1 < text.length()) c.step();
                    c.step();
                }
                sink.token(start, c.at, FLAG);
                count++;
                if (c.has() && c.get() == '=') {
                    int eqStart = c.at;
                    c.step();
                    if (count < limit) {
                        sink.token(eqStart, c.at, OPERATOR);
                        count++;
                    }
                    inAssignmentValue = true;
                }
                continue;
            }

            while (c.has() && !shellSpecial(c.get())) {
                if (c.get() == '\\' && c.at + 1 < text.length()) {
                    char esc = text.charAt(c.at + 1);
                    if (esc == '\n' || esc == '\r') break;
                    c.step();
                }
                c.step();
            }
            int end = c.at;
            if (end <= start) {
                c.step();
                continue;
            }
            if (inAssignmentValue) {
                if (isNumericWord(text, start, end)) {
                    sink.token(start, end, NUMBER);
                    count++;
                }
                continue;
            }
            String token = end - start <= 16 ? text.substring(start, end) : null;
            if (afterFunctionKeyword) {
                sink.token(start, end, COMMAND);
                count++;
                afterFunctionKeyword = false;
                expectCommand = false;
            } else if (expectWordListKeyword && "in".equals(token)) {
                sink.token(start, end, KEYWORD);
                count++;
                expectWordListKeyword = false;
            } else if (expectCommand) {
                if ("function".equals(token)) {
                    sink.token(start, end, KEYWORD);
                    count++;
                    afterFunctionKeyword = true;
                } else if (token != null && SHELL_WORDLIST_KEYWORDS.contains(token)) {
                    sink.token(start, end, KEYWORD);
                    count++;
                    expectCommand = false;
                    expectWordListKeyword = true;
                } else if (token != null && SHELL_OPEN_KEYWORDS.contains(token)) {
                    sink.token(start, end, KEYWORD);
                    count++;
                    expectCommand = true;
                } else if ("[".equals(token) || "[[".equals(token)) {
                    sink.token(start, end, OPERATOR);
                    count++;
                    expectCommand = false;
                } else {
                    sink.token(start, end, COMMAND);
                    count++;
                    if (token != null && SHELL_PRECOMMANDS.contains(token)) {
                        expectCommand = true;
                        inPrecommand = true;
                    } else {
                        expectCommand = false;
                        inPrecommand = false;
                    }
                }
            } else {
                if ("]".equals(token) || "]]".equals(token)) {
                    sink.token(start, end, OPERATOR);
                    count++;
                } else if (isNumericWord(text, start, end)) {
                    sink.token(start, end, NUMBER);
                    count++;
                }
            }
        }
    }

    public static void scanConfig(String text, int limit, Sink sink, Runnable checkpoint) {
        checkpoint.run();
        Cursor c = new Cursor(text, checkpoint);
        int count = 0;
        boolean lineHead = true;

        while (c.has() && count < limit) {
            char ch = c.get();
            if (ch == ' ' || ch == '\t') {
                c.step();
                continue;
            }
            if (ch == '\r' || ch == '\n') {
                if (ch == '\r') c.step();
                if (c.has() && c.get() == '\n') c.step();
                lineHead = true;
                continue;
            }
            if (ch == '#' || (lineHead && ch == ';')) {
                int start = c.at;
                while (c.has() && c.get() != '\n' && c.get() != '\r') c.step();
                sink.token(start, c.at, COMMENT);
                count++;
                continue;
            }
            if (lineHead && ch == '-' && c.at + 1 < text.length() && text.charAt(c.at + 1) == ' ') {
                int start = c.at;
                c.step();
                sink.token(start, c.at, OPERATOR);
                count++;
                continue;
            }
            if (lineHead && ch == '[') {
                int start = c.at;
                while (c.has() && c.get() != '\n' && c.get() != '\r' && c.get() != '#') {
                    char cur = c.get();
                    c.step();
                    if (cur == ']') {
                        if (c.has() && c.get() == ']') c.step();
                        break;
                    }
                }
                sink.token(start, c.at, KEYWORD);
                count++;
                lineHead = false;
                continue;
            }
            if (ch == '\'' || ch == '"') {
                int start = c.at;
                c.step();
                while (c.has()) {
                    char next = c.get();
                    c.step();
                    if (ch == '"' && next == '\\' && c.has()) c.step();
                    else if (next == ch) break;
                }
                if (lineHead) {
                    int peek = c.at;
                    while (peek < text.length() && (text.charAt(peek) == ' ' || text.charAt(peek) == '\t')) peek++;
                    if (peek < text.length() && (text.charAt(peek) == ':' || text.charAt(peek) == '=')) {
                        sink.token(start, c.at, JSON_KEY);
                        count++;
                        lineHead = false;
                        continue;
                    }
                }
                sink.token(start, c.at, STRING);
                count++;
                lineHead = false;
                continue;
            }
            if (lineHead) {
                int start = c.at;
                while (c.has()) {
                    char cur = c.get();
                    if (cur == ':' || cur == '=' || cur == '\n' || cur == '\r' || cur == '#') break;
                    c.step();
                }
                int keyEnd = c.at;
                while (keyEnd > start && (text.charAt(keyEnd - 1) == ' ' || text.charAt(keyEnd - 1) == '\t')) keyEnd--;
                if (c.has() && (c.get() == ':' || c.get() == '=') && keyEnd > start) {
                    sink.token(start, keyEnd, JSON_KEY);
                    count++;
                    int sepStart = c.at;
                    c.step();
                    if (count < limit) {
                        sink.token(sepStart, c.at, OPERATOR);
                        count++;
                    }
                    lineHead = false;
                    continue;
                }
                lineHead = false;
                if (keyEnd > start && isNumericWord(text, start, keyEnd)) {
                    sink.token(start, keyEnd, NUMBER);
                    count++;
                }
                continue;
            }
            if (ch == '=' || ch == ':' || ch == '[' || ch == ']' || ch == '{' || ch == '}' || ch == ',') {
                c.step();
                continue;
            }
            int start = c.at;
            while (c.has()) {
                char cur = c.get();
                if (cur == ' ' || cur == '\t' || cur == '\n' || cur == '\r' || cur == '#' || cur == ',' || cur == ']' || cur == '}') break;
                c.step();
            }
            int end = c.at;
            if (end > start) {
                int numStart = text.charAt(start) == '-' && end - start > 1 ? start + 1 : start;
                if (isNumericWord(text, numStart, end)) {
                    sink.token(start, end, NUMBER);
                    count++;
                } else if (end - start <= 5 && CONFIG_KEYWORDS.contains(text.substring(start, end))) {
                    sink.token(start, end, KEYWORD);
                    count++;
                }
            }
        }
    }

    private static boolean word(char c) { return Character.isLetterOrDigit(c) || c == '_'; }
    private static final class Cursor {
        final String text;
        final Runnable checkpoint;
        int at;
        Cursor(String text, Runnable checkpoint) { this.text = text; this.checkpoint = checkpoint; }
        boolean has() { return at < text.length(); }
        char get() { return text.charAt(at); }
        void step() { if ((++at & 4095) == 0) checkpoint.run(); }
    }
    public static void scan(String text, boolean diff, int limit, Sink sink, Runnable checkpoint) {
        checkpoint.run();
        Cursor c = new Cursor(text, checkpoint);
        int count = 0;
        while (c.has() && count < limit) {
            int start = c.at, kind = 0;
            char ch = c.get();
            if (diff) {
                kind = ch == '+' ? ADDED : ch == '-' ? REMOVED :
                        ch == '@' && c.at + 1 < text.length() && text.charAt(c.at + 1) == '@' ? HUNK : 0;
                while (c.has() && c.get() != '\n' && c.get() != '\r') c.step();
                if (kind != 0) { sink.token(start, c.at, kind); count++; }
                if (c.has() && c.get() == '\r') c.step();
                if (c.has() && c.get() == '\n') c.step();
                continue;
            } else if (ch == '"' || ch == '\'') {
                kind = STRING;
                c.step();
                while (c.has()) {
                    char next = c.get(); c.step();
                    if (next == '\\' && c.has()) c.step();
                    else if (next == ch) break;
                }
            } else if (ch == '#' || (ch == '/' && c.at + 1 < text.length() && text.charAt(c.at + 1) == '/')) {
                kind = COMMENT;
                while (c.has() && c.get() != '\n') c.step();
            } else if (word(ch)) {
                c.step();
                while (c.has() && word(c.get())) c.step();
                boolean number = ch >= '0' && ch <= '9';
                for (int i = start + 1; number && i < c.at; i++) number = text.charAt(i) >= '0' && text.charAt(i) <= '9';
                if (number) {
                    kind = NUMBER;
                    if (c.at + 1 < text.length() && c.get() == '.' && Character.isDigit(text.charAt(c.at + 1))) {
                        c.step();
                        while (c.has() && Character.isDigit(c.get())) c.step();
                    }
                } else if (c.at - start <= 9 && KEYWORDS.contains(text.substring(start, c.at))) kind = KEYWORD;
            } else c.step();
            if (kind != 0) { sink.token(start, c.at, kind); count++; }
        }
    }
}
