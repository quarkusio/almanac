package io.quarkiverse.almanac;

import io.quarkiverse.almanac.cli.AllCommand;
import io.quarkiverse.almanac.cli.AssembleCommand;
import io.quarkiverse.almanac.cli.BomCommand;
import io.quarkiverse.almanac.cli.ClassesCommand;
import io.quarkiverse.almanac.cli.ConfigCommand;
import io.quarkiverse.almanac.cli.DatasetCommand;
import io.quarkiverse.almanac.cli.ExtensionsCommand;
import io.quarkiverse.almanac.cli.PlatformCommand;
import io.quarkiverse.almanac.cli.VersionsCommand;
import io.quarkiverse.almanac.util.Log;
import io.quarkiverse.almanac.verify.VerifyCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/// Entry point of `quarkus-almanac.jar`.
@Command(name = "quarkus-almanac", mixinStandardHelpOptions = true, version = "quarkus-almanac 0.1",
        description = "Extracts Quarkus release data (BOM, platform BOM, config properties, extensions, classes) "
                + "from Maven Central into a committed dataset and assembles the data files of the Quarkus Almanac site.",
        subcommands = {VersionsCommand.class, BomCommand.class, PlatformCommand.class, ConfigCommand.class,
                ExtensionsCommand.class, ClassesCommand.class, AssembleCommand.class, AllCommand.class,
                DatasetCommand.class, VerifyCommand.class, CommandLine.HelpCommand.class})
public final class Main implements Runnable {

    @Spec
    CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Missing command");
    }

    public static void main(String[] args) {
        CommandLine commandLine = new CommandLine(new Main())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setExecutionExceptionHandler((e, command, parseResult) -> {
                    Log.error("%s", e.getMessage() != null ? e.getMessage() : e.toString());
                    if (Log.isVerbose()) {
                        e.printStackTrace();
                    }
                    return 1;
                });
        System.exit(commandLine.execute(args));
    }
}
