package dev.vmn.coverlauncher;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

/** One long-lived {@code su} shell, so injected input does not pay su start-up cost each time. */
final class Root {
    private static final String END = "__COVER_END__";

    private static Process proc;
    private static BufferedWriter in;
    private static BufferedReader out;

    private Root() {}

    /** Runs {@code cmd} as root and returns its combined stdout/stderr. Blocking: never call on the main thread. */
    static synchronized String run(String cmd) throws IOException {
        if (proc == null || !proc.isAlive()) {
            proc = new ProcessBuilder("su").redirectErrorStream(true).start();
            in = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream()));
            out = new BufferedReader(new InputStreamReader(proc.getInputStream()));
        }
        try {
            // Bare echo first: output without a trailing newline (cat of a file) would otherwise glue onto the marker.
            in.write(cmd + "\necho\necho " + END + "\n");
            in.flush();
            StringBuilder sb = new StringBuilder();
            for (String line; (line = out.readLine()) != null; ) {
                if (line.equals(END)) return sb.toString();
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            proc.destroy();
            throw e;
        }
        proc.destroy();
        throw new IOException("su exited (root denied?)");
    }
}
