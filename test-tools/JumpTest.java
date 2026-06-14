import com.jcraft.jsch.*;
import java.io.*;
import java.net.Socket;

public class JumpTest {

    // Replicates the JumpProxy from SshTunnelService exactly
    static class JumpProxy implements Proxy {
        private final Session session;
        private ChannelDirectTCPIP channel;
        private InputStream inputStream;
        private OutputStream outputStream;

        JumpProxy(Session session) { this.session = session; }

        @Override
        public void connect(SocketFactory sf, String host, int port, int timeout) throws Exception {
            ChannelDirectTCPIP ch = (ChannelDirectTCPIP) session.openChannel("direct-tcpip");
            ch.setHost(host);
            ch.setPort(port);
            ch.setOrgIPAddress("127.0.0.1");
            ch.setOrgPort(1);
            // Get streams BEFORE connect() so the internal pipe is wired up
            // before the target's SSH banner arrives
            inputStream  = ch.getInputStream();
            outputStream = ch.getOutputStream();
            ch.connect(timeout);
            channel = ch;
        }

        @Override public InputStream  getInputStream()  { return inputStream; }
        @Override public OutputStream getOutputStream() { return outputStream; }
        @Override public Socket       getSocket()       { return null; }
        @Override public void         close()           { if (channel != null) channel.disconnect(); }
    }

    static String prop(String key, String def) {
        String v = System.getProperty(key);
        return v != null ? v : def;
    }

    public static void main(String[] args) throws Exception {
        String keyPath    = prop("jumpTestKey",        "/tmp/ssh-jump-test/test_key");
        String jumpHost   = prop("jumpTestJumpHost",   "127.0.0.1");
        int    jumpPort   = Integer.parseInt(prop("jumpTestJumpPort", "2222"));
        String targetIp   = prop("jumpTestTargetIp",   "");
        int    targetPort = Integer.parseInt(prop("jumpTestTargetPort", "22"));
        String user       = prop("jumpTestUser",        "tunnel");

        int passed = 0, failed = 0;

        // ── Test 1: direct connection to jump host ──────────────────
        System.out.println("\n[TEST 1] Direct connection to jump host " + jumpHost + ":" + jumpPort);
        try {
            JSch jsch = new JSch();
            jsch.addIdentity(keyPath);
            Session sess = jsch.getSession(user, jumpHost, jumpPort);
            sess.setConfig("StrictHostKeyChecking", "no");
            sess.connect(10_000);

            ChannelExec ch = (ChannelExec) sess.openChannel("exec");
            ch.setCommand("echo hello-direct");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ch.setOutputStream(out);
            ch.connect(5_000);
            Thread.sleep(500);
            ch.disconnect();
            sess.disconnect();

            String reply = out.toString("UTF-8").trim();
            if ("hello-direct".equals(reply)) {
                System.out.println("  PASSED — got '" + reply + "'");
                passed++;
            } else {
                System.out.println("  FAILED — expected 'hello-direct', got '" + reply + "'");
                failed++;
            }
        } catch (Exception e) {
            System.out.println("  FAILED — " + e.getMessage());
            failed++;
        }

        // ── Test 2: jump proxy through to target ────────────────────
        if (targetIp.isEmpty()) {
            System.out.println("\n[TEST 2] SKIPPED — jumpTestTargetIp not set");
        } else {
            System.out.println("\n[TEST 2] Jump-proxy connection: jump → " + targetIp + ":" + targetPort);
            try {
                JSch jsch = new JSch();
                jsch.addIdentity(keyPath);

                // Open jump session
                Session jumpSess = jsch.getSession(user, jumpHost, jumpPort);
                jumpSess.setConfig("StrictHostKeyChecking", "no");
                jumpSess.connect(10_000);
                System.out.println("  jump session connected: " + jumpSess.isConnected());

                // Open target session via JumpProxy
                Session targetSess = jsch.getSession(user, targetIp, targetPort);
                targetSess.setConfig("StrictHostKeyChecking", "no");
                targetSess.setProxy(new JumpProxy(jumpSess));
                targetSess.connect(10_000);
                System.out.println("  target session connected via jump: " + targetSess.isConnected());

                // Run command on target
                ChannelExec ch = (ChannelExec) targetSess.openChannel("exec");
                ch.setCommand("echo hello-from-target");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ch.setOutputStream(out);
                ch.connect(5_000);
                Thread.sleep(500);
                ch.disconnect();
                targetSess.disconnect();
                jumpSess.disconnect();

                String reply = out.toString("UTF-8").trim();
                if ("hello-from-target".equals(reply)) {
                    System.out.println("  PASSED — got '" + reply + "'");
                    passed++;
                } else {
                    System.out.println("  FAILED — expected 'hello-from-target', got '" + reply + "'");
                    failed++;
                }
            } catch (Exception e) {
                System.out.println("  FAILED — " + e);
                e.printStackTrace();
                failed++;
            }
        }

        System.out.println("\nResults: " + passed + " passed, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }
}
