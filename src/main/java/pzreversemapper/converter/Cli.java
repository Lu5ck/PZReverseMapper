package pzreversemapper.converter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;

/** Command-line entry point. Exit codes: 0 success, 1 usage or cancelled, 2 error. */
public final class Cli {
    private Cli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            for (String arg : args) {
                if (arg.equals("-h") || arg.equals("--help") || arg.equals("/?")) {
                    ConverterOptions.printUsage(out);
                    return 0;
                }
            }

            ConverterOptions options = ConverterOptions.parse(args);
            if (options == null) {
                ConverterOptions.printUsage(out);
                return 1;
            }

            if (!confirmCleanOutputIfNeeded(options, err)) {
                err.println("Export canceled: output cleanup was not confirmed.");
                return 1;
            }

            MapConverter converter = new MapConverter(options);
            ConversionResult result = converter.run();

            out.println();
            out.println("Conversion finished");
            out.println("Input cells:  " + result.sourceCellCount());
            out.println("TMX cells:    " + result.targetCellCount());
            out.println("Objects:      " + result.objectCount());
            out.println("Images:       " + result.imageCount());
            out.println("Tile images:  " + result.tileImageCount());
            out.println("Buildings:    " + result.buildingCount());
            out.println("TBX files:    " + result.tbxCount() + " unique");
            out.println("Lots:         " + result.lotCount());
            out.println("Tilesets:     " + result.tileSetCount());
            out.println("Elapsed:      " + MapConverter.formatElapsed(result.elapsed()));
            out.println("Output:       " + result.outputDirectory());
            out.println("Project:      " + (Text.isNullOrWhiteSpace(result.projectFile()) ? "not written" : result.projectFile()));
            return 0;
        } catch (OutOfMemoryError ex) {
            err.println("Out of memory. Start Java with a larger heap, for example: java -Xmx8g -jar " + AppInfo.jarFileName() + " ...");
            return 2;
        } catch (Exception ex) {
            err.println(Text.messageOf(ex));
            return 2;
        }
    }

    private static boolean confirmCleanOutputIfNeeded(ConverterOptions options, PrintStream err) throws IOException {
        if (!options.cleanOutput() || !OutputCleaner.hasContent(options.outputDirectory())) {
            return true;
        }

        OutputCleaner.ensureSafeCleanTarget(options.outputDirectory());

        err.println("Clean output is enabled.");
        err.println("Existing files and folders in this output directory will be moved to the Recycle Bin/Trash:");
        err.println(options.outputDirectory());
        err.print("Type CLEAN to continue, or anything else to cancel: ");
        err.flush();

        Charset charset = System.console() != null ? System.console().charset() : Charset.defaultCharset();
        String answer = new BufferedReader(new InputStreamReader(System.in, charset)).readLine();
        if (!"CLEAN".equals(answer)) {
            return false;
        }

        options.setCleanOutputConfirmed(true);
        return true;
    }
}
