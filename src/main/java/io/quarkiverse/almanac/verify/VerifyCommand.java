package io.quarkiverse.almanac.verify;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/// `verify`: checks config snapshots against each other or against the official documentation.
@Command(name = "verify", description = "Compare config snapshots with each other (snapshots) or with the official "
        + "quarkus.io \"all config\" page (official). Exit code 1 when a result is below its threshold.",
        subcommands = {VerifySnapshotsCommand.class, VerifyOfficialCommand.class, CommandLine.HelpCommand.class})
public final class VerifyCommand implements Runnable {

    @Spec
    CommandSpec spec;

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    boolean help;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Missing subcommand: snapshots or official");
    }
}
