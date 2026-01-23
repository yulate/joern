package org.example;

class Source {
    public static String src() { return "TAINTED"; }
}

class Sink {
    public static void sink(Object o) {}
}

// 模拟外部库类
class Library {
    // Arg2Ret: 返回值依赖于参数
    public String identity(String input) { return "real_impl"; }

    // Base2Ret: 返回值依赖于对象本身（this）
    public String getSelf() { return "real_impl"; }

    // Arg2Base: 将参数污染传递给对象本身（this）
    public void consume(String input) {}

    // Arg2Arg: 将参数0及传递给参数1
    public void transfer(String src, StringBuilder dest) {}
}

public class Test {
    public void testFlows() {
        Library lib = new Library();
        // 1. Arg2Ret Path
        // Source -> identity -> Sink
        String s1 = lib.identity(Source.src());
        Sink.sink(s1);

        // 2. Base2Ret Path
        // Source -> consume -> lib -> getSelf -> Sink
        lib.consume(Source.src());
        String s2 = lib.getSelf();
        Sink.sink(s2);

        // 3. Arg2Arg Path
        StringBuilder dest = new StringBuilder();
        // Source -> transfer -> dest -> Sink
        lib.transfer(Source.src(), dest);
        Sink.sink(dest);
    }
}
