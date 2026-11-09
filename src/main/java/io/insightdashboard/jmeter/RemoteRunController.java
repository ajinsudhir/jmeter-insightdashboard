package io.insightdashboard.jmeter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.List;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.UIKeyboardInteractive;
import com.jcraft.jsch.UserInfo;

/**
 * SSH connection and remote command execution for the Remote Run tab -
 * triggering a non-GUI JMeter run on a remote host exactly the way someone
 * already does by hand over git bash: {@code JVM_ARGS="..." .../bin/jmeter
 * -n -t ... -l ... -j ...}, plus stopping it gracefully or abruptly
 * afterward.
 *
 * <p><b>Security notes, stated plainly rather than left implicit:</b>
 * <ul>
 *   <li>A password, when used, is held only in memory for the life of this
 *   object - never written to disk, never logged. Key-based auth avoids
 *   handling a password at all, which is why it's offered as the other
 *   option rather than the only one.</li>
 *   <li>Host key checking is disabled ({@code StrictHostKeyChecking=no}) to
 *   keep a first version simple - this accepts any host key without
 *   comparing it against a known_hosts file, which is a real tradeoff
 *   (vulnerable to a man-in-the-middle on the network path) worth knowing
 *   about, not something to discover later. Reasonable for a trusted,
 *   internal host reached directly; not a substitute for host key
 *   verification on anything reachable over an untrusted network.</li>
 * </ul>
 *
 * <p>Uses the {@code com.github.mwiede:jsch} fork, not the original
 * {@code com.jcraft:jsch} - confirmed before choosing it that the original
 * (last released 2018) can't read the OpenSSH-v1 key format {@code
 * ssh-keygen} has emitted by default since OpenSSH 7.8, and lacks
 * rsa-sha2-* support needed against OpenSSH 8.8 and later, which would have
 * meant key-based auth silently failing against any reasonably current SSH
 * server. The fork keeps the same {@code com.jcraft.jsch} package and API,
 * so every class referenced below is exactly the long-stable, widely-
 * documented JSch API, unchanged by the fork.
 */
final class RemoteRunController {

    enum AuthMethod { PASSWORD, KEY }

    private Session session;

    /**
     * Opens the SSH session. Password auth sets the session's password
     * directly; key auth registers the private key (with its passphrase,
     * if any) with a fresh JSch instance before the session is created,
     * which is JSch's own required order - addIdentity() must happen
     * before getSession().
     */
    void connect(String host, int port, String username, AuthMethod authMethod,
                 String password, String keyPath, String keyPassphrase, int timeoutMs) throws JSchException {
        disconnect(); // idempotent - always safe to call, never leaks a prior session
        JSch jsch = new JSch();
        if (authMethod == AuthMethod.KEY) {
            if (keyPassphrase != null && !keyPassphrase.isEmpty()) {
                jsch.addIdentity(keyPath, keyPassphrase.getBytes(StandardCharsets.UTF_8));
            } else {
                jsch.addIdentity(keyPath);
            }
        }
        Session s = jsch.getSession(username, host, port);
        if (authMethod == AuthMethod.PASSWORD) {
            s.setPassword(password);
            // Plain SSH "password" auth and "keyboard-interactive" are
            // genuinely different SSH authentication methods, even though
            // a server commonly uses keyboard-interactive to do the exact
            // same thing (prompt for the account's password, routed
            // through PAM) - confirmed as a real, common server
            // configuration (sshd_config's PasswordAuthentication no, with
            // keyboard-interactive still enabled) before adding this,
            // not assumed. setPassword() alone only drives the plain
            // "password" method; without this UserInfo, a
            // keyboard-interactive prompt has nothing to answer it with
            // and fails even when the password itself is correct.
            s.setUserInfo(new PasswordUserInfo(password));
            // Deliberately omits "publickey": no identity is ever loaded in
            // this code path (that only happens under AuthMethod.KEY), so
            // attempting it here has zero chance of succeeding and only
            // spends one of the server's limited auth tries for nothing -
            // servers commonly disconnect with "Too many authentication
            // failures" after a small number of attempts (sometimes as few
            // as 2-3), so every method tried that can't possibly work is
            // attempt budget taken away from the one that might.
            s.setConfig("PreferredAuthentications", "keyboard-interactive,password");
        }
        // See class javadoc for the tradeoff this accepts.
        s.setConfig("StrictHostKeyChecking", "no");
        s.connect(timeoutMs);
        this.session = s;
    }

