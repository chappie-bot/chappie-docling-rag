package org.chappie.bot.rag;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import jakarta.enterprise.context.ApplicationScoped;
import picocli.CommandLine.Command;

@TopCommand
@Command(
    name = "docling-rag",
    mixinStandardHelpOptions = true,
    subcommands = {
        StandaloneBakeImageCommand.class
    },
    description = "Docling-based RAG helper CLI for Quarkus docs"
)
@ApplicationScoped
public class DoclingCli implements Runnable {
    @Override
    public void run() {
        // Prints help by default
    }
}
