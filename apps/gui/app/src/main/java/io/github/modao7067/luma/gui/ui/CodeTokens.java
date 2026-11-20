package io.github.modao7067.luma.gui.ui;

import java.util.Set;

public final class CodeTokens {
    public static final int STRING = 1, COMMENT = 2, NUMBER = 3, KEYWORD = 4,
            ADDED = 5, REMOVED = 6, HUNK = 7, JSON_KEY = 8,
            COMMAND = 9, FLAG = 10, VARIABLE = 11, OPERATOR = 12,
            FUNCTION = 13, TYPE = 14, PROPERTY = 15, ANNOTATION = 16, ESCAPE = 17;
    public static final int DISPLAY_SPAN_LIMIT = 1024;
    public static final int RICH_SPAN_LIMIT = 4096;
    public static final int LANG_DEFAULT = 0, LANG_KOTLIN = 1, LANG_JAVA = 2, LANG_C_FAMILY = 3,
            LANG_RUST = 4, LANG_GO = 5, LANG_SWIFT_DART = 6, LANG_PYTHON = 7,
            LANG_JS_TS = 8, LANG_RUBY_PERL = 9, LANG_PHP = 10, LANG_SQL = 11, LANG_ZIG = 12;
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
    private static final Set<String> SHELL_SUBCOMMAND_TOOLS = Set.of(
            "git", "docker", "kubectl", "cargo", "npm", "pnpm", "yarn", "bun", "pip", "uv",
            "adb", "systemctl", "apt", "brew", "go", "rustup", "gh");
    private static final Set<String> CONFIG_KEYWORDS = Set.of(
            "true", "false", "null", "yes", "no", "on", "off", "True", "False", "None");
    private static final Set<String> COMMON_KEYWORDS = Set.of(
            "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue",
            "return", "try", "catch", "throw", "new", "import", "true", "false", "null",
            "void", "class", "interface", "enum", "const", "static", "public", "private",
            "protected", "final", "abstract", "async", "await", "yield");
    private static final Set<String> KOTLIN_KEYWORDS = Set.of(
            "package", "fun", "val", "var", "object", "data", "sealed", "inner", "open",
            "override", "suspend", "inline", "noinline", "crossinline", "reified", "companion",
            "init", "constructor", "when", "is", "in", "out", "as", "by", "where", "typealias",
            "internal", "lateinit", "vararg", "tailrec", "operator", "infix", "external",
            "expect", "actual", "value", "annotation", "finally", "get", "set");
    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "package", "extends", "implements", "throws", "finally", "instanceof", "synchronized",
            "volatile", "transient", "native", "strictfp", "record", "sealed", "permits",
            "var", "assert", "module", "requires", "exports");
    private static final Set<String> C_FAMILY_KEYWORDS = Set.of(
            "struct", "union", "typedef", "sizeof", "alignof", "namespace", "using", "template",
            "typename", "virtual", "override", "constexpr", "consteval", "noexcept", "explicit",
            "friend", "mutable", "inline", "extern", "register", "auto", "goto", "nullptr",
            "delete", "operator", "concept", "requires", "co_await", "co_return", "co_yield",
            "readonly", "internal", "sealed", "partial", "ref", "out", "in", "params", "is",
            "as", "nameof", "typeof", "stackalloc", "unsafe", "fixed", "lock", "checked",
            "unchecked", "delegate", "event", "get", "set", "init", "record", "var", "dynamic");
    private static final Set<String> RUST_KEYWORDS = Set.of(
            "fn", "let", "mut", "pub", "crate", "mod", "use", "struct", "enum", "trait",
            "impl", "type", "where", "match", "loop", "in", "as", "ref", "move", "dyn",
            "unsafe", "extern", "macro_rules");
    private static final Set<String> GO_KEYWORDS = Set.of(
            "package", "func", "var", "type", "struct", "map", "chan", "go", "defer",
            "select", "range", "fallthrough", "goto", "nil", "iota");
    private static final Set<String> SWIFT_DART_KEYWORDS = Set.of(
            "func", "let", "var", "struct", "protocol", "extension", "typealias", "associatedtype",
            "guard", "defer", "fallthrough", "repeat", "in", "where", "is", "as", "nil", "inout",
            "mutating", "nonmutating", "throws", "rethrows", "actor", "some", "any", "mixin",
            "typedef", "part", "library", "export", "show", "hide", "late", "required",
            "covariant", "factory", "get", "set", "operator", "external", "sealed", "base",
            "on", "rethrow", "with");
    private static final Set<String> PYTHON_KEYWORDS = Set.of(
            "def", "lambda", "pass", "raise", "finally", "with", "as", "from", "global",
            "nonlocal", "assert", "del", "in", "is", "not", "and", "or", "elif", "except",
            "match", "case", "True", "False", "None");
    private static final Set<String> JS_TS_KEYWORDS = Set.of(
            "function", "let", "var", "export", "from", "as", "of", "in", "instanceof",
            "typeof", "delete", "debugger", "finally", "extends", "implements", "type",
            "namespace", "module", "declare", "readonly", "keyof", "infer", "is", "asserts",
            "override", "satisfies", "undefined", "NaN", "Infinity", "get", "set");
    private static final Set<String> RUBY_PERL_KEYWORDS = Set.of(
            "def", "end", "module", "elsif", "unless", "until", "begin", "rescue", "ensure",
            "raise", "when", "then", "and", "or", "not", "in", "defined", "nil", "alias",
            "undef", "sub", "my", "our", "local", "use", "require", "package", "foreach",
            "next", "last", "redo", "die");
    private static final Set<String> PHP_KEYWORDS = Set.of(
            "function", "fn", "use", "namespace", "extends", "implements", "trait", "finally",
            "echo", "print", "require", "require_once", "include", "include_once", "isset",
            "empty", "unset", "array", "list", "match", "elseif", "endforeach", "endif",
            "endfor", "endwhile", "declare", "clone", "instanceof", "insteadof", "global",
            "var", "readonly");
    private static final Set<String> ZIG_KEYWORDS = Set.of(
            "fn", "var", "pub", "struct", "union", "error", "errdefer", "defer", "orelse",
            "comptime", "inline", "noinline", "extern", "export", "packed", "align",
            "allowzero", "volatile", "linksection", "threadlocal", "unreachable", "undefined",
            "and", "or", "test", "asm", "anytype", "anyframe");
    private static final Set<String> SQL_KEYWORDS = Set.of(
            "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE",
            "CREATE", "ALTER", "DROP", "TABLE", "INDEX", "VIEW", "JOIN", "INNER", "LEFT",
            "RIGHT", "FULL", "CROSS", "ON", "AS", "AND", "OR", "NOT", "IN", "IS", "NULL",
            "TRUE", "FALSE", "LIKE", "ILIKE", "BETWEEN", "EXISTS", "GROUP", "BY", "HAVING",
            "ORDER", "ASC", "DESC", "LIMIT", "OFFSET", "UNION", "ALL", "DISTINCT", "CASE",
            "WHEN", "THEN", "ELSE", "END", "CAST", "PRIMARY", "KEY", "FOREIGN", "REFERENCES",
            "UNIQUE", "DEFAULT", "CONSTRAINT", "BEGIN", "COMMIT", "ROLLBACK", "RETURNING",
            "WITH", "EXPLAIN", "ANALYZE", "PRAGMA");
    private static final Set<String> DEFAULT_EXTRA_KEYWORDS = Set.of(
            "fun", "val", "var", "def", "fn", "let", "function", "from", "None", "nil",
            "undefined", "struct", "impl", "trait", "pub", "use", "mut", "func", "package",
            "when", "in", "is", "as", "match", "finally", "with", "raise", "extends", "implements");
    private static final Set<String> PRIMITIVE_TYPES = Set.of(
            "void", "boolean", "bool", "byte", "short", "char", "int", "long", "float",
            "double", "str", "bytes", "i8", "i16", "i32", "i64", "i128", "isize",
            "u8", "u16", "u32", "u64", "u128", "usize", "f32", "f64", "rune");
    private static final Set<String> TS_PRIMITIVE_TYPES = Set.of(
            "string", "number", "bigint", "symbol", "any", "unknown", "never");
    private static final Set<String> KOTLIN_DSL_CALLS = Set.of(
            "remember", "buildAnnotatedString", "runCatching", "withContext",
            "produceState", "LaunchedEffect", "SideEffect");

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
            emitRange(start, at, kind);
        }

        void emitRange(int start, int end, int kind) {
            if (count < limit && end > start) {
                sink.token(start, end, kind);
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
            int segStart = start;
            step();
            while (has()) {
                char ch = get();
                if (ch == '"') {
                    step();
                    if (key) emit(start, JSON_KEY);
                    else emitRange(segStart, at, STRING);
                    return true;
                }
                if (ch < 0x20) return false;
                if (ch != '\\') {
                    step();
                    continue;
                }
                int escStart = at;
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
                if (!key) {
                    emitRange(segStart, escStart, STRING);
                    emitRange(escStart, at, ESCAPE);
                    segStart = at;
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
                int colonStart = at;
                step();
                emitRange(colonStart, at, OPERATOR);
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

    private static int emitQuotedEscapedString(String text, int start, int end, int limit, int count, Sink sink, boolean jsonEscapesOnly) {
        int segStart = start;
        int pos = start + 1;
        int bodyEnd = (end > start + 1 && text.charAt(end - 1) == text.charAt(start)) ? end - 1 : end;
        while (pos < bodyEnd && count < limit) {
            if (text.charAt(pos) == '\\' && pos + 1 < bodyEnd) {
                char esc = text.charAt(pos + 1);
                int escEnd = pos + 2;
                if (esc == 'u' && pos + 5 < bodyEnd && isHex4(text, pos + 2)) {
                    escEnd = pos + 6;
                } else if (jsonEscapesOnly && "\"\\/bfnrt".indexOf(esc) < 0) {
                    pos += 2;
                    continue;
                }
                if (pos > segStart && count < limit) {
                    sink.token(segStart, pos, STRING);
                    count++;
                }
                if (count < limit) {
                    sink.token(pos, escEnd, ESCAPE);
                    count++;
                }
                segStart = escEnd;
                pos = escEnd;
            } else {
                pos++;
            }
        }
        if (end > segStart && count < limit) {
            sink.token(segStart, end, STRING);
            count++;
        }
        return count;
    }

    private static boolean isHex4(String text, int start) {
        if (start + 4 > text.length()) return false;
        for (int i = 0; i < 4; i++) {
            if (Character.digit(text.charAt(start + i), 16) < 0) return false;
        }
        return true;
    }

    public static void scanJsonFragment(String text, int limit, Sink sink, Runnable checkpoint) {
        checkpoint.run();
        Cursor c = new Cursor(text, checkpoint);
        int count = 0;
        while (c.has() && count < limit) {
            char ch = c.get();
            if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n') {
                c.step();
                continue;
            }
            if (ch == '/' && c.at + 1 < text.length()) {
                char next = text.charAt(c.at + 1);
                if (next == '/') {
                    int start = c.at;
                    c.step();
                    c.step();
                    while (c.has() && c.get() != '\n' && c.get() != '\r') c.step();
                    sink.token(start, c.at, COMMENT);
                    count++;
                    continue;
                }
                if (next == '*') {
                    int start = c.at;
                    c.step();
                    c.step();
                    while (c.has()) {
                        if (c.get() == '*' && c.at + 1 < text.length() && text.charAt(c.at + 1) == '/') {
                            c.step();
                            c.step();
                            break;
                        }
                        c.step();
                    }
                    sink.token(start, c.at, COMMENT);
                    count++;
                    continue;
                }
            }
            if (ch == '"') {
                int start = c.at;
                c.step();
                while (c.has()) {
                    char cur = c.get();
                    c.step();
                    if (cur == '\\' && c.has()) {
                        c.step();
                    } else if (cur == '"') {
                        break;
                    }
                }
                int end = c.at;
                int peek = end;
                while (peek < text.length()) {
                    char ws = text.charAt(peek);
                    if (ws == ' ' || ws == '\t' || ws == '\r' || ws == '\n') peek++;
                    else break;
                }
                if (peek < text.length() && text.charAt(peek) == ':') {
                    sink.token(start, end, JSON_KEY);
                    count++;
                } else {
                    count = emitQuotedEscapedString(text, start, end, limit, count, sink, true);
                }
                continue;
            }
            if (ch == ':') {
                int start = c.at;
                c.step();
                sink.token(start, c.at, OPERATOR);
                count++;
                continue;
            }
            if (ch == '-' || (ch >= '0' && ch <= '9')) {
                int start = c.at;
                if (ch == '-') c.step();
                if (c.has() && c.get() >= '0' && c.get() <= '9') {
                    while (c.has() && c.get() >= '0' && c.get() <= '9') c.step();
                    if (c.has() && c.get() == '.' && c.at + 1 < text.length() && Character.isDigit(text.charAt(c.at + 1))) {
                        c.step();
                        while (c.has() && Character.isDigit(c.get())) c.step();
                    }
                    if (c.has() && (c.get() == 'e' || c.get() == 'E')) {
                        int expAt = c.at;
                        c.step();
                        if (c.has() && (c.get() == '+' || c.get() == '-')) c.step();
                        if (c.has() && Character.isDigit(c.get())) {
                            while (c.has() && Character.isDigit(c.get())) c.step();
                        } else {
                            c.at = expAt;
                        }
                    }
                    sink.token(start, c.at, NUMBER);
                    count++;
                    continue;
                }
                if (c.at == start) c.step();
                continue;
            }
            if (identStart(ch)) {
                int start = c.at;
                c.step();
                while (c.has() && identPart(c.get())) c.step();
                int len = c.at - start;
                if ((len == 4 && text.regionMatches(start, "true", 0, 4))
                        || (len == 5 && text.regionMatches(start, "false", 0, 5))
                        || (len == 4 && text.regionMatches(start, "null", 0, 4))) {
                    sink.token(start, c.at, KEYWORD);
                    count++;
                }
                continue;
            }
            c.step();
        }
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

    private static boolean isShellPathOrTarget(String text, int start, int end) {
        int len = end - start;
        if (len <= 0) return false;
        char first = text.charAt(start);
        if (first == '/') return true;
        if (first == ':' && len > 1 && identPart(text.charAt(start + 1))) return true;
        if (text.startsWith("./", start) || text.startsWith("../", start) || text.startsWith("~/", start)) return true;
        if (text.startsWith("http://", start) || text.startsWith("https://", start)
                || text.startsWith("file://", start) || text.startsWith("ssh://", start)) return true;
        for (int i = start + 1; i < end; i++) {
            if (text.charAt(i) == '/') return true;
        }
        return false;
    }

    private static int scanShellDoubleString(String text, int openStart, Cursor c, int limit, int count, Sink sink) {
        int segStart = openStart;
        while (c.has()) {
            char q = c.get();
            if (q == '"') {
                c.step();
                if (c.at > segStart && count < limit) {
                    sink.token(segStart, c.at, STRING);
                    count++;
                }
                return count;
            }
            if (q == '\\' && c.at + 1 < text.length()) {
                char esc = text.charAt(c.at + 1);
                if (esc == '"' || esc == '\\' || esc == '$' || esc == '`' || esc == 'n' || esc == 'r' || esc == 't' || esc == '\n') {
                    if (c.at > segStart && count < limit) {
                        sink.token(segStart, c.at, STRING);
                        count++;
                    }
                    int escStart = c.at;
                    c.step();
                    c.step();
                    if (count < limit) {
                        sink.token(escStart, c.at, ESCAPE);
                        count++;
                    }
                    segStart = c.at;
                } else {
                    c.step();
                    c.step();
                }
                continue;
            }
            if (q == '$' && c.at + 1 < text.length()) {
                char next = text.charAt(c.at + 1);
                int varStart = c.at;
                boolean matched = false;
                if (next == '(' && c.at + 2 < text.length() && text.charAt(c.at + 2) == '(') {
                    c.step();
                    c.step();
                    c.step();
                    int depth = 2;
                    while (c.has() && depth > 0) {
                        char d = c.get();
                        c.step();
                        if (d == '(') depth++;
                        else if (d == ')') depth--;
                    }
                    matched = true;
                } else if (next == '(') {
                    c.step();
                    c.step();
                    int depth = 1;
                    while (c.has() && depth > 0) {
                        char d = c.get();
                        c.step();
                        if (d == '\\' && c.has()) c.step();
                        else if (d == '\'') {
                            while (c.has()) {
                                char sq = c.get();
                                c.step();
                                if (sq == '\'') break;
                            }
                        } else if (d == '(') depth++;
                        else if (d == ')') depth--;
                    }
                    matched = true;
                } else if (next == '{') {
                    c.step();
                    c.step();
                    int depth = 1;
                    while (c.has() && depth > 0) {
                        char b = c.get();
                        c.step();
                        if (b == '\\' && c.has()) c.step();
                        else if (b == '{') depth++;
                        else if (b == '}') depth--;
                    }
                    matched = true;
                } else if (identStart(next)) {
                    c.step();
                    while (c.has() && identPart(c.get())) c.step();
                    matched = true;
                } else if ("0123456789?#$!@*-_".indexOf(next) >= 0) {
                    c.step();
                    c.step();
                    matched = true;
                }
                if (matched) {
                    if (varStart > segStart && count < limit) {
                        sink.token(segStart, varStart, STRING);
                        count++;
                    }
                    if (count < limit) {
                        sink.token(varStart, c.at, VARIABLE);
                        count++;
                    }
                    segStart = c.at;
                    continue;
                }
            }
            c.step();
        }
        if (c.at > segStart && count < limit) {
            sink.token(segStart, c.at, STRING);
            count++;
        }
        return count;
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
        boolean expectSubcommand = false;
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
                expectSubcommand = false;
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
            if (ch == '"') {
                int start = c.at;
                c.step();
                count = scanShellDoubleString(text, start, c, limit, count, sink);
                if (!inAssignmentValue) {
                    expectCommand = false;
                    inPrecommand = false;
                    expectSubcommand = false;
                }
                continue;
            }
            if (ch == '\'' || ch == '`') {
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
                    expectSubcommand = false;
                }
                continue;
            }
            if (ch == '$') {
                int start = c.at;
                c.step();
                if (!c.has()) continue;
                char next = c.get();
                if (next == '"') {
                    c.step();
                    count = scanShellDoubleString(text, start, c, limit, count, sink);
                    if (!inAssignmentValue) {
                        expectCommand = false;
                        inPrecommand = false;
                        expectSubcommand = false;
                    }
                    continue;
                }
                if (next == '\'') {
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
                        expectSubcommand = false;
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
                        expectSubcommand = false;
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
                    expectSubcommand = false;
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
                        expectSubcommand = false;
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
                        expectSubcommand = false;
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
                    expectSubcommand = false;
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
                        expectSubcommand = false;
                        continue;
                    }
                    boolean stripTabs = false;
                    if (c.has() && c.get() == '-') {
                        stripTabs = true;
                        c.step();
                    }
                    sink.token(start, c.at, OPERATOR);
                    count++;
                    expectSubcommand = false;
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
                        expectSubcommand = false;
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
                    expectSubcommand = false;
                    continue;
                }
                sink.token(start, c.at, OPERATOR);
                count++;
                expectCommand = true;
                inPrecommand = false;
                expectSubcommand = false;
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
                expectSubcommand = false;
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
                } else if (isShellPathOrTarget(text, start, end)) {
                    sink.token(start, end, PROPERTY);
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
                expectSubcommand = false;
            } else if (expectWordListKeyword && "in".equals(token)) {
                sink.token(start, end, KEYWORD);
                count++;
                expectWordListKeyword = false;
                expectSubcommand = false;
            } else if (expectCommand) {
                if ("function".equals(token)) {
                    sink.token(start, end, KEYWORD);
                    count++;
                    afterFunctionKeyword = true;
                    expectSubcommand = false;
                } else if (token != null && SHELL_WORDLIST_KEYWORDS.contains(token)) {
                    sink.token(start, end, KEYWORD);
                    count++;
                    expectCommand = false;
                    expectWordListKeyword = true;
                    expectSubcommand = false;
                } else if (token != null && SHELL_OPEN_KEYWORDS.contains(token)) {
                    sink.token(start, end, KEYWORD);
                    count++;
                    expectCommand = true;
                    expectSubcommand = false;
                } else if ("[".equals(token) || "[[".equals(token)) {
                    sink.token(start, end, OPERATOR);
                    count++;
                    expectCommand = false;
                    expectSubcommand = false;
                } else {
                    sink.token(start, end, COMMAND);
                    count++;
                    if (token != null && SHELL_PRECOMMANDS.contains(token)) {
                        expectCommand = true;
                        inPrecommand = true;
                        expectSubcommand = false;
                    } else {
                        expectCommand = false;
                        inPrecommand = false;
                        int slash = text.lastIndexOf('/', end - 1);
                        int baseStart = slash >= start ? slash + 1 : start;
                        int baseLen = end - baseStart;
                        expectSubcommand = baseLen > 0 && baseLen <= 12
                                && SHELL_SUBCOMMAND_TOOLS.contains(text.substring(baseStart, end));
                    }
                }
            } else {
                if ("]".equals(token) || "]]".equals(token)) {
                    sink.token(start, end, OPERATOR);
                    count++;
                } else if (isNumericWord(text, start, end)) {
                    sink.token(start, end, NUMBER);
                    count++;
                } else if (isShellPathOrTarget(text, start, end)) {
                    sink.token(start, end, PROPERTY);
                    count++;
                } else if (expectSubcommand) {
                    sink.token(start, end, TYPE);
                    count++;
                }
                expectSubcommand = false;
            }
        }
    }

    private static boolean isConfigScalarDateOrVersion(String text, int start, int end) {
        int len = end - start;
        if (len >= 10 && Character.isDigit(text.charAt(start)) && text.charAt(start + 4) == '-' && text.charAt(start + 7) == '-') {
            boolean dateOk = true;
            for (int i = start; i < start + 10; i++) {
                if (i == start + 4 || i == start + 7) continue;
                if (!Character.isDigit(text.charAt(i))) {
                    dateOk = false;
                    break;
                }
            }
            if (dateOk) return true;
        }
        int i = start;
        if ((text.charAt(i) == 'v' || text.charAt(i) == 'V') && i + 1 < end && Character.isDigit(text.charAt(i + 1))) {
            i++;
        }
        if (!Character.isDigit(text.charAt(i))) return false;
        boolean hasDot = false;
        for (; i < end; i++) {
            char ch = text.charAt(i);
            if (ch == '.') {
                if (i + 1 >= end || !Character.isDigit(text.charAt(i + 1))) return false;
                hasDot = true;
            } else if (!Character.isLetterOrDigit(ch) && ch != '-' && ch != '+') {
                return false;
            }
        }
        return hasDot;
    }

    private static boolean isPascalCase(String text, int start, int end) {
        if (end <= start) return false;
        char first = text.charAt(start);
        if (first < 'A' || first > 'Z') return false;
        for (int i = start + 1; i < end; i++) {
            char ch = text.charAt(i);
            if (ch >= 'a' && ch <= 'z') return true;
        }
        return false;
    }

    private static boolean isScreamingSnake(String text, int start, int end) {
        if (end - start < 2) return false;
        char first = text.charAt(start);
        if (first < 'A' || first > 'Z') return false;
        boolean hasLetter = true;
        for (int i = start + 1; i < end; i++) {
            char ch = text.charAt(i);
            if ((ch < 'A' || ch > 'Z') && (ch < '0' || ch > '9') && ch != '_') return false;
        }
        return hasLetter;
    }

    private static int scanConfigDoubleString(String text, int start, Cursor c, int limit, int count, Sink sink) {
        int segStart = start;
        while (c.has()) {
            char next = c.get();
            if (next == '"') {
                c.step();
                if (c.at > segStart && count < limit) {
                    sink.token(segStart, c.at, STRING);
                    count++;
                }
                return count;
            }
            if (next == '\\' && c.at + 1 < text.length()) {
                if (c.at > segStart && count < limit) {
                    sink.token(segStart, c.at, STRING);
                    count++;
                }
                int escStart = c.at;
                c.step();
                c.step();
                if (count < limit) {
                    sink.token(escStart, c.at, ESCAPE);
                    count++;
                }
                segStart = c.at;
                continue;
            }
            if (next == '$' && c.at + 1 < text.length()) {
                char after = text.charAt(c.at + 1);
                int varStart = c.at;
                boolean matched = false;
                if (after == '{') {
                    c.step();
                    c.step();
                    int depth = 1;
                    while (c.has() && depth > 0) {
                        char b = c.get();
                        c.step();
                        if (b == '{') depth++;
                        else if (b == '}') depth--;
                    }
                    matched = true;
                } else if (identStart(after)) {
                    c.step();
                    while (c.has() && identPart(c.get())) c.step();
                    matched = true;
                }
                if (matched) {
                    if (varStart > segStart && count < limit) {
                        sink.token(segStart, varStart, STRING);
                        count++;
                    }
                    if (count < limit) {
                        sink.token(varStart, c.at, VARIABLE);
                        count++;
                    }
                    segStart = c.at;
                    continue;
                }
            }
            c.step();
        }
        if (c.at > segStart && count < limit) {
            sink.token(segStart, c.at, STRING);
            count++;
        }
        return count;
    }

    public static void scanConfig(String text, int limit, Sink sink, Runnable checkpoint) {
        checkpoint.run();
        Cursor c = new Cursor(text, checkpoint);
        int count = 0;
        boolean lineHead = true;
        int braceDepth = 0;
        boolean expectInlineKey = false;

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
                if (braceDepth == 0) expectInlineKey = false;
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
                if (lineHead || expectInlineKey) {
                    c.step();
                    while (c.has()) {
                        char next = c.get();
                        c.step();
                        if (ch == '"' && next == '\\' && c.has()) c.step();
                        else if (next == ch) break;
                    }
                    int peek = c.at;
                    while (peek < text.length() && (text.charAt(peek) == ' ' || text.charAt(peek) == '\t')) peek++;
                    if (peek < text.length() && (text.charAt(peek) == ':' || text.charAt(peek) == '=')) {
                        sink.token(start, c.at, JSON_KEY);
                        count++;
                        lineHead = false;
                        expectInlineKey = false;
                        continue;
                    }
                    if (ch == '"') {
                        c.at = start + 1;
                        count = scanConfigDoubleString(text, start, c, limit, count, sink);
                    } else {
                        sink.token(start, c.at, STRING);
                        count++;
                    }
                    lineHead = false;
                    expectInlineKey = false;
                    continue;
                }
                c.step();
                if (ch == '"') {
                    count = scanConfigDoubleString(text, start, c, limit, count, sink);
                } else {
                    while (c.has()) {
                        char next = c.get();
                        c.step();
                        if (next == ch) break;
                    }
                    sink.token(start, c.at, STRING);
                    count++;
                }
                lineHead = false;
                continue;
            }
            if ((lineHead || expectInlineKey) && ch != '=' && ch != ':' && ch != '[' && ch != ']' && ch != '{' && ch != '}' && ch != ',') {
                int start = c.at;
                while (c.has()) {
                    char cur = c.get();
                    if (cur == ':' || cur == '=' || cur == '\n' || cur == '\r' || cur == '#'
                            || (expectInlineKey && (cur == ',' || cur == '}' || cur == '{' || cur == ']'))) break;
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
                    expectInlineKey = false;
                    continue;
                }
                lineHead = false;
                expectInlineKey = false;
                if (keyEnd > start) {
                    int numStart = text.charAt(start) == '-' && keyEnd - start > 1 ? start + 1 : start;
                    if (isNumericWord(text, numStart, keyEnd) || isConfigScalarDateOrVersion(text, numStart, keyEnd)) {
                        sink.token(start, keyEnd, NUMBER);
                        count++;
                    } else if (keyEnd - start <= 5 && CONFIG_KEYWORDS.contains(text.substring(start, keyEnd))) {
                        sink.token(start, keyEnd, KEYWORD);
                        count++;
                    } else if (isShellPathOrTarget(text, start, keyEnd)) {
                        sink.token(start, keyEnd, PROPERTY);
                        count++;
                    } else if (isPascalCase(text, start, keyEnd)) {
                        sink.token(start, keyEnd, TYPE);
                        count++;
                    }
                }
                continue;
            }
            if (ch == '=' || ch == ':' || ch == '[' || ch == ']' || ch == '{' || ch == '}' || ch == ',') {
                int opStart = c.at;
                c.step();
                sink.token(opStart, c.at, OPERATOR);
                count++;
                lineHead = false;
                if (ch == '{') {
                    braceDepth++;
                    expectInlineKey = true;
                } else if (ch == '}') {
                    if (braceDepth > 0) braceDepth--;
                    expectInlineKey = false;
                } else if (ch == ',') {
                    expectInlineKey = braceDepth > 0;
                } else {
                    expectInlineKey = false;
                }
                continue;
            }
            if ((ch == '|' || ch == '>') && (c.at + 1 >= text.length() || text.charAt(c.at + 1) == ' ' || text.charAt(c.at + 1) == '\t' || text.charAt(c.at + 1) == '\n' || text.charAt(c.at + 1) == '\r')) {
                int opStart = c.at;
                c.step();
                sink.token(opStart, c.at, OPERATOR);
                count++;
                continue;
            }
            if (ch == '$' && c.at + 1 < text.length()) {
                char next = text.charAt(c.at + 1);
                if (next == '{') {
                    int start = c.at;
                    c.step();
                    c.step();
                    boolean doubleBrace = c.has() && c.get() == '{';
                    if (doubleBrace) c.step();
                    int depth = doubleBrace ? 2 : 1;
                    while (c.has() && depth > 0) {
                        char b = c.get();
                        c.step();
                        if (b == '{') depth++;
                        else if (b == '}') depth--;
                    }
                    sink.token(start, c.at, VARIABLE);
                    count++;
                    continue;
                }
                if (identStart(next)) {
                    int start = c.at;
                    c.step();
                    while (c.has() && identPart(c.get())) c.step();
                    sink.token(start, c.at, VARIABLE);
                    count++;
                    continue;
                }
            }
            if ((ch == '&' || ch == '*') && c.at + 1 < text.length() && identStart(text.charAt(c.at + 1))) {
                int start = c.at;
                c.step();
                while (c.has() && (identPart(c.get()) || c.get() == '-')) c.step();
                sink.token(start, c.at, TYPE);
                count++;
                continue;
            }
            if (ch == '!' && c.at + 1 < text.length() && text.charAt(c.at + 1) == '!') {
                int start = c.at;
                while (c.has() && text.charAt(c.at) != ' ' && text.charAt(c.at) != '\t' && text.charAt(c.at) != '\n' && text.charAt(c.at) != '\r') c.step();
                sink.token(start, c.at, TYPE);
                count++;
                continue;
            }
            int start = c.at;
            while (c.has()) {
                char cur = c.get();
                if (cur == ' ' || cur == '\t' || cur == '\n' || cur == '\r' || cur == '#' || cur == ',' || cur == ']' || cur == '}') break;
                if (cur == '$' && c.at > start && c.at + 1 < text.length() && (text.charAt(c.at + 1) == '{' || identStart(text.charAt(c.at + 1)))) break;
                c.step();
            }
            int end = c.at;
            if (end > start) {
                int numStart = text.charAt(start) == '-' && end - start > 1 ? start + 1 : start;
                if (isNumericWord(text, numStart, end) || isConfigScalarDateOrVersion(text, numStart, end)) {
                    sink.token(start, end, NUMBER);
                    count++;
                } else if (end - start <= 5 && CONFIG_KEYWORDS.contains(text.substring(start, end))) {
                    sink.token(start, end, KEYWORD);
                    count++;
                } else if (isShellPathOrTarget(text, start, end)) {
                    sink.token(start, end, PROPERTY);
                    count++;
                } else if (isPascalCase(text, start, end)) {
                    sink.token(start, end, TYPE);
                    count++;
                } else {
                    sink.token(start, end, STRING);
                    count++;
                }
            }
        }
    }

    private static boolean isCodeKeyword(int lang, String token) {
        if (lang == LANG_SQL) {
            return SQL_KEYWORDS.contains(token.toUpperCase(java.util.Locale.ROOT));
        }
        if (COMMON_KEYWORDS.contains(token)) return true;
        switch (lang) {
            case LANG_KOTLIN: return KOTLIN_KEYWORDS.contains(token);
            case LANG_JAVA: return JAVA_KEYWORDS.contains(token);
            case LANG_C_FAMILY: return C_FAMILY_KEYWORDS.contains(token);
            case LANG_RUST: return RUST_KEYWORDS.contains(token);
            case LANG_GO: return GO_KEYWORDS.contains(token);
            case LANG_SWIFT_DART: return SWIFT_DART_KEYWORDS.contains(token);
            case LANG_PYTHON: return PYTHON_KEYWORDS.contains(token);
            case LANG_JS_TS: return JS_TS_KEYWORDS.contains(token);
            case LANG_RUBY_PERL: return RUBY_PERL_KEYWORDS.contains(token);
            case LANG_PHP: return PHP_KEYWORDS.contains(token);
            case LANG_ZIG: return ZIG_KEYWORDS.contains(token);
            default: return DEFAULT_EXTRA_KEYWORDS.contains(token);
        }
    }

    private static int skipHorizWs(String text, int pos, int end) {
        while (pos < end && (text.charAt(pos) == ' ' || text.charAt(pos) == '\t')) pos++;
        return pos;
    }

    private static boolean isCallAhead(String text, int pos, int end) {
        int p = skipHorizWs(text, pos, end);
        if (p >= end) return false;
        if (text.charAt(p) == '(') return true;
        if (text.charAt(p) != '<') return false;
        int depth = 1;
        p++;
        while (p < end && depth > 0) {
            char ch = text.charAt(p);
            if (ch == '\n' || ch == '\r' || ch == ';') return false;
            if (ch == '<') depth++;
            else if (ch == '>') depth--;
            p++;
        }
        if (depth != 0) return false;
        p = skipHorizWs(text, p, end);
        return p < end && text.charAt(p) == '(';
    }

    private static int findInterpEnd(String text, int openStart, int end, char closeChar) {
        int pos = openStart;
        int depth = 1;
        char openChar = closeChar == ')' ? '(' : '{';
        while (pos < end && depth > 0) {
            char ch = text.charAt(pos);
            if (ch == '"' || ch == '\'' || ch == '`') {
                pos++;
                while (pos < end) {
                    char q = text.charAt(pos++);
                    if (ch != '\'' && q == '\\' && pos < end) pos++;
                    else if (q == ch) break;
                }
                continue;
            }
            if (ch == openChar) depth++;
            else if (ch == closeChar) {
                depth--;
                if (depth == 0) return pos;
            }
            pos++;
        }
        return -1;
    }

    private static void emitToken(Sink sink, int[] count, int limit, int start, int end, int kind) {
        if (kind > 0 && count[0] < limit && end > start) {
            sink.token(start, end, kind);
            count[0]++;
        }
    }

    private static void scanDelimitedCodeString(
            String text,
            int openStart,
            int quoteLen,
            char quoteChar,
            boolean multiLine,
            boolean allowEscape,
            boolean allowBareDollarInterp,
            boolean allowDollarBraceInterp,
            boolean allowBraceInterp,
            boolean allowHashInterp,
            boolean allowParenInterp,
            Cursor c,
            int lang,
            int limit,
            int[] count,
            int depth,
            Sink sink,
            Runnable checkpoint
    ) {
        int segStart = openStart;
        while (c.has() && count[0] < limit) {
            if (quoteLen == 3) {
                if (c.at + 2 < c.end && text.charAt(c.at) == quoteChar
                        && text.charAt(c.at + 1) == quoteChar && text.charAt(c.at + 2) == quoteChar) {
                    c.step();
                    c.step();
                    c.step();
                    while (c.has() && c.get() == quoteChar && !(c.at + 2 < c.end && text.charAt(c.at + 1) == quoteChar && text.charAt(c.at + 2) == quoteChar)) {
                        c.step();
                    }
                    emitToken(sink, count, limit, segStart, c.at, STRING);
                    return;
                }
            } else {
                char ch = c.get();
                if (ch == quoteChar) {
                    c.step();
                    emitToken(sink, count, limit, segStart, c.at, STRING);
                    return;
                }
                if (!multiLine && (ch == '\n' || ch == '\r')) {
                    emitToken(sink, count, limit, segStart, c.at, STRING);
                    return;
                }
            }
            char ch = c.get();
            if (allowParenInterp && ch == '\\' && c.at + 1 < c.end && text.charAt(c.at + 1) == '(' && depth < 8) {
                int exprClose = findInterpEnd(text, c.at + 2, c.end, ')');
                if (exprClose >= 0) {
                    emitToken(sink, count, limit, segStart, c.at, STRING);
                    int dStart = c.at;
                    c.step();
                    c.step();
                    emitToken(sink, count, limit, dStart, c.at, ESCAPE);
                    scanCodeRange(text, c.at, exprClose, lang, limit, count, depth + 1, sink, checkpoint);
                    c.at = exprClose;
                    c.step();
                    emitToken(sink, count, limit, exprClose, c.at, ESCAPE);
                    segStart = c.at;
                    continue;
                }
            }
            if (allowEscape && ch == '\\' && c.at + 1 < c.end) {
                emitToken(sink, count, limit, segStart, c.at, STRING);
                int escStart = c.at;
                c.step();
                char esc = c.get();
                c.step();
                if (esc == 'u') {
                    if (c.has() && c.get() == '{') {
                        c.step();
                        while (c.has() && c.get() != '}' && c.get() != '\n') c.step();
                        if (c.has() && c.get() == '}') c.step();
                    } else {
                        for (int i = 0; i < 4 && c.has() && Character.digit(c.get(), 16) >= 0; i++) c.step();
                    }
                } else if (esc == 'x') {
                    for (int i = 0; i < 2 && c.has() && Character.digit(c.get(), 16) >= 0; i++) c.step();
                }
                emitToken(sink, count, limit, escStart, c.at, ESCAPE);
                segStart = c.at;
                continue;
            }
            if (allowBraceInterp && ch == '{') {
                if (c.at + 1 < c.end && text.charAt(c.at + 1) == '{') {
                    emitToken(sink, count, limit, segStart, c.at, STRING);
                    int eStart = c.at;
                    c.step();
                    c.step();
                    emitToken(sink, count, limit, eStart, c.at, ESCAPE);
                    segStart = c.at;
                    continue;
                }
                if (depth < 8) {
                    int exprClose = findInterpEnd(text, c.at + 1, c.end, '}');
                    if (exprClose >= 0) {
                        emitToken(sink, count, limit, segStart, c.at, STRING);
                        int dStart = c.at;
                        c.step();
                        emitToken(sink, count, limit, dStart, c.at, ESCAPE);
                        scanCodeRange(text, c.at, exprClose, lang, limit, count, depth + 1, sink, checkpoint);
                        c.at = exprClose;
                        c.step();
                        emitToken(sink, count, limit, exprClose, c.at, ESCAPE);
                        segStart = c.at;
                        continue;
                    }
                }
            }
            if (allowBraceInterp && ch == '}' && c.at + 1 < c.end && text.charAt(c.at + 1) == '}') {
                emitToken(sink, count, limit, segStart, c.at, STRING);
                int eStart = c.at;
                c.step();
                c.step();
                emitToken(sink, count, limit, eStart, c.at, ESCAPE);
                segStart = c.at;
                continue;
            }
            if (allowHashInterp && ch == '#' && c.at + 1 < c.end && text.charAt(c.at + 1) == '{' && depth < 8) {
                int exprClose = findInterpEnd(text, c.at + 2, c.end, '}');
                if (exprClose >= 0) {
                    emitToken(sink, count, limit, segStart, c.at, STRING);
                    int dStart = c.at;
                    c.step();
                    c.step();
                    emitToken(sink, count, limit, dStart, c.at, ESCAPE);
                    scanCodeRange(text, c.at, exprClose, lang, limit, count, depth + 1, sink, checkpoint);
                    c.at = exprClose;
                    c.step();
                    emitToken(sink, count, limit, exprClose, c.at, ESCAPE);
                    segStart = c.at;
                    continue;
                }
            }
            if ((allowDollarBraceInterp || allowBareDollarInterp) && ch == '$' && c.at + 1 < c.end) {
                char next = text.charAt(c.at + 1);
                if (allowDollarBraceInterp && next == '{' && depth < 8) {
                    int exprClose = findInterpEnd(text, c.at + 2, c.end, '}');
                    if (exprClose >= 0) {
                        emitToken(sink, count, limit, segStart, c.at, STRING);
                        int dStart = c.at;
                        c.step();
                        c.step();
                        emitToken(sink, count, limit, dStart, c.at, ESCAPE);
                        scanCodeRange(text, c.at, exprClose, lang, limit, count, depth + 1, sink, checkpoint);
                        c.at = exprClose;
                        c.step();
                        emitToken(sink, count, limit, exprClose, c.at, ESCAPE);
                        segStart = c.at;
                        continue;
                    }
                } else if (allowBareDollarInterp && identStart(next)) {
                    emitToken(sink, count, limit, segStart, c.at, STRING);
                    int vStart = c.at;
                    c.step();
                    while (c.has() && identPart(c.get())) c.step();
                    emitToken(sink, count, limit, vStart, c.at, VARIABLE);
                    segStart = c.at;
                    continue;
                }
            }
            c.step();
        }
        emitToken(sink, count, limit, segStart, c.at, STRING);
    }

    private static boolean tryScanJsxTag(String text, Cursor c, int lang, int limit, int[] count, int depth, Sink sink, Runnable checkpoint) {
        if (c.at + 1 >= c.end) return false;
        int pos = c.at + 1;
        if (text.charAt(pos) == '/') {
            pos++;
            if (pos >= c.end || !identStart(text.charAt(pos))) return false;
            int tagStart = pos;
            while (pos < c.end && (identPart(text.charAt(pos)) || text.charAt(pos) == '.' || text.charAt(pos) == '-')) pos++;
            int tagEnd = pos;
            while (pos < c.end && (text.charAt(pos) == ' ' || text.charAt(pos) == '\t')) pos++;
            if (pos >= c.end || text.charAt(pos) != '>') return false;
            emitToken(sink, count, limit, c.at, c.at + 2, OPERATOR);
            emitToken(sink, count, limit, tagStart, tagEnd, isPascalCase(text, tagStart, tagEnd) ? TYPE : KEYWORD);
            emitToken(sink, count, limit, pos, pos + 1, OPERATOR);
            c.at = pos + 1;
            return true;
        }
        if (!identStart(text.charAt(pos))) return false;
        int tagStart = pos;
        while (pos < c.end && (identPart(text.charAt(pos)) || text.charAt(pos) == '.' || text.charAt(pos) == '-')) pos++;
        int tagEnd = pos;
        if (pos >= c.end) return false;
        char afterTag = text.charAt(pos);
        if (afterTag != '>' && afterTag != '/' && afterTag != ' ' && afterTag != '\t' && afterTag != '\n' && afterTag != '\r') {
            return false;
        }
        int scan = pos;
        int closePos = -1;
        while (scan < c.end) {
            char cur = text.charAt(scan);
            if (cur == '>') {
                closePos = scan + 1;
                break;
            }
            if (cur == '/' && scan + 1 < c.end && text.charAt(scan + 1) == '>') {
                closePos = scan + 2;
                break;
            }
            if (cur == ' ' || cur == '\t' || cur == '\n' || cur == '\r') {
                scan++;
                continue;
            }
            if (cur == '"' || cur == '\'') {
                scan++;
                while (scan < c.end) {
                    char q = text.charAt(scan++);
                    if (q == '\\' && scan < c.end) scan++;
                    else if (q == cur) break;
                }
                continue;
            }
            if (cur == '{') {
                int braceEnd = findInterpEnd(text, scan + 1, c.end, '}');
                if (braceEnd < 0) return false;
                scan = braceEnd + 1;
                continue;
            }
            if (identStart(cur)) {
                while (scan < c.end && (identPart(text.charAt(scan)) || text.charAt(scan) == '-' || text.charAt(scan) == ':')) scan++;
                while (scan < c.end && (text.charAt(scan) == ' ' || text.charAt(scan) == '\t')) scan++;
                if (scan < c.end && text.charAt(scan) == '=') {
                    scan++;
                    while (scan < c.end && (text.charAt(scan) == ' ' || text.charAt(scan) == '\t')) scan++;
                    if (scan >= c.end || (text.charAt(scan) != '"' && text.charAt(scan) != '\'' && text.charAt(scan) != '{')) return false;
                }
                continue;
            }
            return false;
        }
        if (closePos < 0) return false;
        emitToken(sink, count, limit, c.at, c.at + 1, OPERATOR);
        emitToken(sink, count, limit, tagStart, tagEnd, isPascalCase(text, tagStart, tagEnd) ? TYPE : KEYWORD);
        c.at = tagEnd;
        while (c.at < closePos && count[0] < limit) {
            char cur = c.get();
            if (cur == ' ' || cur == '\t' || cur == '\n' || cur == '\r') {
                c.step();
                continue;
            }
            if (cur == '/' && c.at + 1 == closePos - 1) {
                int s = c.at;
                c.step();
                c.step();
                emitToken(sink, count, limit, s, c.at, OPERATOR);
                return true;
            }
            if (cur == '>') {
                int s = c.at;
                c.step();
                emitToken(sink, count, limit, s, c.at, OPERATOR);
                return true;
            }
            if (cur == '=') {
                int s = c.at;
                c.step();
                emitToken(sink, count, limit, s, c.at, OPERATOR);
                continue;
            }
            if (cur == '"' || cur == '\'') {
                int s = c.at;
                c.step();
                scanDelimitedCodeString(text, s, 1, cur, false, true, false, false, false, false, false, c, lang, limit, count, depth, sink, checkpoint);
                continue;
            }
            if (cur == '{') {
                int braceEnd = findInterpEnd(text, c.at + 1, closePos, '}');
                if (braceEnd >= 0) {
                    c.step();
                    scanCodeRange(text, c.at, braceEnd, lang, limit, count, depth + 1, sink, checkpoint);
                    c.at = braceEnd + 1;
                    continue;
                }
            }
            if (identStart(cur)) {
                int s = c.at;
                c.step();
                while (c.has() && (identPart(c.get()) || c.get() == '-' || c.get() == ':')) c.step();
                emitToken(sink, count, limit, s, c.at, PROPERTY);
                continue;
            }
            c.step();
        }
        return true;
    }

    public static void scanCode(String text, int lang, int limit, Sink sink, Runnable checkpoint) {
        checkpoint.run();
        int[] count = {0};
        scanCodeRange(text, 0, text.length(), lang, limit, count, 0, sink, checkpoint);
    }

    private static void scanCodeRange(
            String text,
            int rangeStart,
            int rangeEnd,
            int lang,
            int limit,
            int[] count,
            int depth,
            Sink sink,
            Runnable checkpoint
    ) {
        Cursor c = new Cursor(text, rangeStart, rangeEnd, checkpoint);
        final int declNone = 0, declFunc = 1, declType = 2, declVar = 3, declImport = 4;
        int afterDecl = declNone;
        boolean afterDot = false;
        boolean afterColon = false;
        boolean afterInclude = false;
        boolean afterQuestion = false;
        boolean atMemberKeyStart = true;
        boolean jsxAllowed = true;
        boolean lineStart = true;
        int parenDepth = 0;
        int braceDepth = 0;

        while (c.has() && count[0] < limit) {
            char ch = c.get();
            if (ch == ' ' || ch == '\t') {
                c.step();
                continue;
            }
            if (ch == '\r' || ch == '\n') {
                if (ch == '\r') c.step();
                if (c.has() && c.get() == '\n') c.step();
                lineStart = true;
                afterInclude = false;
                afterColon = false;
                afterQuestion = false;
                atMemberKeyStart = true;
                if (afterDecl == declImport) afterDecl = declNone;
                continue;
            }
            if ((lang == LANG_PYTHON || lang == LANG_RUBY_PERL) && ch == '#') {
                int start = c.at;
                while (c.has() && c.get() != '\n' && c.get() != '\r') c.step();
                emitToken(sink, count, limit, start, c.at, COMMENT);
                continue;
            }
            if (lang == LANG_PHP && ch == '#') {
                if (c.at + 1 < c.end && text.charAt(c.at + 1) == '[') {
                    int start = c.at;
                    c.step();
                    c.step();
                    int bd = 1;
                    while (c.has() && bd > 0 && c.get() != '\n' && c.get() != '\r') {
                        char b = c.get();
                        c.step();
                        if (b == '[') bd++;
                        else if (b == ']') bd--;
                    }
                    emitToken(sink, count, limit, start, c.at, ANNOTATION);
                    lineStart = false;
                    jsxAllowed = true;
                    continue;
                }
                int start = c.at;
                while (c.has() && c.get() != '\n' && c.get() != '\r') c.step();
                emitToken(sink, count, limit, start, c.at, COMMENT);
                continue;
            }
            if (lang == LANG_SQL && ch == '-' && c.at + 1 < c.end && text.charAt(c.at + 1) == '-') {
                int start = c.at;
                while (c.has() && c.get() != '\n' && c.get() != '\r') c.step();
                emitToken(sink, count, limit, start, c.at, COMMENT);
                continue;
            }
            if (lang != LANG_PYTHON && lang != LANG_RUBY_PERL && ch == '/' && c.at + 1 < c.end) {
                char next = text.charAt(c.at + 1);
                if (next == '/' && lang != LANG_SQL) {
                    int start = c.at;
                    while (c.has() && c.get() != '\n' && c.get() != '\r') c.step();
                    emitToken(sink, count, limit, start, c.at, COMMENT);
                    continue;
                }
                if (next == '*') {
                    int start = c.at;
                    c.step();
                    c.step();
                    while (c.has()) {
                        if (c.get() == '*' && c.at + 1 < c.end && text.charAt(c.at + 1) == '/') {
                            c.step();
                            c.step();
                            break;
                        }
                        c.step();
                    }
                    emitToken(sink, count, limit, start, c.at, COMMENT);
                    continue;
                }
            }
            if (lang == LANG_C_FAMILY && lineStart && ch == '#') {
                int start = c.at;
                c.step();
                while (c.has() && (c.get() == ' ' || c.get() == '\t')) c.step();
                int dirStart = c.at;
                while (c.has() && identPart(c.get())) c.step();
                String dir = text.substring(dirStart, c.at);
                emitToken(sink, count, limit, start, c.at, ANNOTATION);
                afterInclude = "include".equals(dir) || "import".equals(dir);
                lineStart = false;
                afterDecl = declNone;
                afterDot = false;
                continue;
            }
            if (afterInclude && ch == '<') {
                int start = c.at;
                c.step();
                while (c.has() && c.get() != '>' && c.get() != '\n' && c.get() != '\r') c.step();
                if (c.has() && c.get() == '>') c.step();
                emitToken(sink, count, limit, start, c.at, STRING);
                afterInclude = false;
                lineStart = false;
                continue;
            }
            if (lang == LANG_RUST && ch == '#' && c.at + 1 < c.end) {
                int start = c.at;
                int peek = c.at + 1;
                if (text.charAt(peek) == '!') peek++;
                if (peek < c.end && text.charAt(peek) == '[') {
                    c.at = peek + 1;
                    int bd = 1;
                    while (c.has() && bd > 0) {
                        char b = c.get();
                        c.step();
                        if (b == '"' || b == '\'') {
                            while (c.has()) {
                                char q = c.get();
                                c.step();
                                if (b != '\'' && q == '\\' && c.has()) c.step();
                                else if (q == b) break;
                            }
                        } else if (b == '[') bd++;
                        else if (b == ']') bd--;
                    }
                    emitToken(sink, count, limit, start, c.at, ANNOTATION);
                    lineStart = false;
                    continue;
                }
            }
            if (ch == '@' && c.at + 1 < c.end && identStart(text.charAt(c.at + 1))) {
                int start = c.at;
                c.step();
                while (c.has() && identPart(c.get())) c.step();
                if (lang == LANG_KOTLIN && c.has() && c.get() == ':' && c.at + 1 < c.end && identStart(text.charAt(c.at + 1))) {
                    c.step();
                    while (c.has() && identPart(c.get())) c.step();
                }
                emitToken(sink, count, limit, start, c.at, ANNOTATION);
                lineStart = false;
                afterDot = false;
                afterColon = false;
                continue;
            }
            if (lang == LANG_RUST && (ch == 'r' || ch == 'b' || ch == 'c')) {
                int p = c.at + 1;
                if ((ch == 'b' || ch == 'c') && p < c.end && text.charAt(p) == 'r') p++;
                if (ch == 'r' || p > c.at + 1) {
                    int hashes = 0;
                    while (p < c.end && text.charAt(p) == '#') {
                        hashes++;
                        p++;
                    }
                    if (p < c.end && text.charAt(p) == '"') {
                        int start = c.at;
                        c.at = p + 1;
                        while (c.has()) {
                            char cur = c.get();
                            c.step();
                            if (cur == '"') {
                                int matched = 0;
                                while (matched < hashes && c.has() && c.get() == '#') {
                                    c.step();
                                    matched++;
                                }
                                if (matched == hashes) break;
                            }
                        }
                        emitToken(sink, count, limit, start, c.at, STRING);
                        lineStart = false;
                        afterDecl = declNone;
                        afterDot = false;
                        jsxAllowed = false;
                        continue;
                    }
                }
            }
            if (lang == LANG_PYTHON && "fFbBrRuU".indexOf(ch) >= 0) {
                int p = c.at + 1;
                if (p < c.end && "fFbBrRuU".indexOf(text.charAt(p)) >= 0) p++;
                if (p < c.end && (text.charAt(p) == '"' || text.charAt(p) == '\'')) {
                    boolean raw = false;
                    boolean fstr = false;
                    for (int i = c.at; i < p; i++) {
                        char pc = Character.toLowerCase(text.charAt(i));
                        if (pc == 'r') raw = true;
                        if (pc == 'f') fstr = true;
                    }
                    char qc = text.charAt(p);
                    int qLen = (p + 2 < c.end && text.charAt(p + 1) == qc && text.charAt(p + 2) == qc) ? 3 : 1;
                    int start = c.at;
                    c.at = p + qLen;
                    scanDelimitedCodeString(text, start, qLen, qc, qLen == 3, !raw, false, false, fstr, false, false, c, lang, limit, count, depth, sink, checkpoint);
                    lineStart = false;
                    afterDecl = declNone;
                    afterDot = false;
                    jsxAllowed = false;
                    continue;
                }
            }
            if (lang == LANG_RUST && ch == '\'' && c.at + 1 < c.end && identStart(text.charAt(c.at + 1))) {
                int scan = c.at + 2;
                while (scan < c.end && identPart(text.charAt(scan))) scan++;
                if (scan >= c.end || text.charAt(scan) != '\'') {
                    emitToken(sink, count, limit, c.at, scan, ANNOTATION);
                    c.at = scan;
                    lineStart = false;
                    afterDot = false;
                    continue;
                }
            }
            if ((ch == '"' || (lang == LANG_PYTHON && ch == '\''))
                    && c.at + 2 < c.end && text.charAt(c.at + 1) == ch && text.charAt(c.at + 2) == ch) {
                int start = c.at;
                c.step();
                c.step();
                c.step();
                boolean allowEsc = lang != LANG_KOTLIN;
                boolean allowDollar = lang == LANG_KOTLIN || lang == LANG_SWIFT_DART;
                boolean allowHash = lang == LANG_RUBY_PERL;
                scanDelimitedCodeString(text, start, 3, ch, true, allowEsc, allowDollar, allowDollar, false, allowHash, lang == LANG_SWIFT_DART, c, lang, limit, count, depth, sink, checkpoint);
                lineStart = false;
                afterDecl = declNone;
                afterDot = false;
                atMemberKeyStart = false;
                jsxAllowed = false;
                continue;
            }
            if (ch == '`') {
                if (lang == LANG_KOTLIN) {
                    int start = c.at;
                    c.step();
                    while (c.has() && c.get() != '`' && c.get() != '\n' && c.get() != '\r') c.step();
                    if (c.has() && c.get() == '`') c.step();
                    int end = c.at;
                    if (afterDecl == declFunc) {
                        emitToken(sink, count, limit, start, end, FUNCTION);
                        afterDecl = declNone;
                    } else if (isCallAhead(text, end, c.end)) {
                        emitToken(sink, count, limit, start, end, FUNCTION);
                    } else if (afterDot) {
                        emitToken(sink, count, limit, start, end, PROPERTY);
                    }
                    lineStart = false;
                    afterDot = false;
                    atMemberKeyStart = false;
                    jsxAllowed = false;
                    continue;
                }
                if (lang == LANG_SQL) {
                    int start = c.at;
                    c.step();
                    while (c.has() && c.get() != '`' && c.get() != '\n') c.step();
                    if (c.has() && c.get() == '`') c.step();
                    emitToken(sink, count, limit, start, c.at, PROPERTY);
                    lineStart = false;
                    afterDot = false;
                    continue;
                }
                int start = c.at;
                c.step();
                boolean tpl = lang == LANG_JS_TS;
                scanDelimitedCodeString(text, start, 1, '`', true, tpl, false, tpl, false, false, false, c, lang, limit, count, depth, sink, checkpoint);
                lineStart = false;
                afterDecl = declNone;
                afterDot = false;
                jsxAllowed = false;
                continue;
            }
            if (ch == '"' || ch == '\'') {
                int start = c.at;
                c.step();
                boolean allowDollar = ch == '"' && (lang == LANG_KOTLIN || lang == LANG_PHP || lang == LANG_RUBY_PERL || lang == LANG_SWIFT_DART);
                boolean allowHash = ch == '"' && lang == LANG_RUBY_PERL;
                boolean allowParen = ch == '"' && lang == LANG_SWIFT_DART;
                scanDelimitedCodeString(text, start, 1, ch, false, true, allowDollar, allowDollar, false, allowHash, allowParen, c, lang, limit, count, depth, sink, checkpoint);
                lineStart = false;
                afterDecl = declNone;
                afterDot = false;
                atMemberKeyStart = false;
                jsxAllowed = false;
                continue;
            }
            if (lang == LANG_JS_TS && ch == '<' && depth < 8 && (jsxAllowed || (c.at + 1 < c.end && text.charAt(c.at + 1) == '/'))) {
                if (tryScanJsxTag(text, c, lang, limit, count, depth, sink, checkpoint)) {
                    lineStart = false;
                    afterDecl = declNone;
                    afterDot = false;
                    jsxAllowed = true;
                    continue;
                }
            }
            if ((ch >= '0' && ch <= '9') || (ch == '.' && c.at + 1 < c.end && Character.isDigit(text.charAt(c.at + 1)))) {
                int start = c.at;
                if (ch == '0' && c.at + 1 < c.end) {
                    char p = text.charAt(c.at + 1);
                    if (p == 'x' || p == 'X') {
                        c.step();
                        c.step();
                        while (c.has() && (Character.digit(c.get(), 16) >= 0 || c.get() == '_')) c.step();
                    } else if (p == 'b' || p == 'B') {
                        c.step();
                        c.step();
                        while (c.has() && (c.get() == '0' || c.get() == '1' || c.get() == '_')) c.step();
                    } else if (p == 'o' || p == 'O') {
                        c.step();
                        c.step();
                        while (c.has() && ((c.get() >= '0' && c.get() <= '7') || c.get() == '_')) c.step();
                    }
                }
                if (c.at == start) {
                    if (ch == '.') c.step();
                    while (c.has() && (Character.isDigit(c.get()) || c.get() == '_')) c.step();
                    if (c.has() && c.get() == '.' && c.at + 1 < c.end && Character.isDigit(text.charAt(c.at + 1))) {
                        c.step();
                        while (c.has() && (Character.isDigit(c.get()) || c.get() == '_')) c.step();
                    }
                    if (c.has() && (c.get() == 'e' || c.get() == 'E')) {
                        int expAt = c.at;
                        c.step();
                        if (c.has() && (c.get() == '+' || c.get() == '-')) c.step();
                        if (c.has() && Character.isDigit(c.get())) {
                            while (c.has() && (Character.isDigit(c.get()) || c.get() == '_')) c.step();
                        } else {
                            c.at = expAt;
                        }
                    }
                }
                while (c.has() && "fFdDlLuUiIn".indexOf(c.get()) >= 0) {
                    if ((c.get() == 'i' || c.get() == 'u' || c.get() == 'f') && c.at + 1 < c.end && Character.isDigit(text.charAt(c.at + 1))) {
                        c.step();
                        while (c.has() && Character.isDigit(c.get())) c.step();
                        break;
                    }
                    if (c.get() == 'i' && c.at + 4 <= c.end && text.regionMatches(c.at, "isize", 0, 5)) {
                        c.at += 5;
                        break;
                    }
                    if (c.get() == 'u' && c.at + 4 <= c.end && text.regionMatches(c.at, "usize", 0, 5)) {
                        c.at += 5;
                        break;
                    }
                    c.step();
                }
                emitToken(sink, count, limit, start, c.at, NUMBER);
                lineStart = false;
                afterDecl = declNone;
                afterDot = false;
                afterColon = false;
                afterQuestion = false;
                atMemberKeyStart = false;
                jsxAllowed = false;
                continue;
            }
            if (lang == LANG_PHP && ch == '$' && c.at + 1 < c.end && identStart(text.charAt(c.at + 1))) {
                int start = c.at;
                c.step();
                while (c.has() && identPart(c.get())) c.step();
                emitToken(sink, count, limit, start, c.at, VARIABLE);
                lineStart = false;
                afterDecl = declNone;
                afterDot = false;
                jsxAllowed = false;
                continue;
            }
            if (identStart(ch) || Character.isLetter(ch)) {
                int start = c.at;
                c.step();
                while (c.has() && (Character.isLetterOrDigit(c.get()) || c.get() == '_')) c.step();
                int end = c.at;
                String word = text.substring(start, end);
                int kind = 0;
                int nextDecl = declNone;
                boolean nextJsx = false;

                if (afterDecl == declImport) {
                    if ("as".equals(word) || "from".equals(word) || "import".equals(word) || "static".equals(word)) {
                        kind = KEYWORD;
                    } else if (isPascalCase(text, start, end)) {
                        kind = TYPE;
                    } else if (isScreamingSnake(text, start, end)) {
                        kind = VARIABLE;
                    }
                    nextDecl = declImport;
                } else if ((!afterDot || (lang == LANG_JAVA && "class".equals(word))) && isCodeKeyword(lang, word)) {
                    kind = KEYWORD;
                    if ("fun".equals(word) || "def".equals(word) || "fn".equals(word) || "function".equals(word) || "func".equals(word) || "sub".equals(word)) {
                        nextDecl = declFunc;
                    } else if ("class".equals(word) || "interface".equals(word) || "struct".equals(word) || "enum".equals(word)
                            || "trait".equals(word) || "record".equals(word) || "object".equals(word) || "protocol".equals(word)
                            || "typealias".equals(word) || "mixin".equals(word) || ("type".equals(word) && (lang == LANG_GO || lang == LANG_RUST || lang == LANG_JS_TS))) {
                        nextDecl = declType;
                    } else if ("val".equals(word) || "var".equals(word) || "let".equals(word) || "const".equals(word)) {
                        nextDecl = declVar;
                    } else if ("import".equals(word) || "package".equals(word)
                            || ("from".equals(word) && lang == LANG_PYTHON)
                            || ("use".equals(word) && (lang == LANG_RUST || lang == LANG_PHP))) {
                        nextDecl = declImport;
                    }
                    if ("return".equals(word) || "throw".equals(word) || "yield".equals(word) || "await".equals(word)
                            || "case".equals(word) || "default".equals(word) || "else".equals(word)) {
                        nextJsx = true;
                    }
                } else if (!afterDot && ("this".equals(word) || "super".equals(word) || "self".equals(word) || "cls".equals(word)
                        || (lang == LANG_KOTLIN && "it".equals(word)))) {
                    kind = VARIABLE;
                } else if (!afterDot && (PRIMITIVE_TYPES.contains(word) || (lang == LANG_JS_TS && TS_PRIMITIVE_TYPES.contains(word)))) {
                    kind = TYPE;
                } else if (afterDecl == declFunc) {
                    int peek = skipHorizWs(text, end, c.end);
                    if (peek < c.end && text.charAt(peek) == '.' && isPascalCase(text, start, end)) {
                        kind = TYPE;
                        nextDecl = declFunc;
                    } else {
                        kind = FUNCTION;
                    }
                } else if (afterDecl == declType) {
                    kind = TYPE;
                } else if (lang == LANG_RUST && c.has() && c.get() == '!' && c.at + 1 < c.end) {
                    int afterBangPos = skipHorizWs(text, c.at + 1, c.end);
                    if (afterBangPos < c.end) {
                        char afterBang = text.charAt(afterBangPos);
                        if (afterBang == '(' || afterBang == '[' || afterBang == '{') {
                            c.step();
                            end = c.at;
                            kind = FUNCTION;
                        }
                    }
                }
                if (kind == 0 && afterDecl != declImport) {
                    int peek = skipHorizWs(text, end, c.end);
                    if (isPascalCase(text, start, end)) {
                        kind = TYPE;
                    } else if (isScreamingSnake(text, start, end)) {
                        kind = VARIABLE;
                    } else if (isCallAhead(text, end, c.end)
                            || (afterDot && peek < c.end && text.charAt(peek) == '{')
                            || (!afterDot && lang == LANG_KOTLIN && KOTLIN_DSL_CALLS.contains(word) && peek < c.end && text.charAt(peek) == '{')) {
                        kind = FUNCTION;
                    } else if (parenDepth > 0 && afterDecl != declVar && !afterDot && !afterColon
                            && peek < c.end && text.charAt(peek) == '='
                            && (peek + 1 >= c.end || (text.charAt(peek + 1) != '=' && text.charAt(peek + 1) != '>'))) {
                        kind = PROPERTY;
                    } else if (lang == LANG_JS_TS && braceDepth > 0 && !afterDot && !afterQuestion && atMemberKeyStart
                            && peek < c.end && text.charAt(peek) == ':'
                            && (peek + 1 >= c.end || text.charAt(peek + 1) != ':')) {
                        kind = PROPERTY;
                    } else if (afterDot) {
                        kind = PROPERTY;
                    }
                }
                emitToken(sink, count, limit, start, end, kind);
                afterDecl = nextDecl;
                afterDot = false;
                afterColon = false;
                afterQuestion = false;
                atMemberKeyStart = false;
                lineStart = false;
                jsxAllowed = nextJsx;
                continue;
            }
            if (ch == '(' || ch == ')' || ch == '{' || ch == '}' || ch == '[' || ch == ']' || ch == ',' || ch == ';') {
                c.step();
                if (ch == '(') {
                    parenDepth++;
                    afterDecl = declNone;
                    jsxAllowed = true;
                } else if (ch == ')') {
                    if (parenDepth > 0) parenDepth--;
                    jsxAllowed = false;
                } else if (ch == '{') {
                    braceDepth++;
                    atMemberKeyStart = true;
                    afterDecl = declNone;
                    jsxAllowed = true;
                } else if (ch == '}') {
                    if (braceDepth > 0) braceDepth--;
                    atMemberKeyStart = true;
                    jsxAllowed = false;
                } else if (ch == '[') {
                    jsxAllowed = true;
                } else if (ch == ']') {
                    jsxAllowed = false;
                } else if (ch == ',') {
                    atMemberKeyStart = true;
                    afterColon = false;
                    afterQuestion = false;
                    jsxAllowed = true;
                } else {
                    atMemberKeyStart = true;
                    afterDecl = declNone;
                    afterColon = false;
                    afterQuestion = false;
                    jsxAllowed = true;
                }
                afterDot = false;
                lineStart = false;
                continue;
            }
            if (ch == '.' && c.at + 1 < c.end && text.charAt(c.at + 1) == '.') {
                int start = c.at;
                c.step();
                c.step();
                if (c.has() && c.get() == '<') c.step();
                emitToken(sink, count, limit, start, c.at, OPERATOR);
                afterDot = false;
                lineStart = false;
                jsxAllowed = false;
                continue;
            }
            if (ch == '.') {
                int start = c.at;
                c.step();
                emitToken(sink, count, limit, start, c.at, OPERATOR);
                afterDot = true;
                afterColon = false;
                lineStart = false;
                jsxAllowed = false;
                continue;
            }
            if (ch == '?' && c.at + 1 < c.end && text.charAt(c.at + 1) == '.') {
                int start = c.at;
                c.step();
                c.step();
                emitToken(sink, count, limit, start, c.at, OPERATOR);
                afterDot = true;
                afterColon = false;
                lineStart = false;
                jsxAllowed = false;
                continue;
            }
            if ("=+-*/%<>!&|^~?:".indexOf(ch) >= 0) {
                int start = c.at;
                c.step();
                while (c.has() && "=+-*/%<>!&|^~?:".indexOf(c.get()) >= 0) {
                    if (c.get() == '/' && c.at + 1 < c.end && (text.charAt(c.at + 1) == '/' || text.charAt(c.at + 1) == '*')) break;
                    c.step();
                }
                int len = c.at - start;
                emitToken(sink, count, limit, start, c.at, OPERATOR);
                boolean isArrow = len == 2 && text.charAt(start) == '-' && text.charAt(start + 1) == '>';
                boolean isFatArrow = len == 2 && text.charAt(start) == '=' && text.charAt(start + 1) == '>';
                boolean isDoubleColon = len == 2 && text.charAt(start) == ':' && text.charAt(start + 1) == ':';
                afterDot = isArrow || isDoubleColon;
                afterColon = len == 1 && ch == ':';
                if (afterColon) afterQuestion = false;
                else if (len == 1 && ch == '?') afterQuestion = true;
                atMemberKeyStart = false;
                lineStart = false;
                if (len == 1 && ch == '=') afterDecl = declNone;
                jsxAllowed = isFatArrow || !(len == 2 && (text.charAt(start) == '+' || text.charAt(start) == '-'));
                continue;
            }
            c.step();
            lineStart = false;
        }
    }

    public static void scanMarkup(String text, int limit, Sink sink, Runnable checkpoint) {
        checkpoint.run();
        Cursor c = new Cursor(text, checkpoint);
        int count = 0;
        while (c.has() && count < limit) {
            char ch = c.get();
            if (ch == '<') {
                if (c.at + 3 < text.length() && text.regionMatches(c.at, "<!--", 0, 4)) {
                    int start = c.at;
                    c.at += 4;
                    while (c.has()) {
                        if (c.at + 2 < text.length() && text.regionMatches(c.at, "-->", 0, 3)) {
                            c.at += 3;
                            break;
                        }
                        c.step();
                    }
                    sink.token(start, c.at, COMMENT);
                    count++;
                    continue;
                }
                if (c.at + 8 < text.length() && text.regionMatches(c.at, "<![CDATA[", 0, 9)) {
                    int start = c.at;
                    c.at += 9;
                    while (c.has()) {
                        if (c.at + 2 < text.length() && text.regionMatches(c.at, "]]>", 0, 3)) {
                            c.at += 3;
                            break;
                        }
                        c.step();
                    }
                    sink.token(start, c.at, STRING);
                    count++;
                    continue;
                }
                if (c.at + 1 < text.length() && (text.charAt(c.at + 1) == '?' || text.charAt(c.at + 1) == '!')) {
                    int start = c.at;
                    boolean pi = text.charAt(c.at + 1) == '?';
                    c.step();
                    c.step();
                    while (c.has()) {
                        char cur = c.get();
                        c.step();
                        if (pi && cur == '?' && c.has() && c.get() == '>') {
                            c.step();
                            break;
                        }
                        if (!pi && cur == '>') break;
                    }
                    sink.token(start, c.at, ANNOTATION);
                    count++;
                    continue;
                }
                int opStart = c.at;
                c.step();
                boolean closing = c.has() && c.get() == '/';
                if (closing) c.step();
                sink.token(opStart, c.at, OPERATOR);
                count++;
                if (c.has() && (identStart(c.get()) || Character.isLetter(c.get()))) {
                    int tagStart = c.at;
                    while (c.has() && (Character.isLetterOrDigit(c.get()) || c.get() == '_' || c.get() == '-' || c.get() == '.' || c.get() == ':')) c.step();
                    int tagEnd = c.at;
                    boolean typeTag = Character.isUpperCase(text.charAt(tagStart)) || text.indexOf('.', tagStart) >= 0 && text.indexOf('.', tagStart) < tagEnd;
                    if (count < limit) {
                        sink.token(tagStart, tagEnd, typeTag ? TYPE : KEYWORD);
                        count++;
                    }
                }
                while (c.has() && count < limit) {
                    char a = c.get();
                    if (a == ' ' || a == '\t' || a == '\r' || a == '\n') {
                        c.step();
                        continue;
                    }
                    if (a == '>' || (a == '/' && c.at + 1 < text.length() && text.charAt(c.at + 1) == '>')) {
                        int endOp = c.at;
                        if (a == '/') c.step();
                        c.step();
                        sink.token(endOp, c.at, OPERATOR);
                        count++;
                        break;
                    }
                    if (a == '=') {
                        int eqStart = c.at;
                        c.step();
                        sink.token(eqStart, c.at, OPERATOR);
                        count++;
                        continue;
                    }
                    if (a == '"' || a == '\'') {
                        int sStart = c.at;
                        int segStart = sStart;
                        c.step();
                        while (c.has() && c.get() != a) {
                            if (c.get() == '&') {
                                int amp = c.at;
                                int scan = amp + 1;
                                while (scan < text.length() && scan - amp <= 10 && (Character.isLetterOrDigit(text.charAt(scan)) || text.charAt(scan) == '#')) scan++;
                                if (scan < text.length() && text.charAt(scan) == ';') {
                                    if (amp > segStart && count < limit) {
                                        sink.token(segStart, amp, STRING);
                                        count++;
                                    }
                                    c.at = scan + 1;
                                    if (count < limit) {
                                        sink.token(amp, c.at, ESCAPE);
                                        count++;
                                    }
                                    segStart = c.at;
                                    continue;
                                }
                            }
                            c.step();
                        }
                        if (c.has() && c.get() == a) c.step();
                        if (c.at > segStart && count < limit) {
                            sink.token(segStart, c.at, STRING);
                            count++;
                        }
                        continue;
                    }
                    if (identStart(a) || Character.isLetter(a)) {
                        int attrStart = c.at;
                        int colonAt = -1;
                        while (c.has() && (Character.isLetterOrDigit(c.get()) || c.get() == '_' || c.get() == '-' || c.get() == ':' || c.get() == '.')) {
                            if (c.get() == ':' && colonAt < 0) colonAt = c.at;
                            c.step();
                        }
                        if (colonAt > attrStart && colonAt + 1 < c.at) {
                            sink.token(attrStart, colonAt, ANNOTATION);
                            count++;
                            if (count < limit) {
                                sink.token(colonAt, colonAt + 1, OPERATOR);
                                count++;
                            }
                            if (count < limit) {
                                sink.token(colonAt + 1, c.at, PROPERTY);
                                count++;
                            }
                        } else {
                            sink.token(attrStart, c.at, PROPERTY);
                            count++;
                        }
                        continue;
                    }
                    c.step();
                }
                continue;
            }
            if (ch == '&') {
                int amp = c.at;
                int scan = amp + 1;
                while (scan < text.length() && scan - amp <= 10 && (Character.isLetterOrDigit(text.charAt(scan)) || text.charAt(scan) == '#')) scan++;
                if (scan < text.length() && text.charAt(scan) == ';') {
                    c.at = scan + 1;
                    sink.token(amp, c.at, ESCAPE);
                    count++;
                    continue;
                }
            }
            c.step();
        }
    }

    public static void scanStyle(String text, boolean scss, int limit, Sink sink, Runnable checkpoint) {
        checkpoint.run();
        Cursor c = new Cursor(text, checkpoint);
        int count = 0;
        int braceDepth = 0;
        boolean afterColon = false;
        while (c.has() && count < limit) {
            char ch = c.get();
            if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n') {
                c.step();
                continue;
            }
            if (ch == '/' && c.at + 1 < text.length()) {
                char next = text.charAt(c.at + 1);
                if (scss && next == '/') {
                    int start = c.at;
                    while (c.has() && c.get() != '\n' && c.get() != '\r') c.step();
                    sink.token(start, c.at, COMMENT);
                    count++;
                    continue;
                }
                if (next == '*') {
                    int start = c.at;
                    c.step();
                    c.step();
                    while (c.has()) {
                        if (c.get() == '*' && c.at + 1 < text.length() && text.charAt(c.at + 1) == '/') {
                            c.step();
                            c.step();
                            break;
                        }
                        c.step();
                    }
                    sink.token(start, c.at, COMMENT);
                    count++;
                    continue;
                }
            }
            if (ch == '"' || ch == '\'') {
                int start = c.at;
                c.step();
                while (c.has()) {
                    char next = c.get();
                    c.step();
                    if (next == '\\' && c.has()) c.step();
                    else if (next == ch) break;
                }
                count = emitQuotedEscapedString(text, start, c.at, limit, count, sink, false);
                continue;
            }
            if (ch == '@') {
                int start = c.at;
                c.step();
                while (c.has() && (Character.isLetterOrDigit(c.get()) || c.get() == '-' || c.get() == '_')) c.step();
                sink.token(start, c.at, KEYWORD);
                count++;
                continue;
            }
            if (ch == '!' && text.regionMatches(true, c.at, "!important", 0, 10)) {
                sink.token(c.at, c.at + 10, KEYWORD);
                count++;
                c.at += 10;
                continue;
            }
            if (ch == '{' || ch == '}' || ch == ':' || ch == ';' || ch == ',' || ch == '>' || ch == '+' || ch == '~') {
                int start = c.at;
                c.step();
                if (ch == ':' && c.has() && c.get() == ':') {
                    c.step();
                    if (braceDepth == 0) {
                        while (c.has() && (Character.isLetterOrDigit(c.get()) || c.get() == '-' || c.get() == '_')) c.step();
                        sink.token(start, c.at, ANNOTATION);
                        count++;
                        continue;
                    }
                }
                if (ch == ':' && braceDepth == 0 && c.has() && (identStart(c.get()) || c.get() == '-')) {
                    while (c.has() && (Character.isLetterOrDigit(c.get()) || c.get() == '-' || c.get() == '_')) c.step();
                    sink.token(start, c.at, ANNOTATION);
                    count++;
                    continue;
                }
                sink.token(start, c.at, OPERATOR);
                count++;
                if (ch == '{') {
                    braceDepth++;
                    afterColon = false;
                } else if (ch == '}') {
                    if (braceDepth > 0) braceDepth--;
                    afterColon = false;
                } else if (ch == ':') {
                    afterColon = true;
                } else if (ch == ';') {
                    afterColon = false;
                }
                continue;
            }
            if (ch == '-' && c.at + 1 < text.length() && text.charAt(c.at + 1) == '-') {
                int start = c.at;
                c.step();
                c.step();
                while (c.has() && (Character.isLetterOrDigit(c.get()) || c.get() == '-' || c.get() == '_')) c.step();
                sink.token(start, c.at, VARIABLE);
                count++;
                continue;
            }
            if (scss && ch == '$') {
                int start = c.at;
                c.step();
                while (c.has() && (Character.isLetterOrDigit(c.get()) || c.get() == '-' || c.get() == '_')) c.step();
                sink.token(start, c.at, VARIABLE);
                count++;
                continue;
            }
            if (ch == '#') {
                int start = c.at;
                c.step();
                while (c.has() && (Character.isLetterOrDigit(c.get()) || c.get() == '-' || c.get() == '_')) c.step();
                sink.token(start, c.at, (braceDepth > 0 && afterColon) ? NUMBER : TYPE);
                count++;
                continue;
            }
            if (braceDepth == 0 && ch == '.') {
                int start = c.at;
                c.step();
                while (c.has() && (Character.isLetterOrDigit(c.get()) || c.get() == '-' || c.get() == '_')) c.step();
                sink.token(start, c.at, TYPE);
                count++;
                continue;
            }
            if ((ch >= '0' && ch <= '9') || ((ch == '-' || ch == '.') && c.at + 1 < text.length() && Character.isDigit(text.charAt(c.at + 1)))) {
                int start = c.at;
                if (ch == '-') c.step();
                while (c.has() && (Character.isDigit(c.get()) || c.get() == '.')) c.step();
                while (c.has() && (Character.isLetter(c.get()) || c.get() == '%')) c.step();
                sink.token(start, c.at, NUMBER);
                count++;
                continue;
            }
            if (identStart(ch) || ch == '-' || ch == '*') {
                int start = c.at;
                c.step();
                while (c.has() && (Character.isLetterOrDigit(c.get()) || c.get() == '-' || c.get() == '_')) c.step();
                int end = c.at;
                if (braceDepth > 0 && !afterColon) {
                    sink.token(start, end, PROPERTY);
                    count++;
                } else if (c.has() && c.get() == '(') {
                    sink.token(start, end, FUNCTION);
                    count++;
                } else if (braceDepth == 0) {
                    sink.token(start, end, KEYWORD);
                    count++;
                } else {
                    sink.token(start, end, STRING);
                    count++;
                }
                continue;
            }
            c.step();
        }
    }

    public static void scanMarkdown(String text, int limit, Sink sink, Runnable checkpoint) {
        checkpoint.run();
        int len = text.length();
        int pos = 0;
        int count = 0;
        boolean inFence = false;
        while (pos < len && count < limit) {
            if ((pos & 4095) == 0) checkpoint.run();
            int lineStart = pos;
            int lineEnd = pos;
            while (lineEnd < len && text.charAt(lineEnd) != '\n' && text.charAt(lineEnd) != '\r') lineEnd++;
            int nextPos = lineEnd;
            if (nextPos < len && text.charAt(nextPos) == '\r') nextPos++;
            if (nextPos < len && text.charAt(nextPos) == '\n') nextPos++;
            int p = lineStart;
            while (p < lineEnd && (text.charAt(p) == ' ' || text.charAt(p) == '\t')) p++;
            if ((p + 2 < lineEnd && ((text.charAt(p) == '`' && text.charAt(p + 1) == '`' && text.charAt(p + 2) == '`')
                    || (text.charAt(p) == '~' && text.charAt(p + 1) == '~' && text.charAt(p + 2) == '~')))) {
                sink.token(lineStart, lineEnd, ANNOTATION);
                count++;
                inFence = !inFence;
                pos = nextPos;
                continue;
            }
            if (inFence) {
                if (lineEnd > lineStart) {
                    sink.token(lineStart, lineEnd, STRING);
                    count++;
                }
                pos = nextPos;
                continue;
            }
            if (p < lineEnd && text.charAt(p) == '#') {
                int h = p;
                while (h < lineEnd && h - p < 6 && text.charAt(h) == '#') h++;
                if (h < lineEnd && (text.charAt(h) == ' ' || text.charAt(h) == '\t')) {
                    sink.token(lineStart, lineEnd, KEYWORD);
                    count++;
                    pos = nextPos;
                    continue;
                }
            }
            if (p < lineEnd && text.charAt(p) == '>') {
                sink.token(p, p + 1, ANNOTATION);
                count++;
                p++;
            } else if (p + 1 < lineEnd && (text.charAt(p) == '-' || text.charAt(p) == '*' || text.charAt(p) == '+')
                    && (text.charAt(p + 1) == ' ' || text.charAt(p + 1) == '\t')) {
                sink.token(p, p + 1, ANNOTATION);
                count++;
                p += 2;
            } else if (p < lineEnd && Character.isDigit(text.charAt(p))) {
                int d = p;
                while (d < lineEnd && Character.isDigit(text.charAt(d))) d++;
                if (d + 1 < lineEnd && text.charAt(d) == '.' && (text.charAt(d + 1) == ' ' || text.charAt(d + 1) == '\t')) {
                    sink.token(p, d + 1, ANNOTATION);
                    count++;
                    p = d + 2;
                }
            }
            while (p < lineEnd && count < limit) {
                char ch = text.charAt(p);
                if (ch == '`') {
                    int close = text.indexOf('`', p + 1);
                    if (close > p && close < lineEnd) {
                        sink.token(p, close + 1, STRING);
                        count++;
                        p = close + 1;
                        continue;
                    }
                } else if (ch == '*' && p + 1 < lineEnd && text.charAt(p + 1) == '*') {
                    int close = text.indexOf("**", p + 2);
                    if (close > p + 1 && close < lineEnd) {
                        sink.token(p, close + 2, TYPE);
                        count++;
                        p = close + 2;
                        continue;
                    }
                } else if (ch == '[') {
                    int closeBracket = text.indexOf(']', p + 1);
                    if (closeBracket > p && closeBracket + 1 < lineEnd && text.charAt(closeBracket + 1) == '(') {
                        int closeParen = text.indexOf(')', closeBracket + 2);
                        if (closeParen > closeBracket + 1 && closeParen < lineEnd) {
                            sink.token(p, closeBracket + 1, FUNCTION);
                            count++;
                            if (count < limit) {
                                sink.token(closeBracket + 2, closeParen, PROPERTY);
                                count++;
                            }
                            p = closeParen + 1;
                            continue;
                        }
                    }
                } else if (ch == '<' && p + 3 < lineEnd && text.regionMatches(p, "<!--", 0, 4)) {
                    int close = text.indexOf("-->", p + 4);
                    int end = (close >= 0 && close < lineEnd) ? close + 3 : lineEnd;
                    sink.token(p, end, COMMENT);
                    count++;
                    p = end;
                    continue;
                }
                p++;
            }
            pos = nextPos;
        }
    }

    private static boolean word(char c) { return Character.isLetterOrDigit(c) || c == '_'; }
    private static final class Cursor {
        final String text;
        final int end;
        final Runnable checkpoint;
        int at;
        Cursor(String text, Runnable checkpoint) { this(text, 0, text.length(), checkpoint); }
        Cursor(String text, int start, int end, Runnable checkpoint) {
            this.text = text;
            this.at = start;
            this.end = end;
            this.checkpoint = checkpoint;
        }
        boolean has() { return at < end; }
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
