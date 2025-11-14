package com.example.ccbuild;

import picocli.CommandLine;

@CommandLine.Command(
        name = "cc-build",
        version = "0.1.0",
        mixinStandardHelpOptions = true,
        description = "SAP Commerce Cloud Build Pruner CLI",
        subcommands = {
                Commands.ListCmd.class,
                Commands.PruneCmd.class
        }
)
public class BuildTool implements Runnable {
    public static void main(String[] args) {
        int exit = new CommandLine(new BuildTool()).execute(args);
        System.exit(exit);
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
