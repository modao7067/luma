package io.github.modao7067.luma.root;
public final class RootHookDump {
    public static void main(String[] args) {
        System.out.print(args.length == 0 ? RootShellIntegration.startupScript() : RootShellIntegration.command());
    }
}