    boolean isConnected() {
        return session != null && session.isConnected();
    }

    void disconnect() {
        if (session != null) {
            session.disconnect();
            session = null;
        }
    }

    /**
     * Runs a single remote command to completion, synchronously, collecting
     * stdout+stderr combined. Used for short-lived commands (a connectivity
     * test, shutdown.sh/stoptest.sh) where waiting for a result makes sense
     * - NOT used for the long-running jmeter command itself, which uses
     * {@link #startRemoteTest} instead so the caller isn't blocked for the
     * whole test duration.
     */
    String execSync(String command, int timeoutMs) throws JSchException, IOException {
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        try {
            channel.setCommand(command);
            ByteArrayOutputStream combined = new ByteArrayOutputStream();
            channel.setOutputStream(combined);
            channel.setErrStream(combined); // stdout+stderr merged into one captured stream
            channel.connect(timeoutMs);
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!channel.isClosed()) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IOException("Command timed out after " + timeoutMs + "ms: " + command);
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for: " + command, ie);
                }
            }
            return combined.toString(StandardCharsets.UTF_8.name());
        } finally {
            channel.disconnect();
        }
    }

    /**
     * Starts the actual jmeter -n -t ... command on the remote host and
     * returns immediately - the command keeps running on its own background
     * thread, streaming each output line to onOutputLine as it arrives and
     * calling onExit once the process actually finishes (not when this
     * method returns, which is immediate).
     *
     * <p>Built with explicit, separate parameters (not one pre-assembled
     * command string from the caller) specifically so paths and the JMX
     * filename can be shell-quoted correctly here, in one place, rather
     * than leaving that to whoever builds the command string - a path
     * containing a space would otherwise silently break the command.
     */
    void startRemoteTest(String jvmArgs, String jmeterBinDir, String jmxPath, String outputCsvPath,
                          String logPath, String extraArgs, boolean outputFormatXml,
                          Consumer<String> onOutputLine, IntConsumer onExit, Consumer<Exception> onError) {
        String jmeterExe = shellJoin(jmeterBinDir) + "/jmeter";
        StringBuilder cmd = new StringBuilder();
        if (jvmArgs != null && !jvmArgs.trim().isEmpty()) {
            cmd.append("JVM_ARGS=").append(shellQuote(jvmArgs.trim())).append(' ');
        }
        cmd.append(shellQuote(jmeterExe))
                .append(" -n -t ").append(shellQuote(jmxPath))
                .append(" -l ").append(shellQuote(outputCsvPath))
                .append(" -j ").append(shellQuote(logPath));
        if (outputFormatXml) {
            // Confirmed exact property name and -J override syntax against
            // JMeter's own official user manual before using it, not
            // assumed. Placed before extraArgs so a person's own -J flags
            // in that field could still override this if they wanted to.
            cmd.append(" -Jjmeter.save.saveservice.output_format=xml");
        }
        if (extraArgs != null && !extraArgs.trim().isEmpty()) {
            cmd.append(' ').append(extraArgs.trim());
        }
        String commandLine = cmd.toString();

        Thread worker = new Thread(() -> {
            ChannelExec channel = null;
            try {
                channel = (ChannelExec) session.openChannel("exec");
                channel.setCommand(commandLine);
                InputStream stdout = channel.getInputStream();
                channel.setErrStream(System.err); // remote stderr surfaces in this plugin's own JMeter log, not lost
                channel.connect();

                byte[] buf = new byte[4096];
                StringBuilder lineBuf = new StringBuilder();
                while (true) {
                    while (stdout.available() > 0) {
                        int n = stdout.read(buf, 0, buf.length);
                        if (n < 0) {
                            break;
                        }
                        for (int i = 0; i < n; i++) {
                            char c = (char) (buf[i] & 0xFF);
                            if (c == '\n') {
                                onOutputLine.accept(lineBuf.toString());
                                lineBuf.setLength(0);
                            } else if (c != '\r') {
                                lineBuf.append(c);
                            }
                        }
                    }
                    if (channel.isClosed()) {
                        if (stdout.available() > 0) {
                            continue; // drain whatever arrived in the same instant the channel closed
                        }
                        break;
                    }
                    Thread.sleep(200);
                }
                if (lineBuf.length() > 0) {
                    onOutputLine.accept(lineBuf.toString());
                }
                onExit.accept(channel.getExitStatus());
            } catch (Exception ex) {
                onError.accept(ex);
            } finally {
                if (channel != null) {
                    channel.disconnect();
                }
            }
        }, "insightdashboard-remote-run");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Gracefully stops the remote run via its own shutdown.sh, executed ON
     * the remote host over this same SSH session - not by connecting to its
     * shutdown port (4445 by default) directly from here, which would not
     * work: that listener only accepts connections from the same host the
     * non-GUI JMeter process is running on, confirmed before designing this
     * rather than assumed. abrupt uses stoptest.sh instead of shutdown.sh -
     * same mechanism, immediate rather than graceful.
     */
    String stopRemoteTest(String jmeterBinDir, boolean abrupt, int timeoutMs) throws JSchException, IOException {
        String script = abrupt ? "stoptest.sh" : "shutdown.sh";
        String command = shellQuote(shellJoin(jmeterBinDir) + "/" + script);
        return execSync(command, timeoutMs);
    }

    /** One entry in a remote directory listing. */
    static final class RemoteFileEntry {
        final String name;
        final boolean directory;

        RemoteFileEntry(String name, boolean directory) {
            this.name = name;
            this.directory = directory;
        }
    }

    /** The remote user's home directory - a sensible starting point for
     *  browsing, confirmed via ChannelSftp's own pwd() (an SFTP session
     *  starts in the user's home directory by default, same as a normal
     *  SSH/SFTP login would). */
    String remoteHomeDir() throws JSchException, SftpException {
        ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
        try {
            sftp.connect();
            return sftp.pwd();
        } finally {
            sftp.disconnect();
        }
    }

    /** Lists one directory's contents, directories first then files, both
     *  alphabetically - "." and ".." are excluded since the file browser
     *  provides its own "up one level" navigation instead. */
    @SuppressWarnings("unchecked")
    List<RemoteFileEntry> listDirectory(String remotePath) throws JSchException, SftpException {
        ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
        try {
            sftp.connect();
            List<RemoteFileEntry> entries = new java.util.ArrayList<>();
            java.util.Vector<ChannelSftp.LsEntry> ls = sftp.ls(remotePath);
            for (ChannelSftp.LsEntry entry : ls) {
                String name = entry.getFilename();
                if (".".equals(name) || "..".equals(name)) {
                    continue;
                }
                entries.add(new RemoteFileEntry(name, entry.getAttrs().isDir()));
            }
            entries.sort((a, b) -> {
                if (a.directory != b.directory) {
                    return a.directory ? -1 : 1;
                }
                return a.name.compareToIgnoreCase(b.name);
            });
            return entries;
        } finally {
            sftp.disconnect();
        }
    }

    /** The remote file's current size in bytes, or -1 if it doesn't exist
     *  yet (the remote jmeter process may not have created the output file
     *  the instant the run starts). Cheap enough to call before every
     *  download, to skip one entirely when the file hasn't grown since the
     *  last poll. */
    long remoteFileSize(String remotePath) throws JSchException, SftpException {
        ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
        try {
            sftp.connect();
            return sftp.lstat(remotePath).getSize();
        } catch (SftpException se) {
            if (se.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                return -1;
            }
            throw se;
        } finally {
            sftp.disconnect();
        }
    }

    /**
     * Deletes a remote file if it exists; silently does nothing if it
     * doesn't. Confirmed JMeter's own documented behavior before relying on
     * this: when a run's output path already has a file at it, JMeter
     * APPENDS new data rather than overwriting it - reusing a path from an
     * earlier run (in the same or a different output format) would
     * silently corrupt the file with mixed-format or stale-plus-fresh
     * content, which is exactly what this exists to prevent.
     */
    void deleteRemoteFileIfExists(String remotePath) throws JSchException, SftpException {
        ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
        try {
            sftp.connect();
            sftp.rm(remotePath);
        } catch (SftpException se) {
            if (se.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                throw se;
            }
        } finally {
            sftp.disconnect();
        }
    }

    /** Uploads a local file to the remote host, overwriting whatever's
     *  already at remotePath - the reverse of downloadFile(), used to copy
     *  the locally-open .jmx to the remote host before running it there. */
    void uploadFile(File localFile, String remotePath) throws JSchException, SftpException {
        ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
        try {
            sftp.connect();
            sftp.put(localFile.getAbsolutePath(), remotePath);
        } finally {
            sftp.disconnect();
        }
    }

    /**
     * Downloads the whole remote file to localFile, overwriting it. Always
     * the whole file, not an incremental byte range - CSVSaveService.
     * processSamples() (used by the live-monitoring side of this feature)
     * needs the CSV's header line, which only exists at the very start of
     * the file, so an incremental fetch would need to separately track and
     * re-prepend that header to each new chunk. Re-downloading the whole,
     * still-growing file each poll is simpler and more obviously correct,
     * at the cost of more network/CPU use on a very large file or a very
     * long-running test - a real scaling limit worth knowing rather than a
     * hidden one.
     */
    void downloadFile(String remotePath, File localFile) throws JSchException, SftpException, java.io.IOException {
        ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
        try {
            sftp.connect();
            try (OutputStream out = new FileOutputStream(localFile)) {
                sftp.get(remotePath, out);
            }
        } finally {
            sftp.disconnect();
        }
    }

    /**
     * Answers both plain password auth (getPassword()) and
     * keyboard-interactive prompts (promptKeyboardInteractive()) with the
     * same password - headless, so every yes/no or message prompt is a
     * no-op that proceeds without actually asking anyone, since there's no
     * one here to ask. Matches the confirmed, standard JSch pattern for
     * this (implementing both UserInfo and UIKeyboardInteractive on one
     * class, set via Session.setUserInfo()) - verified against JSch's own
     * official examples before writing this, not assumed.
     */
    private static final class PasswordUserInfo implements UserInfo, UIKeyboardInteractive {
        private final String password;

        PasswordUserInfo(String password) {
            this.password = password;
        }

        @Override
        public String getPassword() {
            return password;
        }

        @Override
        public boolean promptPassword(String message) {
            return true;
        }

        @Override
        public String getPassphrase() {
            return null;
        }

        @Override
        public boolean promptPassphrase(String message) {
            return true;
        }

        @Override
        public boolean promptYesNo(String message) {
            return true;
        }

        @Override
        public void showMessage(String message) {
            // No-op: headless, nowhere to show a server banner/message.
        }

        @Override
        public String[] promptKeyboardInteractive(String destination, String name, String instruction,
                                                    String[] prompt, boolean[] echo) {
            String[] responses = new String[prompt.length];
            java.util.Arrays.fill(responses, password);
            return responses;
        }
    }

    /** Wraps a value in single quotes for safe use as one shell argument,
     *  escaping any single quote already in it - the standard, correct way
     *  to shell-quote an arbitrary string (including paths with spaces)
     *  for a POSIX shell, not just wrapping in double quotes (which still
     *  allows $-expansion and backtick execution inside the value). */
    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** Local helper so startRemoteTest's path-joining doesn't rely on the
     *  caller having already normalized a possible trailing slash. */
    private static String shellJoin(String dirPath) {
        String trimmed = dirPath.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
