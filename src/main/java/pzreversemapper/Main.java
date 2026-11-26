package pzreversemapper;

import java.awt.GraphicsEnvironment;
import java.util.Locale;
import pzreversemapper.converter.Cli;
import pzreversemapper.studio.Studio;

/**
 * Jar entry point. With no arguments the Studio window opens (when a display is available);
 * otherwise the arguments are handled by the command-line converter.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            if (GraphicsEnvironment.isHeadless()) {
                Cli.main(args);
                return;
            }

            Studio.main(args);
            return;
        }

        String first = args[0].toLowerCase(Locale.ROOT);
        if (first.equals("--gui") || first.equals("--studio")) {
            Studio.main(new String[0]);
            return;
        }

        if (first.equals("--screenshot") || first.equals("--screenshot-about")) {
            Studio.main(args);
            return;
        }

        Cli.main(args);
    }
}
