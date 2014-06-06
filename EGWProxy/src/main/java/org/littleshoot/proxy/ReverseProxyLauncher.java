package org.littleshoot.proxy;

import java.io.File;
import java.util.Arrays;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.commons.cli.UnrecognizedOptionException;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.xml.DOMConfigurator;
import org.littleshoot.proxy.impl.EGWServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Launches a new EGW proxy.
 */
public class ReverseProxyLauncher {

    private static final Logger LOG = LoggerFactory.getLogger(ReverseProxyLauncher.class);

    private static final String OPTION_HELP = "help";

    /**
     * Starts the proxy from the command line.
     * 
     * @param args
     *            Any command line arguments.
     */
    public static void main(final String... args) {
        pollLog4JConfigurationFileIfAvailable();
        LOG.info("Running LittleProxy with args: {}", Arrays.asList(args));
        
        final Options options = new Options();
        options.addOption(null, OPTION_HELP, false,
                "Display command line help.");
        
        final CommandLineParser parser = new PosixParser();
        final CommandLine cmd;
        try {
            cmd = parser.parse(options, args);
            if (cmd.getArgs().length > 0) {
                throw new UnrecognizedOptionException(
                        "Extra arguments were provided in "
                                + Arrays.asList(args));
            }
        } catch (final ParseException e) {
            printHelp(options,
                    "Could not parse command line: " + Arrays.asList(args));
            return;
        }
        if (cmd.hasOption(OPTION_HELP)) {
            printHelp(options, null);
            return;
        }
        
        System.out.println("About to start egw.");
        EGWServer server = new EGWServer("egw.properties");
        
        
        System.out.println("About to start...");
        server.start();
    }

    private static void printHelp(final Options options,
            final String errorMessage) {
        if (!StringUtils.isBlank(errorMessage)) {
            LOG.error(errorMessage);
            System.err.println(errorMessage);
        }

        final HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("EgressGateway", options);
    }

    private static void pollLog4JConfigurationFileIfAvailable() {
        File log4jConfigurationFile = new File(
                "src/test/resources/log4j.xml");
        if (log4jConfigurationFile.exists()) {
            DOMConfigurator.configureAndWatch(
                    log4jConfigurationFile.getAbsolutePath(), 15);
        }
    }
}
